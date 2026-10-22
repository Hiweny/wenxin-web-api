package com.hiweny.wenxinapi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.hiweny.wenxinapi.core.Config;
import com.hiweny.wenxinapi.core.HistoryStore;
import com.hiweny.wenxinapi.core.SessionPool;
import com.hiweny.wenxinapi.server.ApiRouter;
import com.hiweny.wenxinapi.server.HttpServer;

import java.io.File;
import java.util.List;

/** 前台服务：承载本地 HTTP 服务 + 会话池，负责保活 */
public class ApiService extends Service {

    public static final String ACTION_START = "com.hiweny.wenxinapi.START";
    public static final String ACTION_STOP = "com.hiweny.wenxinapi.STOP";
    private static final String CHANNEL = "wenxin_api_fg";
    private static final int NOTIF_ID = 1001;

    public static volatile ApiService INSTANCE;
    public static volatile boolean RUNNING = false;
    public static volatile String BASE_URL = "";
    public static volatile int POOL_SIZE = 3;

    private HttpServer server;
    private SessionPool pool;
    private HistoryStore store;
    private Config cfg;
    private PowerManager.WakeLock wakeLock;
    private FloatingBubble bubble;

    /** 根据配置开关悬浮球 */
    public void syncBubble() {
        boolean want = "1".equals(Prefs.getAttr(this, "bubble", "0"));
        if (want) {
            if (bubble == null) bubble = new FloatingBubble(this);
            if (!bubble.isShown()) bubble.show();
        } else if (bubble != null) {
            bubble.hide();
        }
    }

    public interface StateListener { void onState(); }

    private static volatile StateListener stateListener;

    public static void setStateListener(StateListener l) { stateListener = l; }

    private static void notifyState() {
        StateListener l = stateListener;
        if (l != null) {
            try { l.onState(); } catch (Exception ignore) { }
        }
    }

    public static HistoryStore store() {
        ApiService s = INSTANCE;
        return s == null ? null : s.store;
    }

    public static boolean isRunning() { return RUNNING; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createChannel();
        cfg = Prefs.load(this);
        store = new HistoryStore(new File(getFilesDir(), "data"), 500);
        pool = new SessionPool(cfg.poolSize, new SessionPool.Logger() {
            @Override public void log(String line) { Log.add(line); }
        });
        POOL_SIZE = cfg.poolSize;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : (intent.getAction() == null ? ACTION_START : intent.getAction());
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            overrideConfigFromIntent(intent);
        }
        startForegroundSafely();
        startServer();
        return START_STICKY;
    }

    private void overrideConfigFromIntent(Intent i) {
        int port = i.getIntExtra("port", cfg.port);
        String key = i.getStringExtra("api_key");
        String model = i.getStringExtra("model");
        boolean search = i.getBooleanExtra("search", cfg.searchDefault);
        int poolN = i.getIntExtra("pool", cfg.poolSize);
        cfg.port = port;
        if (key != null) cfg.apiKey = key;
        if (model != null) cfg.defaultModel = model;
        cfg.searchDefault = search;
        if (poolN != cfg.poolSize) {
            cfg.poolSize = poolN;
            pool = new SessionPool(poolN, new SessionPool.Logger() {
                @Override public void log(String line) { Log.add(line); }
            });
        }
        Prefs.save(this, cfg);
    }

    private void startServer() {
        if (RUNNING) { notifyState(); return; }
        try {
            ApiRouter router = new ApiRouter(cfg, pool, store, new ApiRouter.Logger() {
                @Override public void log(String line) { Log.add(line); }
            }, indexHtml());
            server = new HttpServer(cfg.port, router, new HttpServer.Logger() {
                @Override public void log(String line) { Log.add(line); }
            });
            server.start();
            RUNNING = true;
            BASE_URL = "http://" + firstIp() + ":" + cfg.port + "/v1";
            Log.add("[svc] 服务已启动 " + BASE_URL);
            pool.prewarm();
            acquireWakeLock();
            updateNotification();
            syncBubble();
            notifyState();
        } catch (Exception e) {
            Log.add("[svc] 启动失败: " + e);
            RUNNING = false;
            notifyState();
        }
    }

    public void stopServer() {
        try { if (server != null) server.stop(); } catch (Exception ignore) { }
        server = null;
        RUNNING = false;
        BASE_URL = "";
        releaseWakeLock();
        Log.add("[svc] 服务已停止");
        notifyState();
    }

    private String firstIp() {
        List<String> ips = HttpServer.localIps();
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wenxin:http");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignore) { }
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null) wakeLock.release(); } catch (Exception ignore) { }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "API 服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundSafely() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, 0x1 /* dataSync */);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent stop = new Intent(this, ApiService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("文心 Web API 运行中")
                .setContentText(BASE_URL.isEmpty() ? "点击查看详情" : BASE_URL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build());
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignore) { }
    }

    @Override
    public void onDestroy() {
        try { if (bubble != null) bubble.hide(); } catch (Exception ignore) { }
        stopServer();
        RUNNING = false;
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private String indexHtml() {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Wenxin Web API</title><style>"
                + "body{margin:0;background:#0b0d12;color:#e7eaf0;font:14px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:28px}"
                + "h1{font-size:20px;margin:0 0 6px}p{color:#8a93a6;margin:6px 0}"
                + "code{background:#141821;border:1px solid #232a38;border-radius:8px;padding:2px 6px;font-family:ui-monospace,Menlo,monospace}"
                + ".card{background:#141821;border:1px solid #232a38;border-radius:16px;padding:18px;margin-top:16px}"
                + "a{color:#5b8cff}</style></head><body>"
                + "<h1>文心 Web API</h1><p>OpenAI 兼容接口已就绪</p>"
                + "<div class='card'><p>Base URL</p><code>http://" + firstIp() + ":" + cfg.port + "/v1</code>"
                + "<p style='margin-top:14px'>Endpoints</p><p><code>POST /v1/chat/completions</code><br>"
                + "<code>GET /v1/models</code><br><code>GET /v1/history</code><br><code>GET /healthz</code></p></div>"
                + "</body></html>";
    }
}
