package com.hiweny.wenxinapi.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 与平台无关的小工具（避免使用 android.util / java.util.Base64，保证 minSdk 24 可用、且能在纯 JVM 上跑测试）。 */
public final class Tools {

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String b64(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < in.length) {
            int n = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8) | (in[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rest = in.length - i;
        if (rest == 1) {
            int n = (in[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String nowIso() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        return f.format(new java.util.Date());
    }

    public static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
