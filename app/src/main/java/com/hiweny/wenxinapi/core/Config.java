package com.hiweny.wenxinapi.core;

/** 运行配置（Android 侧由 SharedPreferences 填充，桌面上可直接 new） */
public class Config {
    public int port = 8787;
    public String apiKey = "sk-wenxin";
    public int poolSize = 4;
    public String defaultModel = "deepseek-v4-pro";
    public boolean searchDefault = false;
    public boolean thinkDefault = true;
    public boolean saveHistory = true;
    public boolean allowNoKey = false;
}
