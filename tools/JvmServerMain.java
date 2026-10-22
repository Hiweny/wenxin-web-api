package com.hiweny.wenxinapi;

import com.hiweny.wenxinapi.core.Config;
import com.hiweny.wenxinapi.core.HistoryStore;
import com.hiweny.wenxinapi.core.SessionPool;
import com.hiweny.wenxinapi.server.ApiRouter;
import com.hiweny.wenxinapi.server.HttpServer;

import java.io.File;

/** 纯 JVM 启动入口（用于 CI / 桌面自测，不依赖 Android） */
public class JvmServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
        Config cfg = new Config();
        cfg.port = port;
        SessionPool.Logger logger = new SessionPool.Logger() {
            @Override public void log(String line) { System.out.println(line); }
        };
        HistoryStore store = new HistoryStore(new File("/tmp/wxhist"), 100);
        SessionPool pool = new SessionPool(cfg.poolSize, logger);
        ApiRouter router = new ApiRouter(cfg, pool, store, new ApiRouter.Logger() {
            @Override public void log(String line) { System.out.println(line); }
        }, "<html><body style='background:#0b0d12;color:#e7eaf0'>ok</body></html>");
        HttpServer server = new HttpServer(port, router, new HttpServer.Logger() {
            @Override public void log(String line) { System.out.println(line); }
        });
        server.start();
        pool.prewarm();
        System.out.println("[jvm] listening " + port);
        Thread.sleep(Long.MAX_VALUE);
    }
}
