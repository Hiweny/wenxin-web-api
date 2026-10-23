package com.hiweny.wenxinapi.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 OpenAI 的 messages 折叠成文心单轮 prompt。
 *
 * 设计目标（2026-10 重写）：
 *  1) **上下文预算**：文心是"单轮问答"接口，塞太长的文本会稀释注意力、答非所问，甚至被上游
 *     判为异常请求直接回「😩抱歉，出了点小问题，请稍后重试」。所以这里按字符预算裁剪：
 *     系统指令 → 工具定义 → 最近的历史 → 当前问题，超出预算的部分从**最旧**的历史开始丢。
 *  2) **结构清晰**：分成【系统指令】【对话历史】【当前问题】【回答要求】四段，并在结尾明确
 *     要求"只回答最后一条问题"，避免模型把整段历史当成待处理材料。
 *  3) **工具定义精简**：不再塞完整 JSON Schema，只给名字 + 一句话说明 + 参数名与类型。
 */
public final class PromptBuilder {

    public static final String TOOL_OPEN = "@@tool_calls@@";
    public static final String TOOL_CLOSE = "@@end@@";

    /** 预算（字符数）。文心上游对超长 prompt 会答非所问或直接拒答，实测 8K 以内最稳。 */
    public static final int DEFAULT_BUDGET = 7000;
    private static final int SYSTEM_MAX = 1600;
    private static final int TOOLS_MAX = 1400;
    private static final int CURRENT_MIN = 400;

    public static class Built {
        public String prompt = "";
        public boolean toolsEnabled = false;
        public List<String> toolNames = new ArrayList<>();
        public List<String> images = new ArrayList<>();
        public int droppedTurns = 0;      // 因预算被丢弃的历史条数
        public boolean clipped = false;   // 当前问题是否被截断
    }

    @SuppressWarnings("unchecked")
    private static String contentToText(Object content) {
        return contentToText(content, null);
    }

    @SuppressWarnings("unchecked")
    private static String contentToText(Object content, List<String> imageSink) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) content;
            for (String k : new String[]{"text", "content", "parts", "value"}) {
                if (m.get(k) != null) {
                    String t = contentToText(m.get(k), imageSink);
                    if (!t.isEmpty()) return t;
                }
            }
            return "";
        }
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<Object>) content) {
                if (part instanceof String) { sb.append(part); continue; }
                if (!(part instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) part;
                String type = Json.str(m, "type");
                if ("text".equals(type) || type == null) {
                    String t = Json.str(m, "text");
                    if (t != null) sb.append(t);
                } else if ("image_url".equals(type)) {
                    Object iu = m.get("image_url");
                    String url = null;
                    if (iu instanceof String) url = (String) iu;
                    else if (iu instanceof Map) url = Json.str((Map<String, Object>) iu, "url");
                    if (url != null && !url.isEmpty() && imageSink != null) imageSink.add(url);
                    sb.append("[图片]");
                } else if ("input_image".equals(type)) {
                    String url = Json.str(m, "image_url");
                    if (url != null && !url.isEmpty() && imageSink != null) imageSink.add(url);
                    sb.append("[图片]");
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    public static Built build(List<Object> messages, Object tools) {
        return build(messages, tools, DEFAULT_BUDGET);
    }

    @SuppressWarnings("unchecked")
    public static Built build(List<Object> messages, Object tools, int budget) {
        Built b = new Built();
        if (budget < 2000) budget = DEFAULT_BUDGET;

        StringBuilder sys = new StringBuilder();
        List<String> history = new ArrayList<>();
        String lastUser = "";

        if (messages != null) {
            int lastUserIdx = -1;
            for (int i = 0; i < messages.size(); i++) {
                Object o = messages.get(i);
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                if ("user".equals(Json.str(m, "role"))) lastUserIdx = i;
            }
            for (int i = 0; i < messages.size(); i++) {
                Object o = messages.get(i);
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String role = Json.str(m, "role");
                String text = contentToText(m.get("content"));
                if (role == null) role = "user";
                if ("system".equals(role) || "developer".equals(role)) {
                    if (sys.length() < 6000) sys.append(text).append("\n");
                } else if ("assistant".equals(role)) {
                    String tc = toolCallsToText(m.get("tool_calls"));
                    String body = text.isEmpty() ? tc : text;
                    if (!body.trim().isEmpty()) history.add("助手：" + body);
                } else if ("tool".equals(role) || "function".equals(role)) {
                    history.add("工具结果(" + nvl(Json.str(m, "name")) + ")：" + clipMiddle(text, 1200));
                } else {
                    if (i == lastUserIdx) {
                        if (!text.trim().isEmpty()) lastUser = text;
                        List<String> imgs = new ArrayList<>();
                        contentToText(m.get("content"), imgs);
                        b.images.addAll(imgs);
                    } else if (!text.trim().isEmpty()) {
                        history.add("用户：" + text);
                    }
                }
            }
        }

        // ---- 工具定义（精简） ----
        StringBuilder toolDefs = new StringBuilder();
        if (tools instanceof List && !((List<Object>) tools).isEmpty()) {
            b.toolsEnabled = true;
            for (Object t : (List<Object>) tools) {
                if (!(t instanceof Map)) continue;
                Map<String, Object> tm = (Map<String, Object>) t;
                Map<String, Object> fn = Json.obj(tm, "function");
                if (fn.isEmpty()) fn = tm;
                String name = Json.str(fn, "name");
                if (name == null) continue;
                b.toolNames.add(name);
                String desc = firstSentence(nvl(Json.str(fn, "description")), 70);
                StringBuilder args = new StringBuilder();
                Object params = fn.get("parameters");
                if (params instanceof Map) {
                    Map<String, Object> props = Json.obj((Map<String, Object>) params, "properties");
                    for (Map.Entry<String, Object> e : props.entrySet()) {
                        if (args.length() > 0) args.append(", ");
                        String ty = "string";
                        if (e.getValue() instanceof Map) {
                            String t2 = Json.str((Map<String, Object>) e.getValue(), "type");
                            if (t2 != null) ty = t2;
                        }
                        args.append(e.getKey()).append(':').append(ty);
                    }
                }
                if (toolDefs.length() < TOOLS_MAX) {
                    toolDefs.append("- ").append(name);
                    if (!desc.isEmpty()) toolDefs.append("：").append(desc);
                    if (args.length() > 0) toolDefs.append("　参数{").append(args).append("}");
                    toolDefs.append('\n');
                }
            }
        }

        // ---- 预算分配：系统 + 工具 + 当前问题 → 余下给历史 ----
        String sysText = sys.toString().trim();
        if (sysText.length() > SYSTEM_MAX) sysText = clipMiddle(sysText, SYSTEM_MAX);

        String current = lastUser.trim();
        boolean clipped = false;
        int curBudget = Math.min(2600, Math.max(CURRENT_MIN, budget / 3));
        if (current.length() > curBudget) {
            current = clipMiddle(current, curBudget);
            clipped = true;
        }

        int used = sysText.length() + toolDefs.length() + current.length() + 200;
        int histBudget = Math.max(0, budget - used);

        // 从最近的历史往前取，直到预算用完
        List<String> picked = new ArrayList<>();
        int acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String h = history.get(i);
            if (acc + h.length() > histBudget) { b.droppedTurns = i + 1; break; }
            picked.add(0, h);
            acc += h.length() + 1;
        }

        // 单轮纯文本：零包装直通，最快也最自然
        if (sysText.isEmpty() && picked.isEmpty() && !b.toolsEnabled) {
            b.prompt = current;
            b.clipped = clipped;
            return b;
        }

        StringBuilder p = new StringBuilder();
        if (!sysText.isEmpty()) p.append("【系统指令】\n").append(sysText).append("\n\n");
        if (b.toolsEnabled) {
            p.append("【可用工具】\n").append(toolDefs);
            p.append("需要调用工具时，只输出下面这一行（保留标记，不要代码块、不要解释）：\n");
            p.append(TOOL_OPEN).append("[{\"name\":\"工具名\",\"arguments\":{...}}]").append(TOOL_CLOSE).append("\n\n");
            p.append("不需要工具就直接正常回答。\n\n");
        }
        if (!picked.isEmpty()) {
            p.append("【对话历史】（仅供理解上下文，不要复述）\n");
            if (b.droppedTurns > 0) p.append("（更早的 ").append(b.droppedTurns).append(" 条已省略）\n");
            for (String h : picked) p.append(h).append('\n');
            p.append('\n');
        }
        p.append("【当前问题】\n").append(current).append("\n\n");
        p.append("【回答要求】只针对【当前问题】作答；不要复述或总结上面的历史；直接给结论，必要时再补充说明。");
        b.prompt = p.toString();
        b.clipped = clipped;
        return b;
    }

    /** 头尾保留的中间截断（保住开头的指令与结尾的问题） */
    public static String clipMiddle(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int head = (int) (max * 0.7);
        int tail = max - head - 20;
        if (tail < 0) tail = 0;
        return s.substring(0, head) + "\n…（此处省略 " + (s.length() - head - tail) + " 字）…\n"
                + s.substring(s.length() - tail);
    }

    private static String firstSentence(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        int dot = t.indexOf('。');
        if (dot > 8 && dot < max) return t.substring(0, dot + 1);
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    @SuppressWarnings("unchecked")
    private static String toolCallsToText(Object tcs) {
        if (!(tcs instanceof List)) return "";
        StringBuilder sb = new StringBuilder(TOOL_OPEN);
        sb.append('[');
        boolean first = true;
        for (Object o : (List<Object>) tcs) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> fn = Json.obj(m, "function");
            if (fn.isEmpty()) fn = m;
            String name = Json.str(fn, "name");
            if (name == null) continue;
            if (!first) sb.append(',');
            first = false;
            Object args = fn.get("arguments");
            sb.append("{\"name\":");
            Json.quote(sb, name);
            sb.append(",\"arguments\":");
            sb.append(args == null ? "{}" : (args instanceof String ? (String) args : Json.write(args)));
            sb.append('}');
        }
        if (first) return "";
        sb.append(']').append(TOOL_CLOSE);
        return sb.toString();
    }
}
