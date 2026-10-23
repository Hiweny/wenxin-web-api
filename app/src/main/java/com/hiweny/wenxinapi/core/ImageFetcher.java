package com.hiweny.wenxinapi.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 http(s) 图片下载并转成 data:URL 再发给上游。
 *
 * 为什么这么做：文心接口对"自己的"图片处理最稳定，外部 http 链接经常抓不到（防盗链/超时/需要 referer），
 * 表现为"识图失败：模型说看不到图片"。内联成 data:URL 后上游直接解码，成功率最高（实测可用）。
 */
public final class ImageFetcher {

    private static final int MAX_BYTES = 1200 * 1024;  // 单图上限 2MB

    public static String toDataUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String u = url.trim();
        if (u.startsWith("data:")) return u.length() > 4_000_000 ? null : u;
        if (!u.startsWith("http")) return null;
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1");
            h.put("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8");
            h.put("Accept-Encoding", "identity");
            Http1Client.Response r = Http1Client.request(u, "GET", h, null, 8000, 15000);
            try {
                if (r.status < 200 || r.status >= 300) return null;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = r.body.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_BYTES) return null;
                }
                byte[] b = bos.toByteArray();
                if (b.length < 128) return null;
                return "data:" + sniffMime(b) + ";base64," + base64(b);
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String sniffMime(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return "image/webp";
        return "image/jpeg";
    }

    /** 自实现 base64（Android minSdk 24 没有 java.util.Base64） */
    private static final char[] TAB = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(TAB[(v >> 18) & 63]).append(TAB[(v >> 12) & 63]).append(TAB[(v >> 6) & 63]).append(TAB[v & 63]);
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(TAB[(v >> 18) & 63]).append(TAB[(v >> 12) & 63]).append("==");
        } else if (rem == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(TAB[(v >> 18) & 63]).append(TAB[(v >> 12) & 63]).append(TAB[(v >> 6) & 63]).append('=');
        }
        return sb.toString();
    }
}
