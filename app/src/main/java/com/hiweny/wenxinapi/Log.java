package com.hiweny.wenxinapi;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** 轻量全局日志（内存环形缓冲 + UI 订阅） */
public final class Log {
    private static final int CAP = 600;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    public static void add(String line) {
        String stamped = FMT.format(new Date()) + "  " + line;
        List<Runnable> copy;
        synchronized (LINES) {
            LINES.addLast(stamped);
            while (LINES.size() > CAP) LINES.removeFirst();
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) {
            try { r.run(); } catch (Exception ignore) { }
        }
    }

    public static void clear() {
        synchronized (LINES) { LINES.clear(); }
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

    public static void subscribe(Runnable r) {
        synchronized (LISTENERS) { LISTENERS.add(r); }
    }

    public static void unsubscribe(Runnable r) {
        synchronized (LISTENERS) { LISTENERS.remove(r); }
    }
}
