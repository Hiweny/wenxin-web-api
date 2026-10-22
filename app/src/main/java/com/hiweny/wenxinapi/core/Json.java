package com.hiweny.wenxinapi.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化（不依赖 org.json，便于在纯 JVM 上跑单元测试）。
 */
public final class Json {

    public static Object parse(String s) {
        if (s == null) return null;
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObj(String s) {
        Object o = parse(s);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArr(String s) {
        Object o = parse(s);
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') i++;
                else break;
            }
        }

        Object value() {
            skipWs();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; } return null;
                case 'f': if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; } return null;
                case 'n': if (s.startsWith("null", i)) { i += 4; return null; } return null;
                default: return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') break;
                String k = string();
                skipWs();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                    i += 4;
                                } catch (NumberFormatException ex) { /* ignore */ }
                            }
                            break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object number() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            String t = s.substring(st, i);
            if (t.isEmpty()) { i++; return null; }
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException e) {
                        return Double.parseDouble(t);
                    }
                }
                return Double.parseDouble(t);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---------------- serialize ----------------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(((Number) o).longValue());
            else sb.append(o.toString());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------- helpers ----------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return new LinkedHashMap<>();
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur instanceof Map ? (Map<String, Object>) cur : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return new ArrayList<>();
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur instanceof List ? (List<Object>) cur : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur == null ? null : String.valueOf(cur);
    }

    public static Object get(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }
}
