package com.hiweny.wenxinapi;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量全局日志（内存环形缓冲 + 增量订阅）。
 *
 * 性能要点：UI 侧不再每 700ms 拉一次全量文本重绘（那是"日志区闪一下、
 * 自己上下乱跳、滑动卡顿"的根源），改为只接收新增行的增量回调，追加写入。
 */
public final class Log {
    private static final int CAP = 600;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final List<Listener> DELTA = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    /** 增量回调：只在新行产生时触发 */
    public interface Listener {
        void onLine(String line);
        void onCleared();
    }

    public static void add(String line) {
        String stamped = FMT.format(new Date()) + "  " + line;
        try { android.util.Log.i("WenxinApi", stamped); } catch (Throwable ignore) { }
        List<Listener> deltaCopies;
        synchronized (LINES) {
            LINES.addLast(stamped);
            while (LINES.size() > CAP) LINES.removeFirst();
            synchronized (DELTA) { deltaCopies = new ArrayList<>(DELTA); }
        }
        for (Listener l : deltaCopies) {
            try { l.onLine(stamped); } catch (Exception ignore) { }
        }
        for (Runnable r : new ArrayList<>(LISTENERS)) {
            try { r.run(); } catch (Exception ignore) { }
        }
    }

    public static void clear() {
        List<Listener> deltaCopies;
        synchronized (LINES) {
            LINES.clear();
            synchronized (DELTA) { deltaCopies = new ArrayList<>(DELTA); }
        }
        for (Listener l : deltaCopies) {
            try { l.onCleared(); } catch (Exception ignore) { }
        }
        for (Runnable r : new ArrayList<>(LISTENERS)) {
            try { r.run(); } catch (Exception ignore) { }
        }
    }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String s : LINES) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** 最近 n 行（顺序为旧→新） */
    public static List<String> tail(int n) {
        List<String> out = new ArrayList<>();
        synchronized (LINES) {
            int from = Math.max(0, LINES.size() - n);
            int i = 0;
            for (String s : LINES) {
                if (i++ >= from) out.add(s);
            }
        }
        return out;
    }

    public static void subscribe(Runnable r) {
        synchronized (LISTENERS) { LISTENERS.add(r); }
    }

    public static void unsubscribe(Runnable r) {
        synchronized (LISTENERS) { LISTENERS.remove(r); }
    }

    public static void subscribe(Listener l) {
        synchronized (DELTA) { DELTA.add(l); }
    }

    public static void unsubscribe(Listener l) {
        synchronized (DELTA) { DELTA.remove(l); }
    }
}
