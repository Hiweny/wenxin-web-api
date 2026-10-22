package com.hiweny.wenxinapi;

import android.content.Context;
import android.content.SharedPreferences;

import com.hiweny.wenxinapi.core.Config;

/** 配置持久化 */
public class Prefs {
    private static final String NAME = "wenxin_api_prefs";

    public static Config load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        Config c = new Config();
        c.port = sp.getInt("port", 8787);
        c.apiKey = sp.getString("api_key", "sk-wenxin");
        c.poolSize = sp.getInt("pool", 3);
        c.defaultModel = sp.getString("model", "deepseek-v4-pro");
        c.searchDefault = sp.getBoolean("search", false);
        c.saveHistory = true;
        return c;
    }

    public static void save(Context ctx, Config c) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit();
        e.putInt("port", c.port);
        e.putString("api_key", c.apiKey);
        e.putInt("pool", c.poolSize);
        e.putString("model", c.defaultModel);
        e.putBoolean("search", c.searchDefault);
        e.apply();
    }

    public static boolean wantRunning(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("want_running", false);
    }

    public static void setWantRunning(Context ctx, boolean v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("want_running", v).apply();
    }

    public static void setAttr(Context ctx, String k, String v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(k, v).apply();
    }

    public static String getAttr(Context ctx, String k, String def) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(k, def);
    }
}
