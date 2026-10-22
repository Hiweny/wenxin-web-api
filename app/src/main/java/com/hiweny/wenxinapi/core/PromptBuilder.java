package com.hiweny.wenxinapi.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 OpenAI 的 messages 折叠成文心单轮 prompt，并按需注入工具协议。
 *
 * 文心网页端没有原生 function calling，也不支持服务端多轮记忆（每次请求独立），
 * 因此这里负责：历史拼接 + 工具描述注入 + 输出格式约束。
 */
public final class PromptBuilder {

    public static final String TOOL_OPEN = "@@tool_calls@@";
    public static final String TOOL_CLOSE = "@@end@@";

    public static class Built {
        public String prompt = "";
        public boolean toolsEnabled = false;
        public List<String> toolNames = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static String contentToText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
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
                    sb.append("[图片]");
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    @SuppressWarnings("unchecked")
    public static Built build(List<Object> messages, Object tools) {
        Built b = new Built();
        StringBuilder sys = new StringBuilder();
        List<String> history = new ArrayList<>();
        String lastUser = "";

        if (messages != null) {
            // 收集所有非用户消息作为"上下文"，最后一条 user 作为当前问题
            int lastUserIdx = -1;
            for (int i = 0; i < messages.size(); i++) {
                Object o = messages.get(i);
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String role = Json.str(m, "role");
                if ("user".equals(role)) lastUserIdx = i;
            }
            for (int i = 0; i < messages.size(); i++) {
                Object o = messages.get(i);
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                String role = Json.str(m, "role");
                String text = contentToText(m.get("content"));
                if (role == null) role = "user";
                if ("system".equals(role) || "developer".equals(role)) {
                    sys.append(text).append("\n");
                } else if ("assistant".equals(role)) {
                    if (i == lastUserIdx) continue;
                    String tc = toolCallsToText(m.get("tool_calls"));
                    history.add("助手：" + (text.isEmpty() ? tc : text));
                } else if ("tool".equals(role) || "function".equals(role)) {
                    history.add("工具结果(" + nvl(Json.str(m, "name")) + ")：" + text);
                } else {
                    if (i == lastUserIdx) lastUser = text;
                    else history.add("用户：" + text);
                }
            }
        }

        // 工具协议
        if (tools instanceof List && !((List<Object>) tools).isEmpty()) {
            b.toolsEnabled = true;
            StringBuilder defs = new StringBuilder();
            for (Object t : (List<Object>) tools) {
                if (!(t instanceof Map)) continue;
                Map<String, Object> tm = (Map<String, Object>) t;
                Map<String, Object> fn = Json.obj(tm, "function");
                if (fn.isEmpty()) fn = tm;
                String name = Json.str(fn, "name");
                if (name == null) continue;
                b.toolNames.add(name);
                String desc = nvl(Json.str(fn, "description"));
                Object params = fn.get("parameters");
                defs.append("- ").append(name).append(": ").append(desc).append("\n");
                if (params != null) defs.append("  参数JSONSchema: ").append(Json.write(params)).append("\n");
            }
            sys.append("\n【工具调用协议】\n");
            sys.append("你可以在需要时调用以下工具：\n").append(defs);
            sys.append("调用工具时必须且只能输出一行如下格式的内容（必须原样输出 @@tool_calls@@ 与 @@end@@ 这两个标记，不要改成别的写法，不要使用代码块，不要输出任何解释）：\n");
            sys.append(TOOL_OPEN).append("[{\"name\":\"工具名\",\"arguments\":{...}}]").append(TOOL_CLOSE).append("\n");
            sys.append("如果不需要调用工具，就直接正常回答用户。一次可以调用多个工具（数组里多个对象）。\n");
        }

        StringBuilder p = new StringBuilder();
        if (sys.length() > 0) {
            p.append("【系统指令】\n").append(sys.toString().trim()).append("\n\n");
        }
        if (!history.isEmpty()) {
            p.append("【对话历史】\n");
            int from = Math.max(0, history.size() - 24);
            for (int i = from; i < history.size(); i++) p.append(history.get(i)).append("\n");
            p.append("\n");
        }
        p.append("【当前问题】\n").append(lastUser);
        b.prompt = p.toString();
        return b;
    }

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
            String name = Json.str(fn, "name");
            String args = Json.str(fn, "arguments");
            if (name == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":");
            Json.quote(sb, name);
            sb.append(",\"arguments\":");
            sb.append(args == null || args.trim().isEmpty() ? "{}" : args.trim());
            sb.append('}');
        }
        sb.append(']').append(TOOL_CLOSE);
        return sb.toString();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
