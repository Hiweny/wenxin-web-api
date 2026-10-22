package com.hiweny.wenxinapi.core;

import java.io.IOException;
import java.io.InputStream;
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
    public int readTimeoutMs = 180000;
    public volatile int totalCalls = 0;
    /** 预热阶段预先建立到 chat.baidu.com 的 TLS 连接，发问时直接复用（省一次握手） */
    private java.net.Socket chatSocket;
    private long chatSocketAt = 0;

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
        public String withdrawDetail = "";
        public StringBuilder rawHead = new StringBuilder();
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
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA_MOBILE);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        h.put("Accept-Encoding", "identity");
        h.put("sec-fetch-dest", "document");
        h.put("sec-fetch-mode", "navigate");
        h.put("sec-fetch-site", "none");
        h.put("upgrade-insecure-requests", "1");
        // 不允许任何中间层缓存首页：被缓存时会拿到别人的（或上一次的）token/lid，
        // 上游就会把我们的多次请求当成同一个页面会话，表现为"永远回同一句话"。
        h.put("Cache-Control", "no-cache, no-store, max-age=0");
        h.put("Pragma", "no-cache");

        String url = HOME_URL + "?_r=" + Long.toHexString(System.nanoTime());
        Http1Client.Response resp = null;
        IOException lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                doWarm(h, url);
                return;
            } catch (IOException e) {
                lastErr = e;
                try { Thread.sleep(700L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw lastErr == null ? new IOException("warm failed") : lastErr;
    }

    private void doWarm(Map<String, String> h, String startUrl) throws IOException {
        String url = startUrl;
        Http1Client.Response resp = null;
        for (int i = 0; i < 4; i++) {
            resp = Http1Client.request(url, "GET", h, null, 15000, 25000);
            storeCookies(resp.setCookies);
            if (resp.status >= 300 && resp.status < 400 && resp.header("Location") != null) {
                String loc = resp.header("Location");
                resp.close();
                if (loc.startsWith("http")) {
                    url = loc;
                } else {
                    URL base = new URL(url);
                    url = base.getProtocol() + "://" + base.getHost() + loc;
                }
                continue;
            }
            break;
        }
        if (resp == null) throw new IOException("warm: no response");
        if (resp.status >= 400) {
            resp.close();
            throw new IOException("warm http " + resp.status);
        }
        String html = resp.readAll();
        resp.close();
        Matcher m = Pattern.compile("name=\"aiTabFrameBaseData\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        if (!m.find()) throw new IOException("warm: baseData not found (html " + html.length() + "B)");
        Map<String, Object> d = Json.parseObj(m.group(1));
        token = Json.str(d, "token");
        lid = Json.str(d, "lid");
        baiduid = Json.str(d, "userInfo", "baiduid");
        if (token == null || lid == null) throw new IOException("warm: empty token/lid");
        preconnect();
    }

    /**
     * 预先和 chat.baidu.com 建好 TLS 连接并保持 keep-alive。
     * 真正发问时直接复用这条连接，省掉 TCP + TLS 握手（手机网络下通常 200~600ms）。
     * 失败不影响功能：chat() 会自动回退到新建连接。
     */
    private void preconnect() {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA_MOBILE);
            h.put("Accept", "*/*");
            h.put("Accept-Encoding", "identity");
            Http1Client.Response r = Http1Client.request("https://chat.baidu.com/robots.txt", "GET", h,
                    null, 8000, 8000, true, null);
            try { r.readAll(); } catch (Exception ignore) { }
            chatSocket = r.detachSocket();
            chatSocketAt = System.currentTimeMillis();
            r.close();
        } catch (Exception ignore) {
            chatSocket = null;
        }
    }

    private void storeCookies(java.util.List<String> raws) {
        if (raws == null) return;
        for (String raw : raws) {
            if (raw == null) continue;
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
        return buildPayload(query, model, search, false);
    }

    public Map<String, Object> buildPayload(String query, String model, boolean search, boolean think) {
        return buildPayload(query, model, search, think, null);
    }

    public Map<String, Object> buildPayload(String query, String model, boolean search, boolean think, java.util.List<String> images) {
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
        modelFunction.put("thinkMode", think ? "1" : "0");
        modelFunction.put("deepThink", think ? "1" : "0");
        modelFunction.put("internetSearch", search ? "1" : "0");
        modelFunction.put("deepSearch", search ? "1" : "0");

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

        List<Object> queryItems = new ArrayList<>();
        // 图片先于文字下发：{type:IMAGE, data:{image:{image_url, upload_flag:1}}}
        if (images != null) {
            int n = 0;
            for (String url : images) {
                if (url == null || url.isEmpty()) continue;
                if (n++ >= 4) break;
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("image_url", url);
                image.put("upload_flag", 1);
                Map<String, Object> imageData = new LinkedHashMap<>();
                imageData.put("image", image);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "IMAGE");
                item.put("data", imageData);
                queryItems.add(item);
            }
        }
        Map<String, Object> q0 = new LinkedHashMap<>();
        q0.put("type", "TEXT");
        q0.put("data", data);
        queryItems.add(q0);

        Map<String, Object> antiExt = new LinkedHashMap<>();
        antiExt.put("inputT", 1500);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("inputMethod", "chat_search");
        message.put("isRebuild", false);
        message.put("content", content);
        message.put("searchInfo", searchInfo);
        message.put("from", "");
        message.put("source", "wise_csaitab");
        message.put("query", queryItems);
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
        return chat(query, model, search, false, listener);
    }

    public Result chat(String query, String model, boolean search, boolean think, Listener listener) throws IOException {
        return chat(query, model, search, think, null, listener);
    }

    public Result chat(String query, String model, boolean search, boolean think, java.util.List<String> images, Listener listener) throws IOException {
        // 同一匿名身份保持最小请求间隔，降低触发风控的概率
        long gap = System.currentTimeMillis() - lastUsed;
        if (lastUsed > 0 && gap < minGapMs) {
            try { Thread.sleep(minGapMs - gap); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastUsed = System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        Map<String, Object> body = buildPayload(query, model, search, think, images);
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA_MOBILE);
        h.put("Accept", "text/event-stream");
        h.put("Content-Type", "application/json");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        h.put("Accept-Encoding", "identity");
        h.put("Origin", "https://chat.baidu.com");
        h.put("Referer", "https://wenxin.baidu.com/");
        h.put("source", "wise_csaitab");
        h.put("isDeepseek", "1");
        h.put("personifiedswitch", "0");
        h.put("landingpageswitch", "");
        h.put("sec-fetch-dest", "empty");
        h.put("sec-fetch-mode", "cors");
        h.put("sec-fetch-site", "same-origin");
        h.put("x-chat-message", chatMessageHeader(query, model));
        if (!cookies.isEmpty()) h.put("Cookie", cookieHeader());

        Http1Client.Response resp;
        java.net.Socket reuse = chatSocket;
        // 预连接放置超过 25 秒就丢弃（服务器空闲超时关掉后复用会白等一次超时）
        if (reuse != null && System.currentTimeMillis() - chatSocketAt > 8000) {
            try { reuse.close(); } catch (Exception ignore) { }
            reuse = null;
        }
        chatSocket = null;   // 一次性使用；SSE 是长连接，结束后不再复用
        try {
            resp = Http1Client.request(CONV_URL, "POST", h, payload, 15000, readTimeoutMs, false, reuse);
        } catch (IOException e) {
            r.error = "net: " + e.getMessage();
            r.totalMs = System.currentTimeMillis() - t0;
            return r;
        }
        r.httpStatus = resp.status;
        storeCookies(resp.setCookies);
        if (resp.status >= 400) {
            String err = "";
            try { err = Tools.truncate(resp.readAll(), 200); } catch (Exception ignore) { }
            resp.close();
            r.error = "http " + resp.status + (err.isEmpty() ? "" : (": " + err));
            r.totalMs = System.currentTimeMillis() - t0;
            return r;
        }

        String line;
        String ev = null;
        StringBuilder data = new StringBuilder();
        try {
            while ((line = resp.readLine()) != null) {
                if (r.rawHead.length() < 1500) r.rawHead.append(line).append('\n');
                if (line.isEmpty()) {
                    if (ev != null) handleEvent(ev, data.toString(), r, listener, new StringBuilder(), t0);
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
        } catch (IOException e) {
            if (r.error == null && r.text.length() == 0) r.error = "stream: " + e.getMessage();
        } finally {
            resp.close();
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
                        // 上游试图缩短/清空已生成内容 → 判定为撤回：保留已输出的完整原文（防撤回核心）
                        r.withdrew = true;
                        r.withdrawDetail = "shrink " + cur.length() + "->" + piece.length();
                        if (l != null) l.onWithdraw(r.withdrawDetail);
                    }
                } else {
                    r.text.append(piece);
                    if (l != null) l.onDelta(piece);
                }
                if (r.firstTokenMs < 0) r.firstTokenMs = System.currentTimeMillis() - t0;
                if (Boolean.TRUE.equals(g.get("isFinished")) && r.error == null) { /* keep */ }
            }
            // 思考链（thinkingSteps 的 reasoningContentArr[0] 是增量片段；个别版本是全量，两种都兼容）
            List<Object> rc = Json.arr(d, "reasoningContentArr");
            if (!rc.isEmpty() && rc.get(0) instanceof String && !((String) rc.get(0)).isEmpty()) {
                String tpiece = (String) rc.get(0);
                String cur = r.think.toString();
                if (!cur.isEmpty() && cur.startsWith(tpiece)) {
                    // 上游重发了已经出现过的片段 → 忽略，避免思考链重复
                } else if (tpiece.length() > cur.length() && tpiece.startsWith(cur)) {
                    String add = tpiece.substring(cur.length());
                    r.think.setLength(0);
                    r.think.append(tpiece);
                    if (l != null) l.onThink(add);
                } else {
                    r.think.append(tpiece);
                    if (l != null) l.onThink(tpiece);
                }
            }
            String rc2 = Json.str(d, "reasoningContent");
            if ((rc.isEmpty()) && rc2 != null && !rc2.isEmpty() && !r.think.toString().endsWith(rc2)) {
                r.think.append(rc2);
                if (l != null) l.onThink(rc2);
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
