package com.hiweny.wenxinapi.server;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 极简 HTTP/1.1 服务器（支持 chunked 响应，用于 SSE 流式） */
public class HttpServer {

    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    public interface Logger {
        void log(String line);
    }

    public static class Request {
        public String method = "";
        public String path = "/";
        public String rawQuery = "";
        public Map<String, String> headers = new LinkedHashMap<>();
        public Map<String, String> query = new LinkedHashMap<>();
        public Map<String, String> params = new LinkedHashMap<>(); // 路径参数（预留）
        public byte[] body = new byte[0];

        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }
    }

    public static class Response {
        private final OutputStream out;
        public int status = 200;
        public Map<String, String> headers = new LinkedHashMap<>();
        public boolean chunked = false;
        private boolean headersSent = false;
        private boolean finished = false;

        Response(OutputStream out) {
            this.out = out;
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Headers", "*");
            headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        }

        public synchronized void sendHeaders() throws IOException {
            if (headersSent) return;
            headersSent = true;
            if (chunked && !headers.containsKey("Transfer-Encoding")) {
                headers.put("Transfer-Encoding", "chunked");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public synchronized void write(byte[] data) throws IOException {
            if (finished) return;
            sendHeaders();
            if (chunked) {
                out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(data);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(data);
            }
            out.flush();
        }

        public synchronized void write(String s) throws IOException {
            write(s.getBytes(StandardCharsets.UTF_8));
        }

        public synchronized void end() throws IOException {
            if (finished) return;
            if (!headersSent) {
                if (!headers.containsKey("Content-Type")) headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("Content-Length", "0");
            }
            sendHeaders();
            if (chunked) {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            finished = true;
        }

        public void json(int status, String body) throws IOException {
            this.status = status;
            headers.put("Content-Type", "application/json; charset=utf-8");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Length", String.valueOf(b.length));
            headersSent = false;
            chunked = false;
            sendHeaders();
            out.write(b);
            out.flush();
            finished = true;
        }

        public void text(int status, String contentType, String body) throws IOException {
            this.status = status;
            headers.put("Content-Type", contentType);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Length", String.valueOf(b.length));
            chunked = false;
            sendHeaders();
            out.write(b);
            out.flush();
            finished = true;
        }

        private static String reason(int code) {
            switch (code) {
                case 200: return "OK";
                case 204: return "No Content";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 429: return "Too Many Requests";
                case 500: return "Internal Server Error";
                case 502: return "Bad Gateway";
                default: return "OK";
            }
        }
    }

    private final int port;
    private final Handler handler;
    private final Logger logger;
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;

    public HttpServer(int port, Handler handler, Logger logger) {
        this.port = port;
        this.handler = handler;
        this.logger = logger;
    }

    public boolean isRunning() { return running.get(); }

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        running.set(true);
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                logger.log("[http] listening on 0.0.0.0:" + port);
                while (running.get()) {
                    try {
                        final Socket s = serverSocket.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { serve(s); }
                        });
                    } catch (IOException e) {
                        if (running.get()) logger.log("[http] accept error: " + e.getMessage());
                    }
                }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignore) { }
        if (pool != null) pool.shutdownNow();
        logger.log("[http] stopped");
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(180000);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { socket.close(); return; }
            String[] parts = requestLine.split(" ");
            Request req = new Request();
            req.method = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            int qi = target.indexOf('?');
            if (qi >= 0) {
                req.path = target.substring(0, qi);
                req.rawQuery = target.substring(qi + 1);
                for (String kv : req.rawQuery.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) req.query.put(kv.substring(0, eq), java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            } else {
                req.path = target;
            }

            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    req.headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }

            String cl = req.header("Content-Length");
            if (cl != null) {
                int len = Integer.parseInt(cl.trim());
                byte[] body = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(body, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
                req.body = body;
            } else if ("chunked".equalsIgnoreCase(req.header("Transfer-Encoding"))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                while (true) {
                    String sizeLine = readLine(in);
                    if (sizeLine == null) break;
                    sizeLine = sizeLine.trim();
                    if (sizeLine.isEmpty()) continue;
                    int size = Integer.parseInt(sizeLine.split(";")[0], 16);
                    if (size == 0) { readLine(in); break; }
                    byte[] chunk = new byte[size];
                    int read = 0;
                    while (read < size) {
                        int n = in.read(chunk, read, size - read);
                        if (n < 0) break;
                        read += n;
                    }
                    bos.write(chunk, 0, read);
                    readLine(in);
                }
                req.body = bos.toByteArray();
            }

            Response res = new Response(out);
            if ("OPTIONS".equals(req.method)) {
                res.sendHeaders();
                res.end();
            } else {
                handler.handle(req, res);
                res.end();
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            logger.log("[http] serve error: " + e);
            try { socket.close(); } catch (Exception ignore) { }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int prev = -1;
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
            prev = c;
            if (bos.size() > 1024 * 1024) break;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddressHolder h : holders(ni)) {
                    String ip = h.ip;
                    if (ip != null && ip.startsWith("192.168.") || (ip != null && ip.startsWith("10."))
                            || (ip != null && ip.startsWith("172."))) {
                        out.add(ip);
                    }
                }
            }
        } catch (Exception ignore) { }
        if (out.isEmpty()) out.addAll(Collections.singletonList("127.0.0.1"));
        return out;
    }

    private static List<InterfaceAddressHolder> holders(NetworkInterface ni) {
        List<InterfaceAddressHolder> l = new ArrayList<>();
        for (java.net.InterfaceAddress a : ni.getInterfaceAddresses()) {
            InetAddress addr = a.getAddress();
            if (addr instanceof java.net.Inet4Address) {
                InterfaceAddressHolder h = new InterfaceAddressHolder();
                h.ip = addr.getHostAddress();
                l.add(h);
            }
        }
        return l;
    }

    private static class InterfaceAddressHolder { String ip; }
}
