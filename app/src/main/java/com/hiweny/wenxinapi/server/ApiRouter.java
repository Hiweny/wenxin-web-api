package com.hiweny.wenxinapi.server;

import com.hiweny.wenxinapi.core.ApiActivity;
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
    private volatile String lastPrompt = "";
    private volatile String lastRawMessages = "[]";
    private volatile boolean lastTools = false;
    private volatile String lastAt = "";
    private volatile String lastAnswerHead = "";

    /** 重复回答守卫：记录上一次「问题指纹 -> 回答开头」，用于识别上游把新问题当成旧会话续写 */
    private String dupPromptKey = "";
    private String dupAnswerHead = "";
    private final Object dupLock = new Object();
    private static final class RetrySignal extends RuntimeException {
        RetrySignal() { super("retry"); }
    }

    public ApiRouter(Config cfg, SessionPool pool, HistoryStore store, Logger logger, String banner) {
        this.cfg = cfg;
        this.pool = pool;
        this.store = store;
        this.logger = logger;
        this.banner = banner;
    }

    public Config config() { return cfg; }

    /** 把调用阶段广播出去（前台通知 / UI 用） */
    private void publishState(String phase, String model, int chars, String note) {
        try {
            ApiActivity.State st = new ApiActivity.State();
            st.phase = phase;
            st.model = model;
            st.chars = chars;
            st.note = note == null ? "" : note;
            st.lastMs = lastCallMs;
            ApiActivity.publish(st);
        } catch (Throwable ignore) { }
    }

    private volatile long lastCallMs = 0;


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
        if ("/debug/last".equals(p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", lastAt);
            m.put("prompt_len", lastPrompt.length());
            m.put("prompt", lastPrompt);
            m.put("tools", lastTools);
            m.put("raw_messages", lastRawMessages);
            m.put("answer_head", lastAnswerHead);
            res.json(200, Json.write(m));
            return;
        }
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
        Map<String, Object> extra = Json.obj(body, "extra_body");

        // 联网搜索：默认关闭（cfg.searchDefault），可用 body.search / extra_body.search 开启
        boolean search = cfg.searchDefault;
        if (body.get("search") instanceof Boolean) search = (Boolean) body.get("search");
        if (extra.get("search") instanceof Boolean) search = (Boolean) extra.get("search");

        // 深度思考：默认开启（cfg.thinkDefault），可用 thinking:false / reasoning_effort:"none" 关闭
        boolean think = cfg.thinkDefault;
        if (body.get("thinking") instanceof Boolean) think = (Boolean) body.get("thinking");
        if (extra.get("thinking") instanceof Boolean) think = (Boolean) extra.get("thinking");
        Object effort = body.containsKey("reasoning_effort") ? body.get("reasoning_effort") : extra.get("reasoning_effort");
        if (effort instanceof String) {
            String e = ((String) effort).toLowerCase(Locale.ROOT);
            if (e.equals("none") || e.equals("off") || e.equals("minimal")) think = false;
        }

        PromptBuilder.Built built = PromptBuilder.build(messages, tools);
        if (built.prompt == null || built.prompt.trim().isEmpty()) {
            res.json(400, "{\"error\":{\"message\":\"empty prompt: messages 中没有可用的 user 内容\",\"type\":\"invalid_request_error\"}}");
            return;
        }
        String internalModel = WenxinSession.mapModel(model);
        String completionId = "chatcmpl-" + Long.toHexString(System.nanoTime());
        long created = System.currentTimeMillis() / 1000;

        if (!built.images.isEmpty()) {
            StringBuilder ib = new StringBuilder();
            for (String u : built.images) ib.append(u.startsWith("data:") ? ("data:…(len=" + u.length() + ")") : u).append(' ');
            logger.log("[chat] images=" + built.images.size() + " [" + Tools.truncate(ib.toString(), 120) + "]");
        }
        lastPrompt = Tools.truncate(built.prompt.replace("\n", " ⏎ "), 300);
        lastRawMessages = Json.write(messages);
        lastTools = built.toolsEnabled;
        lastAt = Tools.nowIso();
        logger.log("[chat] model=" + model + " -> " + internalModel + " stream=" + stream + " think=" + think
                + " search=" + search + " tools=" + built.toolsEnabled + " msg=" + messages.size()
                + " prompt=" + built.prompt.length() + "chars «"
                + Tools.truncate(built.prompt.replace("\n", " "), 40) + "»");

        publishState(ApiActivity.PHASE_THINKING, model, 0, "");
        long callStart = System.currentTimeMillis();
        try {
            if (stream) {
                streamChat(res, built, internalModel, model, completionId, created, search, think);
            } else {
                blockChat(res, built, internalModel, model, completionId, created, search, think);
            }
        } finally {
            lastCallMs = System.currentTimeMillis() - callStart;
        }
    }

    /** 去掉空白取前 n 个字符，用于判定「两个回答是否实质相同」 */
    private static String headOf(String s, int n) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private boolean looksDuplicate(String prompt, String answer) {
        if (answer == null || answer.trim().length() < 24) return false;
        String hd = headOf(answer, 32);
        if (hd.length() < 24) return false;
        synchronized (dupLock) {
            if (dupAnswerHead.isEmpty()) return false;
            // 同一个问题重复问、答案一样，属正常
            if (prompt.equals(dupPromptKey)) return false;
            return hd.equals(dupAnswerHead);
        }
    }

    private void remember(String prompt, String answer) {
        if (answer == null || answer.trim().length() < 24) return;
        synchronized (dupLock) {
            dupPromptKey = prompt;
            dupAnswerHead = headOf(answer, 32);
            lastAnswerHead = headOf(answer, 160);
        }
    }

    private WenxinSession.Result runOnce(String prompt, String internalModel, boolean search, boolean think,
                                        java.util.List<String> images, WenxinSession.Listener l) throws Exception {
        WenxinSession.Result last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            SessionPool.Lease lease = pool.acquire(30000);
            try {
                if (attempt > 0 && l instanceof RetryAware) ((RetryAware) l).reset();
                lease.session.totalCalls++;
                WenxinSession.Result r = lease.session.chat(prompt, internalModel, search, think, images, l);
                boolean bad = r.error != null || (r.text.length() == 0 && !r.withdrew);
                if (!bad && looksDuplicate(prompt, r.textStr())) {
                    boolean sent = (l instanceof RetryAware) && ((RetryAware) l).hasSent();
                    if (!sent) {
                        bad = true;
                        r.error = "duplicate_answer";
                        logger.log("[guard] 问题已变化但回答与上一轮完全相同 → 判定上游会话串扰，换新身份重试");
                    }
                }
                if (bad) {
                    lease.session.failCount++;
                    logger.log("[chat] attempt " + (attempt + 1) + " failed: " + r.error);
                    last = r;
                    boolean sent = (l instanceof RetryAware) && ((RetryAware) l).hasSent();
                    if (sent && !"duplicate_answer".equals(r.error)) return r; // 已经流出去的内容无法重来
                    try { Thread.sleep(260); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                lease.session.failCount = 0;
                remember(prompt, r.textStr());
                return r;
            } catch (RetrySignal rs) {
                logger.log("[guard] 首段内容与上一轮完全相同 → 换新身份重试");
                last = null;
                continue;
            } finally {
                lease.close();
            }
        }
        return last;
    }

    public interface RetryAware { boolean hasSent(); void reset(); }

    /** 客户端提前断开 */
    static class ClientGone extends RuntimeException {
        ClientGone() { super("client gone"); }
    }

    private void blockChat(HttpServer.Response res, PromptBuilder.Built built, String internalModel,
                           String model, String id, long created, boolean search, boolean think) throws Exception {
        WenxinSession.Result r = runOnce(built.prompt, internalModel, search, think, built.images, null);
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
        if (r.think.length() > 0) {
            sb.append(",\"reasoning_content\":");
            Json.quote(sb, r.thinkStr());
        }
        sb.append("},\"finish_reason\":").append(calls.isEmpty() ? "\"stop\"" : "\"tool_calls\"").append("}],");
        sb.append("\"usage\":{\"prompt_tokens\":").append(r.promptTokens)
                .append(",\"completion_tokens\":").append(r.completionTokens)
                .append(",\"total_tokens\":").append(r.promptTokens + r.completionTokens).append("}}");

        saveHistory(id, model, built.prompt, r);
        lastCallMs = r.totalMs;
        publishState(r.error != null ? ApiActivity.PHASE_ERROR : ApiActivity.PHASE_DONE,
                model, r.text.length(), r.error == null ? "" : Tools.truncate(r.error, 40));
        res.json(200, sb.toString());
    }

    private void streamChat(HttpServer.Response res, PromptBuilder.Built built, String internalModel,
                            String model, String id, long created, boolean search, boolean think) throws Exception {
        res.status = 200;
        res.headers.put("Content-Type", "text/event-stream; charset=utf-8");
        res.headers.put("Cache-Control", "no-store");
        res.headers.put("Cache-Control", "no-cache");
        res.headers.put("X-Accel-Buffering", "no");
        res.chunked = true;
        res.sendHeaders();

        final ToolBridge.StreamGate[] gateRef = {new ToolBridge.StreamGate()};
        final boolean[] sent = {false};
        final boolean[] started = {false};
        final StringBuilder pend = new StringBuilder();
        final StringBuilder pendThink = new StringBuilder();

        sendChunk(res, id, model, created, "{\"role\":\"assistant\"}", null, null);
        sent[0] = true;

        // 首段缓冲：攒够一小段再决定是否发送，这样「上游串扰（新问题返回旧答案）」可以在
        // 污染客户端之前被识别并换身份重试，同时不牺牲可感知的首字速度（阈值 60 字，通常 <0.4s）。
        final Runnable flushPending = new Runnable() {
            @Override public void run() {
                if (!started[0]) started[0] = true; else return;
                publishState(ApiActivity.PHASE_STREAMING, model, pend.length(), "");
                try {
                    if (pendThink.length() > 0) {
                        sendChunk(res, id, model, created, "{\"reasoning_content\":" + quote(pendThink.toString()) + "}", null, null);
                        pendThink.setLength(0);
                    }
                    if (pend.length() > 0) {
                        String e = gateRef[0].feed(pend.toString());
                        pend.setLength(0);
                        if (!e.isEmpty()) sendChunk(res, id, model, created, contentDelta(e), null, null);
                    }
                } catch (Exception ex) {
                    throw new ClientGone();
                }
            }
        };

        final class StreamListener implements WenxinSession.Listener, RetryAware {
            @Override public void onDelta(String text) {
                try {
                    if (!started[0]) {
                        pend.append(text);
                        if (pend.length() < 36) return;
                        if (looksDuplicate(built.prompt, pend.toString())) throw new RetrySignal();
                        flushPending.run();
                        return;
                    }
                    String emit = gateRef[0].feed(text);
                    if (!emit.isEmpty()) sendChunk(res, id, model, created, contentDelta(emit), null, null);
                } catch (ClientGone cg) {
                    throw cg;
                } catch (RetrySignal rs) {
                    throw rs;
                } catch (Exception e) {
                    // 客户端已断开：抛出让上游流尽快结束，避免白烧配额
                    throw new ClientGone();
                }
            }
            @Override public void onThink(String think) {
                if (!started[0]) {
                    if (pendThink.length() < 4000) pendThink.append(think);
                    return;
                }
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
            @Override public boolean hasSent() { return started[0]; }
            @Override public void reset() {
                started[0] = false;
                pend.setLength(0);
                pendThink.setLength(0);
                gateRef[0] = new ToolBridge.StreamGate();
            }
        }
        WenxinSession.Listener listener = new StreamListener();

        WenxinSession.Result r;
        try {
            r = runOnce(built.prompt, internalModel, search, think, built.images, listener);
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

        flushPending.run();
        String tail = gateRef[0].finish();
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
        lastCallMs = r.totalMs;
        publishState(r.error != null ? ApiActivity.PHASE_ERROR : ApiActivity.PHASE_DONE,
                model, r.text.length(), r.error == null ? "" : Tools.truncate(r.error, 40));
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
        logger.log("[done] " + r.totalMs + "ms · " + r.text.length() + "字 · «"
                + Tools.truncate(r.textStr().replace("\n", " "), 40) + "»"
                + (r.withdrew ? " (已拦截撤回并保全原文)" : ""));
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
