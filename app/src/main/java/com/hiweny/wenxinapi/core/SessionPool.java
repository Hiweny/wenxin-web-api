package com.hiweny.wenxinapi.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 匿名会话池：多个独立匿名身份（各自 cookie + token + lid）轮流使用，
 * 单会话内串行（避免同身份并发触发风控），跨会话并行。
 */
public class SessionPool {

    public interface Logger {
        void log(String line);
    }

    private final int size;
    private final WenxinSession[] sessions;
    private final ReentrantLock[] locks;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final Logger logger;

    public SessionPool(int size, Logger logger) {
        this.size = Math.max(1, Math.min(8, size));
        this.logger = logger;
        this.sessions = new WenxinSession[this.size];
        this.locks = new ReentrantLock[this.size];
        for (int i = 0; i < this.size; i++) {
            this.sessions[i] = new WenxinSession();
            this.locks[i] = new ReentrantLock();
        }
    }

    public int size() { return size; }

    public class Lease implements AutoCloseable {
        public final WenxinSession session;
        private final int idx;
        private boolean released = false;

        Lease(WenxinSession s, int idx) { this.session = s; this.idx = idx; }

        public void invalidate() {
            synchronized (SessionPool.this) {
                sessions[idx] = new WenxinSession();
            }
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            locks[idx].unlock();
        }
    }

    /** 取一个可用会话；优先空闲，其次按轮询顺序等待。 */
    public Lease acquire(long waitMs) throws IOException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            int n = cursor.getAndIncrement() % size;
            for (int k = 0; k < size; k++) {
                int i = (n + k) % size;
                if (locks[i].tryLock()) {
                    WenxinSession s = sessions[i];
                    if (!s.isReady()) {
                        try {
                            s.warm();
                            logger.log("[pool] slot#" + i + " warmed token=" + s.token + " lid=" + s.lid + " cookies=" + s.cookieHeader().length() + "b [" + s.cookieNames() + "]");
                        } catch (Exception e) {
                            locks[i].unlock();
                            logger.log("[pool] slot#" + i + " warm failed: " + e.getMessage());
                            continue;
                        }
                    }
                    return new Lease(s, i);
                }
            }
            if (System.currentTimeMillis() > deadline) {
                // 超时降级：再尝试一次强制等待第一个槽位
                boolean got = false;
                try {
                    got = locks[0].tryLock(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (got) {
                    WenxinSession s = sessions[0];
                    if (!s.isReady()) {
                        try { s.warm(); } catch (Exception e) { locks[0].unlock(); throw new IOException("warm failed: " + e.getMessage()); }
                    }
                    return new Lease(s, 0);
                }
                throw new IOException("服务器忙（会话池已满），请稍后重试");
            }
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
    }

    public List<String> snapshot() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            WenxinSession s = sessions[i];
            out.add("slot#" + i + (s.isReady() ? (" token=" + s.token + " calls=" + s.totalCalls + " fails=" + s.failCount) : " (cold)"));
        }
        return out;
    }

    public void prewarm() {
        for (int i = 0; i < size; i++) {
            final int idx = i;
            new Thread(new Runnable() {
                @Override public void run() {
                    if (!sessions[idx].isReady() && locks[idx].tryLock()) {
                        try {
                            sessions[idx].warm();
                            logger.log("[pool] prewarm slot#" + idx + " ok");
                        } catch (Exception e) {
                            logger.log("[pool] prewarm slot#" + idx + " failed: " + e.getMessage());
                        } finally {
                            locks[idx].unlock();
                        }
                    }
                }
            }, "prewarm-" + i).start();
        }
    }
}
