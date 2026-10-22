package com.hiweny.wenxinapi.server;

import com.hiweny.wenxinapi.core.Config;
import com.hiweny.wenxinapi.core.HistoryStore;
import com.hiweny.wenxinapi.core.Json;
import com.hiweny.wenxinapi.core.PromptBuilder;
import com.hiweny.wenxinapi.core.SessionPool;
import com.hiweny.wenxinapi.core.ToolBridge;
import com.hiweny.wenxinapi.core.Tools;
import com.hiweny.wenxinapi.core.WenxinSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenAI 兼容路由层 */
public class ApiRouter implements HttpServer.Handler {

    public interface Logger {
        void log(String line);
    }

    private final Config cfg;
    private final SessionPool pool;
    private final HistoryStore store;
    private final Logger logger;
    private final long startedAt = System.currentTimeMillis();
    private final String banner;

    public ApiRouter(Config cfg, SessionPool pool, HistoryStore store, Logger logger, String banner) {
        this.cfg = cfg;
        this.pool = pool;
        this.store = store;
        this.logger = logger;
        this.banner = banner;
    }

    public Config config() { return cfg; }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws Exception {
        String p = req.path;
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);

        if ("/".equals(p) || "/index.html".equals(p)) { res.text(200, "text/html; charset=utf-8", banner); return; }
        if ("/healthz".equals(p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("uptime_ms", System.currentTimeMillis() - startedAt);
            m.put("pool", pool.size());
            m.put("models", WenxinSession.publicModels());
            res.json(200, Json.write(m));
            return;
        }
        if ("/v1/models".equals(p) || "/models".equals(p)) { res.json(200, modelsJson()); return; }
        if ("/v1/chat/completions".equals(p) || "/chat/completions".equals(p)) { chat(req, res); return; }
        if ("/v1/history".equals(p) || "/history".equals(p)) { history(req, res); return; }
        if ("/debug/probe".equals(p)) { debugProbe(res); return; }
        if ("/v1/pool".equals(p)) {
            res.json(200, Json.write(listToJson(pool.snapshot())));
            return;
        }
        res.json(404, "{\"error\":{\"message\":\"not found\",\"type\":\"invalid_request_error\"}}");
    }

    /** 诊断：一次完整的上游调用，返回详细信息，便于定位客户端侧差异 */
    private void debugProbe(HttpServer.Response res) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        WenxinSession s = new WenxinSession();
        long t0 = System.currentTimeMillis();
        try {
            s.warm();
            out.put("warm_ms", System.currentTimeMillis() - t0);
            out.put("token", s.token);
            out.put("lid", s.lid);
            out.put("cookies", s.cookieNames());
            out.put("cookie_len", s.cookieHeader().length());
            WenxinSession.Result r = s.chat("只回复：PROBE", "DeepSeek-V4-Flash", false, null);
            out.put("http_status", r.httpStatus);
            out.put("error", r.error);
            out.put("model", r.model);
            out.put("text", r.textStr());
            out.put("tokens", r.promptTokens + "/" + r.completionTokens);
            out.put("raw_head", Tools.truncate(r.rawHead.toString(), 1100));
        } catch (Exception e) {
            out.put("exception", String.valueOf(e));
        }
        res.json(200, Json.write(out));
    }

    private List<Object> listToJson(List<String> l) {
        List<Object> out = new ArrayList<>();
        out.addAll(l);
        return out;
    }

    private String modelsJson() {
        long created = 1750000000L;
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        boolean first = true;
        for (String m : WenxinSession.publicModels()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":");
            Json.quote(sb, m);
            sb.append(",\"object\":\"model\",\"created\":").append(created)
                    .append(",\"owned_by\":\"baidu-wenxin-anon\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void history(HttpServer.Request req, HttpServer.Response res) throws Exception {
        boolean full = "1".equals(req.query.get("full"));
        int limit = 50;
        try { if (req.query.containsKey("limit")) limit = Integer.parseInt(req.query.get("limit")); } catch (Exception ignore) { }
        String id = req.query.get("id");
        if (id != null) {
            HistoryStore.Entry e = store.get(id);
            if (e == null) { res.json(404, "{\"error\":{\"message\":\"not found\"}}"); return; }
            res.json(200, e.toJson());
            return;
        }
        res.json(200, "{\"data\":" + store.listJson(limit, full) + "}");
    }

    private boolean auth(HttpServer.Request req) {
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty() || cfg.allowNoKey) return true;
        String h = req.header("Authorization");
        String key = null;
        if (h != null && h.toLowerCase(Locale.ROOT).startsWith("bearer ")) key = h.substring(7).trim();
        if (key == null) key = req.header("x-api-key");
        return cfg.apiKey.equals(key);
    }

    private void chat(HttpServer.Request req, HttpServer.Response res) throws Exception {
        if (!auth(req)) {
            res.json(401, "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");
            return;
        }
        Map<String, Object> body;
        try {
            body = Json.parseObj(req.bodyText());
        } catch (Exception e) {
            res.json(400, "{\"error\":{\"message\":\"invalid json\"}}");
            return;
        }
        List<Object> messages = Json.arr(body, "messages");
        Object tools = body.get("tools");
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        String model = Json.str(body, "model");
        if (model == null || model.isEmpty()) model = cfg.defaultModel;
        boolean search = cfg.searchDefault;
        Map<String, Object> extra = Json.obj(body, "extra_body");
        if (extra.get("search") instanceof Boolean) search = (Boolean) extra.get("search");
        if (body.get("search") instanceof Boolean) search = (Boolean) body.get("search");

        PromptBuilder.Built built = PromptBuilder.build(messages, tools);
        String internalModel = WenxinSession.mapModel(model);
        String completionId = "chatcmpl-" + Long.toHexString(System.nanoTime());
        long created = System.currentTimeMillis() / 1000;

        logger.log("[chat] model=" + model + " -> " + internalModel + " stream=" + stream
                + " tools=" + built.toolsEnabled + " prompt=" + built.prompt.length() + "chars");

        if (stream) {
            streamChat(res, built, internalModel, model, completionId, created, search);
        } else {
            blockChat(res, built, internalModel, model, completionId, created, search);
        }
    }

    private WenxinSession.Result runOnce(String prompt, String internalModel, boolean search, WenxinSession.Listener l) throws Exception {
        WenxinSession.Result last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            SessionPool.Lease lease = pool.acquire(30000);
            try {
                lease.session.totalCalls++;
                WenxinSession.Result r = lease.session.chat(prompt, internalModel, search, l);
                boolean bad = r.error != null || (r.text.length() == 0 && !r.withdrew);
                if (bad) {
                    lease.session.failCount++;
                    lease.invalidate();
                    logger.log("[chat] attempt " + (attempt + 1) + " failed: " + r.error);
                    last = r;
                    if (l instanceof RetryAware && ((RetryAware) l).hasSent()) return r; // 已输出不能重试
                    try { Thread.sleep(900); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                lease.session.failCount = 0;
                return r;
            } finally {
                lease.close();
            }
        }
        return last;
    }

    public interface RetryAware { boolean hasSent(); }

    /** 客户端提前断开 */
    static class ClientGone extends RuntimeException {
        ClientGone() { super("client gone"); }
    }

    private void blockChat(HttpServer.Response res, PromptBuilder.Built built, String internalModel,
                           String model, String id, long created, boolean search) throws Exception {
        WenxinSession.Result r = runOnce(built.prompt, internalModel, search, null);
        if (r == null) { res.json(502, "{\"error\":{\"message\":\"upstream unavailable\"}}"); return; }

        List<ToolBridge.Call> calls = built.toolsEnabled ? ToolBridge.parse(r.textStr()) : new ArrayList<ToolBridge.Call>();
        String text = built.toolsEnabled ? ToolBridge.stripMarkup(r.textStr()) : r.textStr();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":");
        Json.quote(sb, id);
        sb.append(",\"object\":\"chat.completion\",\"created\":").append(created).append(",\"model\":");
        Json.quote(sb, model);
        sb.append(",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",");
        if (!calls.isEmpty()) {
            sb.append("\"content\":");
            Json.quote(sb, text);
            sb.append(",\"tool_calls\":").append(Json.write(ToolBridge.toOpenAi(calls)));
        } else {
            sb.append("\"content\":");
            Json.quote(sb, text);
        }
        sb.append("},\"finish_reason\":").append(calls.isEmpty() ? "\"stop\"" : "\"tool_calls\"").append("}],");
        sb.append("\"usage\":{\"prompt_tokens\":").append(r.promptTokens)
                .append(",\"completion_tokens\":").append(r.completionTokens)
                .append(",\"total_tokens\":").append(r.promptTokens + r.completionTokens).append("}}");

        saveHistory(id, model, built.prompt, r);
        res.json(200, sb.toString());
    }

    private void streamChat(HttpServer.Response res, PromptBuilder.Built built, String internalModel,
                            String model, String id, long created, boolean search) throws Exception {
        res.status = 200;
        res.headers.put("Content-Type", "text/event-stream; charset=utf-8");
        res.headers.put("Cache-Control", "no-cache");
        res.headers.put("X-Accel-Buffering", "no");
        res.chunked = true;
        res.sendHeaders();

        final ToolBridge.StreamGate gate = new ToolBridge.StreamGate();
        final boolean[] sent = {false};

        sendChunk(res, id, model, created, "{\"role\":\"assistant\"}", null, null);
        sent[0] = true;

        WenxinSession.Listener listener = new WenxinSession.Listener() {
            @Override public void onDelta(String text) {
                try {
                    String emit = gate.feed(text);
                    if (!emit.isEmpty()) sendChunk(res, id, model, created, contentDelta(emit), null, null);
                } catch (Exception e) {
                    // 客户端已断开：抛出让上游流尽快结束，避免白烧配额
                    throw new ClientGone();
                }
            }
            @Override public void onThink(String think) {
                try {
                    sendChunk(res, id, model, created, "{\"reasoning_content\":" + quote(think) + "}", null, null);
                } catch (Exception e) {
                    throw new ClientGone();
                }
            }
            @Override public void onModel(String m) { }
            @Override public void onWithdraw(String reason) {
                try {
                    sendChunk(res, id, model, created, "{}", null, null);
                } catch (Exception ignore) { }
                logger.log("[chat] upstream withdraw detected: " + reason);
            }
        };

        WenxinSession.Result r;
        try {
            r = runOnce(built.prompt, internalModel, search, listener);
        } catch (ClientGone cg) {
            logger.log("[chat] client disconnected, upstream released");
            try { res.end(); } catch (Exception ignore) { }
            return;
        } catch (Exception e) {
            logger.log("[chat] stream error: " + e);
            sendChunk(res, id, model, created, "{}", "stop", null);
            res.write("data: [DONE]\n\n");
            res.end();
            return;
        }
        if (r == null) {
            sendChunk(res, id, model, created, "{}", "stop", null);
            res.write("data: [DONE]\n\n");
            res.end();
            return;
        }

        String tail = gate.finish();
        if (!tail.isEmpty()) sendChunk(res, id, model, created, contentDelta(tail), null, null);

        List<ToolBridge.Call> calls = built.toolsEnabled ? ToolBridge.parse(r.textStr()) : new ArrayList<ToolBridge.Call>();
        String finish = "stop";
        if (!calls.isEmpty()) {
            List<Object> oa = ToolBridge.toOpenAi(calls);
            StringBuilder d = new StringBuilder("{\"tool_calls\":[");
            for (int i = 0; i < oa.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) oa.get(i);
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) c.get("function");
                if (i > 0) d.append(',');
                d.append("{\"index\":").append(i).append(",\"id\":");
                Json.quote(d, String.valueOf(c.get("id")));
                d.append(",\"type\":\"function\",\"function\":{\"name\":");
                Json.quote(d, String.valueOf(fn.get("name")));
                d.append(",\"arguments\":");
                Json.quote(d, String.valueOf(fn.get("arguments")));
                d.append("}}");
            }
            d.append("]}");
            sendChunk(res, id, model, created, d.toString(), null, null);
            finish = "tool_calls";
        }
        sendChunk(res, id, model, created, "{}", finish,
                "{\"prompt_tokens\":" + r.promptTokens + ",\"completion_tokens\":" + r.completionTokens
                        + ",\"total_tokens\":" + (r.promptTokens + r.completionTokens) + "}");
        res.write("data: [DONE]\n\n");
        res.end();
        saveHistory(id, model, built.prompt, r);
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        Json.quote(sb, s == null ? "" : s);
        return sb.toString();
    }

    private static String contentDelta(String text) {
        return "{\"content\":" + quote(text) + "}";
    }

    private void sendChunk(HttpServer.Response res, String id, String model, long created,
                           String deltaJson, String finishReason, String usageJson) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("data: {\"id\":");
        Json.quote(sb, id);
        sb.append(",\"object\":\"chat.completion.chunk\",\"created\":").append(created).append(",\"model\":");
        Json.quote(sb, model);
        sb.append(",\"choices\":[{\"index\":0,\"delta\":").append(deltaJson)
                .append(",\"finish_reason\":").append(finishReason == null ? "null" : ("\"" + finishReason + "\"")).append("}]");
        if (usageJson != null) sb.append(",\"usage\":").append(usageJson);
        sb.append("}\n\n");
        res.write(sb.toString());
    }

    private void saveHistory(String id, String model, String prompt, WenxinSession.Result r) {
        if (!cfg.saveHistory) return;
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.id = id;
        e.time = Tools.nowIso();
        e.model = model;
        e.prompt = Tools.truncate(prompt, 4000);
        e.answer = r.textStr();
        e.think = Tools.truncate(r.thinkStr(), 8000);
        e.flags = (r.error != null ? ("error:" + r.error) : "")
                + (r.withdrew ? " withdraw" : "")
                + (r.finished ? "" : " unfinished");
        e.ms = r.totalMs;
        e.promptTokens = r.promptTokens;
        e.completionTokens = r.completionTokens;
        store.add(e);
    }
}
