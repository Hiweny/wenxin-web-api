# 文心 Web API（wenxin-web-api）

把**百度文心助手手机版网页**（`wenxin.baidu.com`，**免登录**）里的 **DeepSeek-V4 Pro / DeepSeek-V4 Flash** 逆向成 **OpenAI 兼容 API**，并打包成可常驻后台的 Android APK。

> 纯 HTTP 直连，不依赖浏览器 / WebView 自动化；一个 APK 就是一台小 API 服务器。

---

## 特性

| 能力 | 说明 |
| --- | --- |
| 免登录 | 每次调用只需一次 `GET https://wenxin.baidu.com/` 预热，拿到匿名身份（cookie + token + lid），无需任何账号 |
| 模型 | `deepseek-v4-pro`、`deepseek-v4-flash`、`deepseek-r1`、`ernie-5.1`、`smartmode` |
| OpenAI 兼容 | `POST /v1/chat/completions`（流式 / 非流式）、`GET /v1/models`、`GET /v1/history`、`GET /healthz` |
| 工具调用 | 无原生 function calling → 用 `@@tool_calls@@` 标记协议 + 宽容解析，对客户端表现为标准 `tool_calls` |
| 防撤回 | 流式即时下发 + 本地完整存档（`/v1/history`、App 内「防撤回记录」），上游中途截断/撤回不影响已输出内容 |
| 会话池 | 多个独立匿名身份轮换，单身份串行、跨身份并行，失败自动重建，规避同身份高频风控 |
| APK 保活 | 前台服务 + WakeLock + 通知栏常驻 + 可选悬浮球 + 电池优化白名单引导 |
| 深色 UI | 单页极简控制台：状态、地址、Key、端口、模型、日志、记录 |

---

## 逆向出来的协议（2026-10 实测）

```
1) GET https://wenxin.baidu.com/   (手机 UA)
   → HTML 内嵌 <script type="application/json" name="aiTabFrameBaseData">
     { "token": "8hex", "lid": "19位数字", ... }        ← 匿名身份
   → 同时下发 cookie: BAIDUID / H_WISE_SIDS / BA_HECTOR / ZFY

2) POST https://chat.baidu.com/aichat/api/conversation
   body.message.searchInfo.chatParams.chat_token
       = base64( token | md5(query) | nowMs | lid ) + "-" + lid + "-3"
   header  x-chat-message:
       query:<urlencoded>,anti_ext:{"inputT":1500},enter_type:chat_url,
       re_rank:1,modelName:<DeepSeek-V4|DeepSeek-V4-Flash|...>,sa:bkb
   其它必带 header: source: wise_csaitab / isDeepseek: 1 / Origin / Referer

3) 响应 text/event-stream：
   event: basedata   → 会话信息（lid / baiduid）
   event: ping
   event: message    → 增量
       正文：data.message.content.generator.component 以 markdown 开头，
             文本在 generator.data.value（**增量**，直接拼接）
       思考：generator.data.reasoningContentArr[0]（全量）
       模型：generator.data.usedModel / speedInfo.labels.modelName
       用量：metaData.speedInfo.costs.promptTokenLabel / completionTokenLabel
       结束：metaData.state == "generate-complete" && endTurn == true
```

要点：`token`、`lid` 来自**首页 HTML**，`chat_token` 里的 md5 必须与 query 严格一致，
所以整套流程可以完全脱离浏览器（本仓库的 `WenxinSession` 就是纯 `HttpURLConnection` 实现）。

---

## 快速开始

### 方式一：装 APK（推荐）

1. 下载 Release 里的 `app-release.apk` 安装；
2. 打开 App → 设置端口（默认 8787）与 API Key（默认 `sk-wenxin`）→ **启动服务**；
3. 同一 Wi-Fi 下，客户端填 `http://<手机IP>:8787/v1`，Key 填你设置的字符串。

App 内有「防撤回记录」可查看本地完整存档；通知栏可一键停止；开启「悬浮球」后可随时点回 App。

### 方式二：纯 JVM 跑（调试用）

```bash
javac -encoding UTF-8 -d /tmp/wx \
  $(find app/src/main/java/com/hiweny/wenxinapi/core app/src/main/java/com/hiweny/wenxinapi/server -name '*.java') \
  tools/JvmServerMain.java
java -cp /tmp/wx com.hiweny.wenxinapi.JvmServerMain 8787
```

### 调用示例

```bash
curl http://127.0.0.1:8787/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-wenxin' \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"你好"}]}'
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8787/v1", api_key="sk-wenxin")

for chunk in client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "用一句话介绍杭州"}],
        stream=True):
    print(chunk.choices[0].delta.content or "", end="")
```

工具调用：

```python
resp = client.chat.completions.create(
    model="deepseek-v4-pro",
    messages=[{"role": "user", "content": "查一下杭州天气"}],
    tools=[{"type": "function", "function": {
        "name": "get_weather",
        "description": "查询城市天气",
        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}],
)
print(resp.choices[0].message.tool_calls)
```

---

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/chat/completions` | 主接口，支持 `stream`、`tools`、`extra_body.search=true`（联网搜索） |
| GET | `/v1/models` | 模型列表 |
| GET | `/v1/history?limit=50&full=1` | 防撤回存档（`id` 参数可看单条全文） |
| GET | `/v1/pool` | 会话池状态 |
| GET | `/healthz` | 存活探针 |

---

## 一个必须知道的坑：HttpURLConnection 在 Android 上会改写请求

同样的代码在 JVM 上跑得通、装到手机上却被上游判定为非法请求（只回 `😩抱歉，出了点小问题`）。
原因是 Android 的 `HttpURLConnection` 实现会：

* 合并 `Set-Cookie`（多个 cookie 变成一行，导致 cookie 解析残缺）；
* 丢弃值为空的请求头（`landingpageswitch: ""`）；
* 强制 `gzip` 并覆盖 `Accept-Encoding`。

所以本项目的网络层是**手写的 HTTP/1.1 客户端**（`core/Http1Client.java`：`SSLSocket` + SNI +
chunked 解码 + 原始 `Set-Cookie` 逐条解析），JVM 与 Android 的行为完全一致，也便于精确控制每一个字节。

---

## 设计说明

* **为什么不走浏览器**：网页端同样走这套 HTTP + SSE，浏览器只是外壳。直连省内存、省电、可并发，也不会因为页面改版而整块失效（改版时只需更新 `WenxinSession`）。
* **限流规避**：`SessionPool` 默认 3 个匿名身份，请求按轮询分配；同一身份串行；上游返回 `tokenFail` / 空回答时自动换身份重试一次；高频失败会重建身份（重新预热）。
* **防撤回**：分两层。① 流式边生成边下发，客户端已收到的内容永不回滚（上游若中途"缩短/重写"，`showType=replace` 且更短的片段会被忽略，只记 `withdraw` 标记）；② 完整回答落本地 `history.jsonl`，即使 App 重启也能查。
* **工具协议**：文心会把 `<...>` 类标签当 HTML 过滤，所以协议标记用 `@@tool_calls@@ ... @@end@@`；解析器同时兼容 `<tool_calls>`、```json 围栏、裸 JSON 数组等写法。

---

## 目录

```
app/src/main/java/com/hiweny/wenxinapi/
├── core/                  # 与平台无关，可在纯 JVM 跑单测
│   ├── WenxinSession.java # 预热 + 对话（协议核心）
│   ├── SessionPool.java   # 匿名身份池 / 轮换 / 重建
│   ├── PromptBuilder.java # OpenAI messages → 文心 prompt（含工具协议注入）
│   ├── ToolBridge.java    # 工具调用解析 + 流式防泄漏闸门
│   ├── Json.java / Tools.java / Config.java / HistoryStore.java
├── server/
│   ├── HttpServer.java    # 极简 HTTP/1.1（chunked，SSE）
│   └── ApiRouter.java     # OpenAI 兼容路由
├── ApiService.java        # 前台服务（保活）
├── FloatingBubble.java    # 悬浮球
└── MainActivity.java      # 深色控制台
tools/                     # CI 冒烟测试入口（真实接口）
```

---

## 免责声明

仅供个人学习与研究。请遵守百度相关服务条款，不要用于商业或大规模滥用场景。
本项目不含任何账号凭据，所有请求均为匿名身份，行为与你自己打开网页对话一致。
