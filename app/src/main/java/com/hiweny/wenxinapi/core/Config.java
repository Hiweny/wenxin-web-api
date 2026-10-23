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
    /** 折叠后 prompt 的字符预算（文心单轮接口，太长会答非所问甚至被拒） */
    public int contextChars = 7000;
}
