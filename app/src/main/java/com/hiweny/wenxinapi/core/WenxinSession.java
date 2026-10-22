package com.hiweny.wenxinapi.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文心助手手机版（免登录）会话。
 *
 * 协议要点（2026-10 实测）：
 *  1) GET https://wenxin.baidu.com/  页面内嵌 aiTabFrameBaseData，含 token(8 hex) 与 lid(19 位数字)，
 *     同时下发 BAIDUID cookie。
 *  2) POST https://chat.baidu.com/aichat/api/conversation
 *     body.message.searchInfo.chatParams.chat_token = base64(token|md5(query)|nowMs|lid) + "-" + lid + "-3"
 *     并且 header x-chat-message 必须带同样的 query / anti_ext / modelName。
 *  3) 响应为 text/event-stream：basedata / ping / message。
 *     正文是 generator.component 以 markdown 开头的 generator.data.value（增量），
 *     思考链在 generator.data.reasoningContentArr[0]（全量），
 *     结束标志 metaData.state=generate-complete 且 endTurn=true。
 */
public class WenxinSession {

    public static final String UA_MOBILE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    public static final String CONV_URL = "https://chat.baidu.com/aichat/api/conversation";
    public static final String HOME_URL = "https://wenxin.baidu.com/";

    /** 对外模型名 -> 文心内部 modelName */
    public static String mapModel(String apiModel) {
        if (apiModel == null) return "DeepSeek-V4";
        String m = apiModel.toLowerCase(Locale.ROOT);
        if (m.contains("flash")) return "DeepSeek-V4-Flash";
        if (m.contains("v4") && m.contains("pro")) return "DeepSeek-V4";
        if (m.contains("v4")) return "DeepSeek-V4";
        if (m.contains("r1") || m.contains("reason") || m.contains("think")) return "DeepSeek-R1";
        if (m.contains("ernie") || m.contains("wenxin")) return "ERINE-5.1";
        if (m.contains("smart") || m.contains("auto")) return "smartMode";
        return "DeepSeek-V4";
    }

    public static List<String> publicModels() {
        List<String> l = new ArrayList<>();
        l.add("deepseek-v4-pro");
        l.add("deepseek-v4-flash");
        l.add("deepseek-r1");
        l.add("ernie-5.1");
        l.add("smartmode");
        return l;
    }

    // ---- state ----
    public String token = "";
    public String lid = "";
    public String baiduid = "";
    private final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
    public volatile long lastUsed = 0;
    public volatile int failCount = 0;
    public long minGapMs = 1200L;
    public volatile int totalCalls = 0;

    public interface Listener {
        void onDelta(String text);
        void onThink(String think);
        void onModel(String model);
        void onWithdraw(String reason);
    }

    public static class Result {
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder think = new StringBuilder();
        public String model = "";
        public boolean finished = false;
        public String error = null;
        public boolean withdrew = false;
        public int promptTokens = 0;
        public int completionTokens = 0;
        public int httpStatus = 0;
        public long firstTokenMs = -1;
        public long totalMs = 0;

        public String textStr() { return text.toString(); }
        public String thinkStr() { return think.toString(); }
    }

    public boolean isReady() {
        return token != null && !token.isEmpty() && lid != null && !lid.isEmpty() && !cookies.isEmpty();
    }

    // ------------------------------------------------------------------
    // 1. 预热：拿到匿名身份（cookie + token + lid）
    // ------------------------------------------------------------------
    public void warm() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(HOME_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA_MOBILE);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("warm http " + code);
        readCookies(c);
        String html = readAll(c.getInputStream());
        Matcher m = Pattern.compile("name=\"aiTabFrameBaseData\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        if (!m.find()) throw new IOException("warm: baseData not found");
        Map<String, Object> d = Json.parseObj(m.group(1));
        token = Json.str(d, "token");
        lid = Json.str(d, "lid");
        baiduid = Json.str(d, "userInfo", "baiduid");
        if (token == null || lid == null) throw new IOException("warm: empty token/lid");
    }

    private void readCookies(HttpURLConnection c) {
        Map<String, List<String>> h = c.getHeaderFields();
        if (h == null) return;
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() == null) continue;
            if (!"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String raw : e.getValue()) {
                if (raw == null) continue;
                // Android 的 HttpURLConnection 可能把多个 Set-Cookie 合并成一行，这里按 ", name=" 拆开
                String[] parts = raw.split(",(?=\\s*[A-Za-z0-9_\\-]+=)");
                for (String part : parts) {
                    if (part == null) continue;
                    String one = part.trim();
                    int eq = one.indexOf('=');
                    if (eq <= 0) continue;
                    String name = one.substring(0, eq).trim();
                    String val = one.substring(eq + 1);
                    int semi = val.indexOf(';');
                    if (semi >= 0) val = val.substring(0, semi);
                    if (name.isEmpty() || val.isEmpty()) continue;
                    cookies.put(name, val);
                    if ("BAIDUID".equals(name)) baiduid = val;
                }
            }
        }
    }

    public String cookieNames() {
        StringBuilder sb = new StringBuilder();
        for (String k : cookies.keySet()) { if (sb.length() > 0) sb.append(','); sb.append(k); }
        return sb.toString();
    }

    public String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 2. 发问
    // ------------------------------------------------------------------
    private String chatToken(String query) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(query.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            String raw = token + "|" + hex + "|" + System.currentTimeMillis() + "|" + lid;
            return Tools.b64(raw) + "-" + lid + "-3";
        } catch (Exception e) {
            return "";
        }
    }

    public Map<String, Object> buildPayload(String query, String model, boolean search) {
        String q = query;
        Map<String, Object> agentInfo = new LinkedHashMap<>();
        agentInfo.put("agent_id", listOf(""));
        agentInfo.put("params", "{\"agt_rk\":1,\"agt_sess_cnt\":1}");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("query", "");
        content.put("agentInfo", agentInfo);
        content.put("agentInfoList", new ArrayList<>());
        content.put("qtype", 0);

        Map<String, Object> chatParams = new LinkedHashMap<>();
        chatParams.put("setype", "csaitab");
        chatParams.put("chat_samples", "WISE_NEW_CSAITAB");
        chatParams.put("chat_token", chatToken(q));
        chatParams.put("scene", "");

        Map<String, Object> modelFunction = new LinkedHashMap<>();
        modelFunction.put("thinkMode", "0");
        modelFunction.put("deepSearch", "0");
        modelFunction.put("internetSearch", search ? "1" : "0");

        Map<String, Object> usedModel = new LinkedHashMap<>();
        usedModel.put("modelName", model);
        usedModel.put("modelFunction", modelFunction);

        Map<String, Object> deepDecision = new LinkedHashMap<>();
        deepDecision.put("isDeepDecision", 0);

        Map<String, Object> searchInfo = new LinkedHashMap<>();
        searchInfo.put("srcid", "");
        searchInfo.put("order", "");
        searchInfo.put("tplname", "");
        searchInfo.put("dqaKey", "");
        searchInfo.put("re_rank", "1");
        searchInfo.put("ori_lid", "");
        searchInfo.put("sa", "bkb");
        searchInfo.put("enter_type", "chat_url");
        searchInfo.put("chatParams", chatParams);
        searchInfo.put("isPrivateChat", false);
        searchInfo.put("usedModel", usedModel);
        searchInfo.put("landingPageSwitch", "");
        searchInfo.put("landingPage", "aitab");
        searchInfo.put("ecomFrom", "");
        searchInfo.put("hasLocPermission", "");
        searchInfo.put("isInnovate", 2);
        searchInfo.put("applid", "");
        searchInfo.put("a_lid", "");
        searchInfo.put("showMindMap", false);
        searchInfo.put("deepDecisionInfo", deepDecision);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("query", q);
        text.put("text_type", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        Map<String, Object> q0 = new LinkedHashMap<>();
        q0.put("type", "TEXT");
        q0.put("data", data);

        Map<String, Object> antiExt = new LinkedHashMap<>();
        antiExt.put("inputT", 1500);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("inputMethod", "chat_search");
        message.put("isRebuild", false);
        message.put("content", content);
        message.put("searchInfo", searchInfo);
        message.put("from", "");
        message.put("source", "wise_csaitab");
        message.put("query", listOf(q0));
        message.put("anti_ext", antiExt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("fromNa", true);
        body.put("rank", 1);
        return body;
    }

    private static List<Object> listOf(Object o) {
        List<Object> l = new ArrayList<>();
        l.add(o);
        return l;
    }

    public Result chat(String query, String model, boolean search, Listener listener) throws IOException {
        // 同一匿名身份保持最小请求间隔，降低触发风控的概率
        long gap = System.currentTimeMillis() - lastUsed;
        if (lastUsed > 0 && gap < minGapMs) {
            try { Thread.sleep(minGapMs - gap); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastUsed = System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        Map<String, Object> body = buildPayload(query, model, search);
        String json = Json.write(body);

        HttpURLConnection c = (HttpURLConnection) new URL(CONV_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", UA_MOBILE);
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept-Language", "zh-CN");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Origin", "https://chat.baidu.com");
        c.setRequestProperty("Referer", "https://wenxin.baidu.com/");
        c.setRequestProperty("source", "wise_csaitab");
        c.setRequestProperty("isDeepseek", "1");
        c.setRequestProperty("personifiedswitch", "0");
        c.setRequestProperty("landingpageswitch", "");
        c.setRequestProperty("x-chat-message", chatMessageHeader(query, model));
        if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookieHeader());

        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        int code = c.getResponseCode();
        r.httpStatus = code;
        if (code >= 400) {
            String err = "";
            try {
                InputStream es = c.getErrorStream();
                if (es != null) err = readAll(es);
            } catch (Exception ignore) { }
            r.error = "http " + code + (err.isEmpty() ? "" : (": " + err.substring(0, Math.min(200, err.length()))));
            r.totalMs = System.currentTimeMillis() - t0;
            return r;
        }
        readCookies(c);

        StringBuilder seen = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line;
        String ev = null;
        StringBuilder data = new StringBuilder();
        try {
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    if (ev != null) {
                        handleEvent(ev, data.toString(), r, listener, seen, t0);
                    }
                    ev = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    ev = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        } finally {
            try { br.close(); } catch (Exception ignore) { }
            c.disconnect();
        }
        r.totalMs = System.currentTimeMillis() - t0;
        if (r.text.length() == 0 && r.error == null) {
            r.error = r.withdrew ? "withdrawn" : "empty_response";
        }
        return r;
    }

    private String chatMessageHeader(String query, String model) {
        String encoded;
        try {
            encoded = Tools.urlEncode(query);
        } catch (Exception e) {
            encoded = query;
        }
        return "query:" + encoded
                + ",anti_ext:" + Tools.urlEncode("{\"inputT\":1500}")
                + ",enter_type:chat_url,re_rank:1,modelName:" + model + ",sa:bkb";
    }

    @SuppressWarnings("unchecked")
    private void handleEvent(String ev, String data, Result r, Listener l, StringBuilder seen, long t0) {
        if (data.isEmpty()) return;
        Map<String, Object> j;
        try {
            j = Json.parseObj(data);
        } catch (Exception e) {
            return;
        }
        if ("basedata".equals(ev)) return;
        if (!"message".equals(ev)) return;

        Map<String, Object> msg = Json.obj(j, "data", "message");
        Map<String, Object> meta = Json.obj(msg, "metaData");
        Map<String, Object> content = Json.obj(msg, "content");
        Map<String, Object> g = Json.obj(content, "generator");
        if (!g.isEmpty()) {
            String comp = Json.str(g, "component");
            Map<String, Object> d = Json.obj(g, "data");
            String piece = Json.str(g, "text");
            if ((piece == null || piece.isEmpty()) && comp != null && comp.startsWith("markdown")) {
                piece = Json.str(d, "value");
            }
            boolean isTextComp = (comp != null && comp.startsWith("markdown"))
                    || (Json.str(g, "text") != null && !Json.str(g, "text").isEmpty());
            if (piece != null && !piece.isEmpty() && isTextComp) {
                String showType = Json.str(g, "showType");
                if ("replace".equals(showType)) {
                    String cur = r.text.toString();
                    if (piece.length() >= cur.length()) {
                        // 正常的重绘（更完整）→ 用差量继续追加
                        if (piece.startsWith(cur)) {
                            String add = piece.substring(cur.length());
                            r.text.append(add);
                            if (l != null) l.onDelta(add);
                        } else {
                            r.text.setLength(0);
                            r.text.append(piece);
                            if (l != null) l.onDelta(piece);
                        }
                    } else {
                        // 上游试图缩短/清空已生成内容 → 判定为撤回，保留已输出内容
                        r.withdrew = true;
                        if (l != null) l.onWithdraw("shrink");
                    }
                } else {
                    r.text.append(piece);
                    if (l != null) l.onDelta(piece);
                }
                if (r.firstTokenMs < 0) r.firstTokenMs = System.currentTimeMillis() - t0;
                if (Boolean.TRUE.equals(g.get("isFinished")) && r.error == null) { /* keep */ }
            }
            // 思考链
            List<Object> rc = Json.arr(d, "reasoningContentArr");
            if (!rc.isEmpty() && rc.get(0) instanceof String) {
                String full = (String) rc.get(0);
                if (full.length() > r.think.length()) {
                    String add = full.substring(r.think.length());
                    r.think.setLength(0);
                    r.think.append(full);
                    if (l != null) l.onThink(add);
                }
            }
            Object um = d.get("usedModel");
            if (um instanceof String && !((String) um).isEmpty()) {
                if (!um.equals(r.model)) { r.model = (String) um; if (l != null) l.onModel(r.model); }
            } else if (um instanceof Map) {
                String mn = Json.str((Map<String, Object>) um, "modelName");
                if (mn != null && !mn.isEmpty() && !mn.equals(r.model)) { r.model = mn; if (l != null) l.onModel(mn); }
            }
            Object mi = g.get("modelInfo");
            if (mi instanceof Map) {
                String mt = Json.str((Map<String, Object>) mi, "model_type");
                if ((r.model == null || r.model.isEmpty()) && mt != null) { r.model = mt; if (l != null) l.onModel(mt); }
            }
            Map<String, Object> hints = Json.obj(content, "hints");
            for (Object p : Json.arr(hints, "parts")) {
                if (!(p instanceof Map)) continue;
                Map<String, Object> pm = (Map<String, Object>) p;
                String t = Json.str(pm, "text");
                String type = Json.str(pm, "type");
                if (t != null && (t.contains("抱歉") || t.contains("稍后重试") || "tokenFail".equals(type))) {
                    r.error = t;
                }
            }
            Object antiFlag = g.get("antiFlag");
            if (antiFlag instanceof Number && ((Number) antiFlag).intValue() == 1) {
                r.withdrew = true;
                if (l != null) l.onWithdraw("antiFlag");
            }
        }
        Map<String, Object> speed = Json.obj(meta, "speedInfo");
        Map<String, Object> costs = Json.obj(speed, "costs");
        if (costs.get("promptTokenLabel") instanceof Number) {
            r.promptTokens = ((Number) costs.get("promptTokenLabel")).intValue();
        }
        if (costs.get("completionTokenLabel") instanceof Number) {
            r.completionTokens = ((Number) costs.get("completionTokenLabel")).intValue();
        }
        Map<String, Object> labels = Json.obj(speed, "labels");
        String mn = Json.str(labels, "modelName");
        if (mn != null && !mn.isEmpty()) r.model = mn;
        if ("generate-complete".equals(Json.str(meta, "state")) && Boolean.TRUE.equals(meta.get("endTurn"))) {
            r.finished = true;
        }
    }

    static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        return sb.toString();
    }
}
