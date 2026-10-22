package com.hiweny.wenxinapi.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 手写 HTTP/1.1 客户端（仅 GET/POST + chunked 响应）。
 *
 * 为什么不用 HttpURLConnection：Android 的实现会改写/丢弃部分 header（空值、Cookie 合并、
 * 强制 gzip 等），导致同样的代码在 JVM 上能过、在手机上被上游判为非法请求。
 * 这里自己控制每一个字节，JVM 与 Android 行为完全一致。
 */
public final class Http1Client {

    public static class Response {
        public int status;
        public final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        public final List<String> setCookies = new ArrayList<>();
        public InputStream body;
        public Socket socket;

        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }

        public String readAll() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = body.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }

        /** 逐行读取（SSE） */
        public String readLine() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (true) {
                int c = body.read();
                if (c < 0) return bos.size() == 0 ? null : new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (c == '\n') {
                    byte[] b = bos.toByteArray();
                    int len = b.length;
                    if (len > 0 && b[len - 1] == '\r') len--;
                    return new String(b, 0, len, StandardCharsets.UTF_8);
                }
                bos.write(c);
            }
        }

        public void close() {
            try { if (body != null) body.close(); } catch (Exception ignore) { }
            try { if (socket != null) socket.close(); } catch (Exception ignore) { }
        }
    }

    public static Response request(String urlStr, String method, Map<String, String> extraHeaders,
                                   byte[] payload, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URL url = new URL(urlStr);
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        String path = url.getFile();
        if (path == null || path.isEmpty()) path = "/";

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);

        if (https) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(socket, host, port, true);
            try {
                SSLParameters p = ssl.getSSLParameters();
                p.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
                ssl.setSSLParameters(p);
            } catch (Throwable ignore) { }
            ssl.setUseClientMode(true);
            ssl.startHandshake();
            socket = ssl;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() == null) continue;
                sb.append(e.getKey()).append(": ").append(e.getValue() == null ? "" : e.getValue()).append("\r\n");
            }
        }
        if (payload != null) {
            sb.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (payload != null) out.write(payload);
        out.flush();

        InputStream in = socket.getInputStream();
        String statusLine = readLineRaw(in);
        if (statusLine == null) throw new IOException("empty response");
        Response res = new Response();
        String[] parts = statusLine.split(" ");
        res.status = parts.length > 1 ? safeInt(parts[1]) : 0;
        String line;
        while ((line = readLineRaw(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();
            if ("set-cookie".equalsIgnoreCase(k)) res.setCookies.add(v);
            res.headers.put(k, v);
        }
        res.socket = socket;

        String te = res.header("Transfer-Encoding");
        String cl = res.header("Content-Length");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            res.body = new ChunkedInputStream(in);
        } else if (cl != null) {
            res.body = new FixedLengthStream(in, safeInt(cl.trim()));
        } else {
            res.body = in;
        }
        return res;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String readLineRaw(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return bos.size() == 0 ? null : new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (c == '\n') {
                byte[] b = bos.toByteArray();
                int len = b.length;
                if (len > 0 && b[len - 1] == '\r') len--;
                return new String(b, 0, len, StandardCharsets.UTF_8);
            }
            bos.write(c);
            if (bos.size() > 256 * 1024) break;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** chunked 解码流 */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private int remaining = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0) {
                String line = readLineRaw(in);
                if (line == null) { eof = true; return -1; }
                line = line.trim();
                if (line.isEmpty()) { // 上一个 chunk 的 \r\n 之后可能还有空行
                    line = readLineRaw(in);
                    if (line == null) { eof = true; return -1; }
                    line = line.trim();
                }
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                int size;
                try { size = Integer.parseInt(line.trim(), 16); } catch (Exception e) { eof = true; return -1; }
                if (size == 0) {
                    // 吃掉尾部 trailer
                    String t;
                    while ((t = readLineRaw(in)) != null && !t.isEmpty()) { /* skip */ }
                    eof = true;
                    return -1;
                }
                remaining = size;
            }
            int toRead = Math.min(len, remaining);
            int n = in.read(buf, off, toRead);
            if (n < 0) { eof = true; return -1; }
            remaining -= n;
            if (remaining == 0) {
                // 每个 chunk 后跟 CRLF
                readLineRaw(in);
            }
            return n;
        }
    }

    /** 定长流 */
    static final class FixedLengthStream extends InputStream {
        private final InputStream in;
        private int left;

        FixedLengthStream(InputStream in, int len) { this.in = in; this.left = len; }

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, Math.min(len, left));
            if (n < 0) return -1;
            left -= n;
            return n;
        }
    }
}
