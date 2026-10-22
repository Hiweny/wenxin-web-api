package com.hiweny.wenxinapi.core;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 匿名身份池（严格无状态版）。
 *
 * 关键设计：**每个请求用一个全新身份，用完即弃**。
 * 文心网页端把 lid 当成"页面会话"，复用同一身份连续提问时，服务端可能把后续请求
 * 当成同一会话的续写（表现为重复上一轮回答 / 上下文串味）。既然我们的 API 是无状态的
 * （每次都把完整对话折叠进 prompt），那就让上游身份也一次性使用，彻底消除串扰。
 *
 * 池子只负责"提前预热"，让 acquire 不必现场等 300~600ms。
 */
public class SessionPool {

    public interface Logger {
        void log(String line);
    }

    private final int size;
    private final LinkedList<WenxinSession> ready = new LinkedList<>();
    private final Logger logger;
    private volatile boolean closed = false;
    private Thread refiller;
    private final AtomicInteger seq = new AtomicInteger();

    public SessionPool(int size, Logger logger) {
        this.size = Math.max(1, Math.min(6, size));
        this.logger = logger;
    }

    public int size() { return size; }

    public class Lease implements AutoCloseable {
        public final WenxinSession session;
        private boolean released = false;

        Lease(WenxinSession s) { this.session = s; }

        @Override
        public void close() {
            if (released) return;
            released = true;
            // 一次性身份：直接丢弃，并由补货线程预热新的
            synchronized (ready) { ready.notifyAll(); }
        }
    }

    public void start() {
        if (refiller != null) return;
        refiller = new Thread(new Runnable() {
            @Override public void run() {
                while (!closed) {
                    WenxinSession s = null;
                    synchronized (ready) {
                        if (ready.size() >= size) {
                            try { ready.wait(500); } catch (InterruptedException e) { return; }
                            continue;
                        }
                    }
                    try {
                        s = new WenxinSession();
                        s.warm();
                        synchronized (ready) {
                            if (ready.size() < size) {
                                ready.addLast(s);
                                ready.notifyAll();
                            }
                        }
                    } catch (Exception e) {
                        logger.log("[pool] warm failed: " + e.getMessage());
                        try { Thread.sleep(800); } catch (InterruptedException ie) { return; }
                    }
                }
            }
        }, "session-refill");
        refiller.setDaemon(true);
        refiller.start();
        logger.log("[pool] 一次性身份池已启动，容量 " + size);
    }

    public void stop() {
        closed = true;
        if (refiller != null) refiller.interrupt();
    }

    /** 取一个全新身份；池空时现场预热一个（保证可用性优先于速度） */
    public Lease acquire(long waitMs) throws IOException {
        start();
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            synchronized (ready) {
                if (!ready.isEmpty()) {
                    return new Lease(ready.removeFirst());
                }
            }
            if (System.currentTimeMillis() > deadline) {
                // 兜底：现场预热
                WenxinSession s = new WenxinSession();
                s.warm();
                logger.log("[pool] 现场预热（池空）token=" + s.token);
                return new Lease(s);
            }
            synchronized (ready) {
                try { ready.wait(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    public synchronized java.util.List<String> snapshot() {
        java.util.List<String> out = new java.util.ArrayList<>();
        int n;
        synchronized (ready) { n = ready.size(); }
        out.add("一次性身份池：就绪 " + n + " / 容量 " + size);
        return out;
    }
}
