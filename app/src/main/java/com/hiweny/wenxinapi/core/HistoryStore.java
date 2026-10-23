package com.hiweny.wenxinapi.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 对话存档 —— "防撤回"的落地部分：
 * 即使上游中途撤回/报错，回答在本地仍完整保留，可在 App 内查看与再次取回（API: /v1/history）。
 */
public class HistoryStore {

    public static class Entry {
        public String id;
        public String time;
        public String model;
        public String prompt;
        public String answer;
        public String think;
        public String flags;
        public String replaced;
        public long ms;
        public int promptTokens;
        public int completionTokens;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"id\":");
            Json.quote(sb, id == null ? "" : id);
            sb.append(",\"time\":");
            Json.quote(sb, time == null ? "" : time);
            sb.append(",\"model\":");
            Json.quote(sb, model == null ? "" : model);
            sb.append(",\"prompt\":");
            Json.quote(sb, prompt == null ? "" : prompt);
            sb.append(",\"answer\":");
            Json.quote(sb, answer == null ? "" : answer);
            sb.append(",\"think\":");
            Json.quote(sb, think == null ? "" : think);
            sb.append(",\"flags\":");
            Json.quote(sb, flags == null ? "" : flags);
            sb.append(",\"replaced\":");
            Json.quote(sb, replaced == null ? "" : replaced);
            sb.append(",\"ms\":").append(ms);
            sb.append(",\"pt\":").append(promptTokens);
            sb.append(",\"ct\":").append(completionTokens);
            sb.append('}');
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        public static Entry fromJson(String s) {
            Map<String, Object> m = Json.parseObj(s);
            Entry e = new Entry();
            e.id = Json.str(m, "id");
            e.time = Json.str(m, "time");
            e.model = Json.str(m, "model");
            e.prompt = Json.str(m, "prompt");
            e.answer = Json.str(m, "answer");
            e.think = Json.str(m, "think");
            e.flags = Json.str(m, "flags");
            e.replaced = Json.str(m, "replaced");
            Object ms = m.get("ms");
            e.ms = ms instanceof Number ? ((Number) ms).longValue() : 0;
            Object pt = m.get("pt");
            e.promptTokens = pt instanceof Number ? ((Number) pt).intValue() : 0;
            Object ct = m.get("ct");
            e.completionTokens = ct instanceof Number ? ((Number) ct).intValue() : 0;
            return e;
        }
    }

    private final LinkedList<Entry> entries = new LinkedList<>();
    private final int cap;
    private final File file;

    public HistoryStore(File dir, int cap) {
        this.cap = cap;
        this.file = dir == null ? null : new File(dir, "history.jsonl");
        if (file != null && file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        load();
    }

    /** 启动时把上次的存档读回来（否则重启后"防撤回记录"就是空的，等于没做） */
    private void load() {
        if (file == null || !file.isFile()) return;
        String tail = null;
        try {
            long len = file.length();
            long start = Math.max(0, len - 2 * 1024 * 1024);   // 只读尾部 2MB，避免大文件拖慢启动
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                tail = new String(buf, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return;
        }
        if (tail == null || tail.isEmpty()) return;
        String[] lines = tail.split("\n");
        java.util.LinkedList<Entry> loaded = new java.util.LinkedList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("{")) continue;
            try {
                Entry e = Entry.fromJson(t);
                if (e != null && e.answer != null) loaded.addLast(e);
            } catch (Exception ignore) { }
        }
        while (loaded.size() > cap) loaded.removeFirst();
        // entries 按"最新在前"
        for (java.util.Iterator<Entry> it = loaded.descendingIterator(); it.hasNext(); ) entries.addLast(it.next());
    }

    public synchronized void add(Entry e) {
        entries.addFirst(e);
        while (entries.size() > cap) entries.removeLast();
        if (file != null) {
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write((e.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignore) { }
        }
    }

    public synchronized List<Entry> list(int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (out.size() >= limit) break;
            out.add(e);
        }
        return out;
    }

    public synchronized Entry get(String id) {
        for (Entry e : entries) if (e.id != null && e.id.equals(id)) return e;
        return null;
    }

    public synchronized String listJson(int limit, boolean withBody) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry e : list(limit)) {
            if (!first) sb.append(',');
            first = false;
            if (withBody) {
                sb.append(e.toJson());
            } else {
                sb.append('{').append("\"id\":");
                Json.quote(sb, e.id == null ? "" : e.id);
                sb.append(",\"time\":");
                Json.quote(sb, e.time == null ? "" : e.time);
                sb.append(",\"model\":");
                Json.quote(sb, e.model == null ? "" : e.model);
                sb.append(",\"flags\":");
                Json.quote(sb, e.flags == null ? "" : e.flags);
                sb.append(",\"len\":").append(e.answer == null ? 0 : e.answer.length());
                sb.append('}');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public synchronized int size() { return entries.size(); }
}
