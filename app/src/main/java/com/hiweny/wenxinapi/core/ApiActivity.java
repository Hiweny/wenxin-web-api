package com.hiweny.wenxinapi.core;

/**
 * 调用状态广播（与 Android 解耦，核心层只负责发布）。
 * Android 侧把它同步到前台通知，用户随时能看到「正在思考 / 输出中 / 上次耗时」。
 */
public final class ApiActivity {

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_THINKING = "thinking";
    public static final String PHASE_STREAMING = "streaming";
    public static final String PHASE_DONE = "done";
    public static final String PHASE_ERROR = "error";

    public static final class State {
        public String phase = PHASE_IDLE;
        public String model = "";
        public int chars = 0;
        public long sinceMs = 0;
        public long lastMs = 0;
        public String note = "";

        public String label() {
            if (PHASE_THINKING.equals(phase)) return "正在思考 · " + model;
            if (PHASE_STREAMING.equals(phase)) return "输出中 · " + chars + " 字 · " + (sinceMs / 100) / 10.0 + "s";
            if (PHASE_DONE.equals(phase)) return "就绪 · 上次 " + (lastMs / 100) / 10.0 + "s / " + chars + " 字";
            if (PHASE_ERROR.equals(phase)) return "就绪 · 上次失败" + (note.isEmpty() ? "" : ("（" + note + "）"));
            return "待命";
        }
    }

    public interface Sink { void onState(State s); }

    private static volatile Sink sink;
    private static volatile State last = new State();

    public static void setSink(Sink s) { sink = s; }

    public static State last() { return last; }

    public static void publish(State s) {
        last = s;
        Sink k = sink;
        if (k != null) {
            try { k.onState(s); } catch (Throwable ignore) { }
        }
    }
}
