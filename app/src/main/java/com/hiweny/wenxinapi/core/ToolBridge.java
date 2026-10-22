package com.hiweny.wenxinapi.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用解析 / 流式防泄漏。
 *
 * 模型被要求在需要时输出：<tool_calls>[{"name":"x","arguments":{...}}]</tool_calls>
 * 但实际输出经常"写残"（缺闭合、加代码块、裸 JSON、偶发全角括号），所以这里做宽容识别。
 */
public final class ToolBridge {

    public static class Call {
        public String name;
        public String id;
        public String arguments; // JSON object text
    }

    private static final Pattern OPEN = Pattern.compile(
            "@@\\s*tool[_-]?calls?\\s*@@|<\\s*tool[_-]?calls?\\s*>|\\[\\[\\s*tool[_-]?calls?\\s*\\]\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCED = Pattern.compile("```(?:json|tool_call[s]?)\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** 从完整回答里提取工具调用；不修改原文。 */
    public static List<Call> parse(String text) {
        List<Call> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        int idx = indexOfOpen(text);
        String candidate = null;
        if (idx >= 0) {
            candidate = text.substring(idx);
        } else {
            Matcher f = FENCED.matcher(text);
            while (f.find()) {
                String body = f.group(1).trim();
                if (body.startsWith("[") || body.startsWith("{") || body.contains("\"name\"")) {
                    candidate = body;
                    break;
                }
            }
        }
        if (candidate == null) return out;

        String json = extractJsonArray(candidate);
        if (json == null) return out;
        Object parsed = null;
        try { parsed = Json.parse(json); } catch (Exception ignore) { }
        if (parsed == null) return out;

        List<Object> arr;
        if (parsed instanceof List) {
            arr = Json.parseArr(json);
        } else if (parsed instanceof Map) {
            Map<String, Object> m = Json.parseObj(json);
            Object tcs = m.get("tool_calls");
            if (tcs == null) tcs = m.get("calls");
            if (tcs instanceof List) arr = Json.parseArr(Json.write(tcs));
            else arr = new ArrayList<>();
            if (arr.isEmpty() && (m.containsKey("name") || m.containsKey("tool"))) arr.add(m);
        } else {
            arr = new ArrayList<>();
        }

        for (Object o : arr) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Call c = new Call();
            c.name = Json.str(m, "name");
            if (c.name == null) c.name = Json.str(m, "tool");
            if (c.name == null) c.name = Json.str(m, "tool_name");
            if (c.name == null) continue;
            Object args = m.containsKey("arguments") ? m.get("arguments")
                    : (m.containsKey("args") ? m.get("args") : m.get("parameters"));
            if (args instanceof String) {
                String s = ((String) args).trim();
                c.arguments = s.isEmpty() ? "{}" : (s.startsWith("{") || s.startsWith("[") ? s : "{\"value\":" + Json.write(s) + "}");
            } else if (args != null) {
                c.arguments = Json.write(args);
            } else {
                c.arguments = "{}";
            }
            c.id = "call_" + Long.toHexString(System.nanoTime()) + "_" + out.size();
            out.add(c);
        }
        return out;
    }

    /** 去掉正文里的工具标记（避免泄漏给终端用户）。 */
    public static String stripMarkup(String text) {
        if (text == null) return "";
        int i = indexOfOpen(text);
        if (i >= 0) text = text.substring(0, i);
        text = text.replaceAll("(?i)@@\\s*end\\s*@@|@@\\s*tool[_-]?calls?\\s*@@", "");
        text = text.replaceAll("(?i)<\\s*/?\\s*tool[_-]?calls?\\s*>", "");
        text = text.replaceAll("(?i)tool[_-]?calls?>", "");
        return text.trim();
    }

    private static int indexOfOpen(String s) {
        Matcher m = OPEN.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** 从含 <tool_calls> 的片段中截出 JSON 数组（括号配平，容忍多余文字）。 */
    public static String extractJsonArray(String s) {
        if (s == null) return null;
        int start = -1;
        char open = 0, close = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') { start = i; open = '['; close = ']'; break; }
            if (c == '{') { start = i; open = '{'; close = '}'; break; }
        }
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 流式闸门：把"可能要变成工具调用"的尾部先扣住，确认不是标记后再放行，
     * 保证不会把 <tool_calls> 片段先吐给客户端。
     */
    public static class StreamGate {
        private final StringBuilder hold = new StringBuilder();
        private boolean blocked = false;
        private boolean anyTool = false;

        public String feed(String delta) {
            if (delta == null || delta.isEmpty()) return "";
            hold.append(delta);
            if (blocked) return "";
            String low = hold.toString().toLowerCase(Locale.ROOT);
            int idx = indexOfOpen(low);
            if (idx < 0) idx = low.indexOf("[[tool");
            if (idx >= 0) {
                anyTool = true;
                blocked = true;
                String out = hold.substring(0, idx);
                hold.delete(0, idx);
                return out;
            }
            // 扣住可能是标记前缀的尾部
            int keep = prefixSuffixLen(hold);
            int emitLen = hold.length() - keep;
            if (emitLen > 0) {
                String out = hold.substring(0, emitLen);
                hold.delete(0, emitLen);
                return out;
            }
            return "";
        }

        public boolean sawTool() { return anyTool || blocked; }

        public String finish() {
            if (blocked) return "";
            String out = hold.toString();
            hold.setLength(0);
            return out;
        }

        public String heldText() { return hold.toString(); }

        private static final String[] MARKS = {"@@tool_calls@@", "@@end@@", "<tool_calls>", "<tool-calls>", "<toolcall", "<tool_call>", "[[tool_calls]]"};

        private static int prefixSuffixLen(StringBuilder sb) {
            int best = 0;
            int max = Math.min(16, sb.length());
            for (String m : MARKS) {
                for (int len = 1; len <= Math.min(max, m.length()); len++) {
                    if (m.regionMatches(true, 0, sb.toString(), sb.length() - len, len)) {
                        if (len > best) best = len;
                    }
                }
            }
            return best;
        }
    }

    /** 构造 OpenAI 格式的 tool_calls 数组 */
    public static List<Object> toOpenAi(List<Call> calls) {
        List<Object> out = new ArrayList<>();
        for (Call c : calls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", c.name);
            fn.put("arguments", c.arguments);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id);
            item.put("type", "function");
            item.put("function", fn);
            out.add(item);
        }
        return out;
    }
}
