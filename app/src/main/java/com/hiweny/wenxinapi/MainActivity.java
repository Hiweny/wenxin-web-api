package com.hiweny.wenxinapi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hiweny.wenxinapi.core.Config;
import com.hiweny.wenxinapi.core.HistoryStore;
import com.hiweny.wenxinapi.server.HttpServer;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String[] MODELS = {"deepseek-v4-pro", "deepseek-v4-flash", "deepseek-r1", "ernie-5.1", "smartmode"};
    private static final String[] MODEL_LABELS = {
            "DeepSeek V4 Pro（推荐）",
            "DeepSeek V4 Flash（更快）",
            "DeepSeek R1（深度思考）",
            "文心 5.1",
            "智能模式"
    };

    private TextView dot, statusText, poolText, baseUrl, btnToggle, logView, copyUrl, btnHistory, btnClearLog;
    private EditText apiKey, port;
    private Spinner modelSpinner;
    private CheckBox switchSearch, switchFloat;
    private ScrollView logScroll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Config cfg;
    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            logView.setText(Log.text());
            logScroll.post(new Runnable() {
                @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
            ui.postDelayed(this, 700);
        }
    };
    private final Runnable stateTick = new Runnable() {
        @Override public void run() {
            refreshState();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0xFF0B0D12);
        w.setNavigationBarColor(0xFF0B0D12);
        setContentView(R.layout.activity_main);

        cfg = Prefs.load(this);
        dot = findViewById(R.id.dot);
        statusText = findViewById(R.id.statusText);
        poolText = findViewById(R.id.poolText);
        baseUrl = findViewById(R.id.baseUrl);
        btnToggle = findViewById(R.id.btnToggle);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        copyUrl = findViewById(R.id.copyUrl);
        btnHistory = findViewById(R.id.btnHistory);
        btnClearLog = findViewById(R.id.btnClearLog);
        apiKey = findViewById(R.id.apiKey);
        port = findViewById(R.id.port);
        modelSpinner = findViewById(R.id.modelSpinner);
        switchSearch = findViewById(R.id.switchSearch);
        switchFloat = findViewById(R.id.switchFloat);

        apiKey.setText(cfg.apiKey);
        port.setText(String.valueOf(cfg.port));
        switchSearch.setChecked(cfg.searchDefault);
        switchFloat.setChecked(Prefs.getAttr(this, "bubble", "0").equals("1"));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, MODEL_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        for (int i = 0; i < MODELS.length; i++) {
            if (MODELS[i].equals(cfg.defaultModel)) { modelSpinner.setSelection(i); break; }
        }

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ApiService.isRunning()) {
                    stopService(new Intent(MainActivity.this, ApiService.class).setAction(ApiService.ACTION_STOP));
                    Prefs.setWantRunning(MainActivity.this, false);
                    Log.add("[ui] 已请求停止");
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshState(); } }, 400);
                } else {
                    saveCfg();
                    ensureNotificationPermission();
                    Intent i = new Intent(MainActivity.this, ApiService.class);
                    i.setAction(ApiService.ACTION_START);
                    i.putExtra("port", cfg.port);
                    i.putExtra("api_key", cfg.apiKey);
                    i.putExtra("model", cfg.defaultModel);
                    i.putExtra("search", cfg.searchDefault);
                    i.putExtra("pool", cfg.poolSize);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                    Prefs.setWantRunning(MainActivity.this, true);
                    ui.postDelayed(new Runnable() { @Override public void run() { refreshState(); } }, 900);
                }
            }
        });

        copyUrl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = currentBaseUrl();
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("base_url", url));
                Toast.makeText(MainActivity.this, "已复制：" + url, Toast.LENGTH_SHORT).show();
            }
        });

        btnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Log.clear(); }
        });

        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistory(); }
        });

        switchFloat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = switchFloat.isChecked();
                Prefs.setAttr(MainActivity.this, "bubble", on ? "1" : "0");
                if (on && !canOverlay()) {
                    requestOverlay();
                    switchFloat.setChecked(false);
                    Prefs.setAttr(MainActivity.this, "bubble", "0");
                    return;
                }
                if (ApiService.INSTANCE != null) ApiService.INSTANCE.syncBubble();
            }
        });

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                cfg.defaultModel = MODELS[position];
                Prefs.save(MainActivity.this, cfg);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        if (getIntent() != null && getIntent().getBooleanExtra("auto_start", false)) {
            saveCfg();
            Intent i = new Intent(this, ApiService.class);
            i.setAction(ApiService.ACTION_START);
            i.putExtra("port", cfg.port);
            i.putExtra("api_key", cfg.apiKey);
            i.putExtra("model", cfg.defaultModel);
            i.putExtra("search", cfg.searchDefault);
            i.putExtra("pool", cfg.poolSize);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Prefs.setWantRunning(this, true);
        }

        Log.subscribe(logTick);
        Log.add("[ui] 界面已就绪");
        ui.post(logTick);
        ui.post(stateTick);
    }

    private void saveCfg() {
        cfg.apiKey = apiKey.getText().toString().trim();
        if (cfg.apiKey.isEmpty()) cfg.apiKey = "sk-wenxin";
        try { cfg.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { cfg.port = 8787; }
        if (cfg.port < 1024 || cfg.port > 65535) cfg.port = 8787;
        cfg.searchDefault = switchSearch.isChecked();
        cfg.defaultModel = MODELS[Math.max(0, modelSpinner.getSelectedItemPosition())];
        Prefs.save(this, cfg);
    }

    private String currentBaseUrl() {
        if (ApiService.isRunning() && ApiService.BASE_URL != null && !ApiService.BASE_URL.isEmpty()) {
            return ApiService.BASE_URL;
        }
        List<String> ips = HttpServer.localIps();
        String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        return "http://" + ip + ":" + cfg.port + "/v1";
    }

    private void refreshState() {
        boolean running = ApiService.isRunning();
        dot.setBackgroundResource(running ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
        statusText.setText(running ? "运行中" : "已停止");
        statusText.setTextColor(running ? 0xFF3DD68C : 0xFF8A93A6);
        btnToggle.setText(running ? "停止服务" : "启动服务");
        btnToggle.setBackgroundResource(running ? R.drawable.bg_ghost : R.drawable.bg_primary);
        btnToggle.setTextColor(running ? 0xFFE7EAF0 : 0xFFFFFFFF);
        baseUrl.setText(currentBaseUrl());
        HistoryStore s = ApiService.store();
        poolText.setText("会话池 " + (cfg.poolSize) + (s == null ? "" : ("  ·  记录 " + s.size())));
    }

    private void showHistory() {
        HistoryStore s = ApiService.store();
        if (s == null) {
            Toast.makeText(this, "请先启动服务", Toast.LENGTH_SHORT).show();
            return;
        }
        List<HistoryStore.Entry> list = s.list(60);
        if (list.isEmpty()) {
            Toast.makeText(this, "暂无记录", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] items = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++) {
            HistoryStore.Entry e = list.get(i);
            String head = (e.time == null ? "" : e.time) + "  " + (e.model == null ? "" : e.model);
            String body = e.answer == null ? "" : e.answer.replace("\n", " ");
            if (body.length() > 48) body = body.substring(0, 48) + "…";
            items[i] = head + "\n" + body + (e.flags != null && !e.flags.isEmpty() ? ("  [" + e.flags.trim() + "]") : "");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("防撤回记录（本地存档）")
                .setItems(items, new DialogOnClick(list))
                .setNegativeButton("关闭", null)
                .show();
    }

    private class DialogOnClick implements android.content.DialogInterface.OnClickListener {
        private final List<HistoryStore.Entry> list;
        DialogOnClick(List<HistoryStore.Entry> l) { this.list = l; }

        @Override public void onClick(android.content.DialogInterface d, int which) {
            HistoryStore.Entry e = list.get(which);
            String content = "时间：" + e.time + "\n模型：" + e.model + "\n耗时：" + e.ms + "ms"
                    + "\nToken：" + e.promptTokens + "/" + e.completionTokens
                    + (e.flags == null || e.flags.isEmpty() ? "" : ("\n标记：" + e.flags))
                    + "\n\n—— 提问 ——\n" + e.prompt + "\n\n—— 回答 ——\n" + e.answer;
            ScrollView sv = new ScrollView(MainActivity.this);
            TextView tv = new TextView(MainActivity.this);
            tv.setText(content);
            tv.setTextSize(12.5f);
            tv.setTextColor(0xFFE7EAF0);
            tv.setPadding(32, 24, 32, 24);
            tv.setTextIsSelectable(true);
            sv.addView(tv);
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("记录详情")
                    .setView(sv)
                    .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("history", content));
                            Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            }
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignore) { }
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlay() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "请授予悬浮窗权限后再次开启", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法请求悬浮窗权限", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        if (Prefs.wantRunning(this) && !ApiService.isRunning()) {
            Intent i = new Intent(this, ApiService.class);
            i.setAction(ApiService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
    }

    @Override
    protected void onDestroy() {
        Log.unsubscribe(logTick);
        ui.removeCallbacks(logTick);
        ui.removeCallbacks(stateTick);
        super.onDestroy();
    }
}
