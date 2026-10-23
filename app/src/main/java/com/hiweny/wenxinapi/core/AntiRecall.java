package com.hiweny.wenxinapi.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 防撤回（Anti-Recall）。
 *
 * 参考 deepseek-enhance / wenxin-enhance-mobile 的 antirecall 模块，落到「API 服务端」这个形态上：
 *
 *  1) **保全**：流式生成过程中，上游可能用 showType=replace 把已经吐出的内容整体换掉（变短 / 变成
 *     一句错误提示 / 变成完全不同的文本）。此时**绝不覆盖**已经输出的内容，而是记住"撤回后的版本"，
 *     对外仍然交付字数最多的那一版原文。
 *  2) **标注**：被撤回的回答会打上 recalled 标记，写进本地存档（App 内可查、`/v1/history` 可查）。
 *  3) **回填**：客户端下一轮把"被撤回后的内容"当历史发回来时（这是最常见的上下文断裂原因），
 *     服务端用本地存档把原文换回去，保证对话上下文不断。
 */
public final class AntiRecall {

    public static final String TIP = "⚠️ 此回复已被上游撤回，以下为本地缓存原文";
    public static final String NOT_FOUND = "⛔ 此回复已被撤回，本地缓存中未找到";

    /** 上游撤回时常见的兜底文案（命中即判定为撤回，绝不覆盖原文） */
    private static final String[] RECALL_HINTS = {
            "抱歉，出了点小问题", "请稍后重试", "该内容已被", "内容不合规",
            "无法展示", "已停止生成", "回复已被删除", "内容已被删除"
    };

    public static class Record {
        public String id = "";
        public String prompt = "";
        public String text = "";        // 保全的原文（最长版本）
        public String replaced = "";    // 上游撤回后给出的版本
        public boolean recalled = false;
        public long at = 0;
        public String detail = "";
    }

    private static final int CAP = 400;
    private static final LinkedHashMap<String, Record> BY_ID = new LinkedHashMap<>();
    private static final List<Record> RECALLED = new ArrayList<>();

    public static boolean looksRecalled(String piece) {
        if (piece == null) return false;
        String t = piece.trim();
        if (t.length() > 160) return false;
        for (String h : RECALL_HINTS) {
            if (t.contains(h)) return true;
        }
        return false;
    }

    public static synchronized void remember(Record r) {
        if (r == null || r.text == null || r.text.isEmpty()) return;
        if (r.id != null && !r.id.isEmpty()) BY_ID.put(r.id, r);
        while (BY_ID.size() > CAP) {
            String first = BY_ID.keySet().iterator().next();
            BY_ID.remove(first);
        }
        if (r.recalled) {
            RECALLED.add(r);
            while (RECALLED.size() > CAP) RECALLED.remove(0);
        }
    }

    /**
     * 把客户端传回来的、可能已被撤回覆盖的 assistant 内容还原成原文。
     *
     * @return 原文；未命中则返回 null
     */
    public static synchronized String recover(String clientText) {
        if (clientText == null || clientText.trim().isEmpty()) return null;
        String t = clientText.trim();
        for (int i = RECALLED.size() - 1; i >= 0; i--) {
            Record r = RECALLED.get(i);
            if (r.text.equals(t)) return null;               // 已经是原文，无需处理
            if (sameHead(t, r.replaced)) return r.text;      // 客户端拿到的是撤回后的版本
            if (looksRecalled(t)) return r.text;             // 客户端把它换成了错误提示
        }
        return null;
    }

    public static synchronized List<Record> recalled() {
        return new ArrayList<>(RECALLED);
    }

    public static synchronized int recalledCount() { return RECALLED.size(); }

    public static synchronized void clear() {
        BY_ID.clear();
        RECALLED.clear();
    }

    private static boolean sameHead(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.replaceAll("\\s+", "");
        String y = b.replaceAll("\\s+", "");
        if (x.isEmpty() || y.isEmpty()) return false;
        int n = Math.min(24, Math.min(x.length(), y.length()));
        return x.substring(0, n).equals(y.substring(0, n));
    }

    public static Map<String, Object> toJson(Record r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("time", r.at);
        m.put("recalled", r.recalled);
        m.put("detail", r.detail);
        m.put("answer", r.text);
        m.put("replaced", r.replaced);
        m.put("prompt", Tools.truncate(r.prompt, 500));
        return m;
    }
}
