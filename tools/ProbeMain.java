package com.hiweny.wenxinapi.core;

/** 纯 JVM 冒烟测试：验证 免登录预热 + 对话 的完整链路 */
public class ProbeMain {
    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? args[0] : "只回复：OK";
        String model = args.length > 1 ? args[1] : "DeepSeek-V4";
        WenxinSession s = new WenxinSession();
        long t0 = System.currentTimeMillis();
        s.warm();
        System.out.println("[warm] " + (System.currentTimeMillis() - t0) + "ms token=" + s.token
                + " lid=" + s.lid + " cookies=" + s.cookieHeader().length() + "chars");
        final int[] deltas = {0};
        WenxinSession.Result r = s.chat(q, model, false, new WenxinSession.Listener() {
            @Override public void onDelta(String text) { deltas[0]++; }
            @Override public void onThink(String think) { }
            @Override public void onModel(String m) { System.out.println("[model] " + m); }
            @Override public void onWithdraw(String reason) { System.out.println("[withdraw] " + reason); }
        });
        System.out.println("[result] model=" + r.model + " finished=" + r.finished
                + " err=" + r.error + " tokens=" + r.promptTokens + "/" + r.completionTokens
                + " deltas=" + deltas[0] + " total=" + r.totalMs + "ms");
        System.out.println("[text] " + r.textStr());
        if (r.thinkStr().length() > 0) System.out.println("[think] " + Tools.truncate(r.thinkStr(), 200));
    }
}
