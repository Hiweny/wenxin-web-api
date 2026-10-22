# 文心助手手机版协议记录（2026-10 实测）

> 本文记录逆向过程与实测结论，供维护 `WenxinSession` 时对照。所有结论均在真实环境中验证过。

## 1. 入口

```
GET https://wenxin.baidu.com/        （手机 UA）
```

HTML 内嵌：

```html
<script type="application/json" name="aiTabFrameBaseData">{
  "token": "8b179dc6",           <!-- 8 位 hex，页面级身份 -->
  "lid": "8666324498882526375",  <!-- 19 位数字，页面级会话 id -->
  "userInfo": {"isUserLogin": 0},
  "chatParams": {"sse_host": "https://chat.baidu.com", ...},
  "modelList": [ ... ],          <!-- 模型清单 -->
  "phoneParams": {"reqInfo": {"reqURL": "https://chat.baidu.com/aichat/api/conversation?phone=1"}}
}</script>
```

响应同时下发 cookie：`BAIDUID`、`H_WISE_SIDS`（后续可能再有 `BA_HECTOR`、`ZFY`）。

**实测**：不登录也能拿到完整身份，`isUserLogin=0` 不影响对话。

## 2. 模型清单（页面 modelList 原文）

| id | 展示名 | 说明 | 实测上游 modelName |
| --- | --- | --- | --- |
| `smartMode` | 智能模式 | 智能识别需求调度最佳模型 | `AskbackHintA3B` |
| `ERINE-5.1` | 文心5.1 | 文心最新模型 | `ANSWER_EB4P5` |
| `DeepSeek-V4` | DS-V4 Pro | 编程更强 推理更优 | `DEEPSEEK_V4` |
| `DeepSeek-V4-Flash` | DS-V4 Flash | 响应更快 | `DEEPSEEK_V4_FLASH` |
| `DeepSeek` | DS-R1 | 深度思考 | `AskbackHintA3B`（走智能调度） |

## 3. 对话请求

```
POST https://chat.baidu.com/aichat/api/conversation
```

**必带 header**

```
Content-Type: application/json
Accept: text/event-stream
Origin: https://chat.baidu.com
Referer: https://wenxin.baidu.com/
source: wise_csaitab          ← 手机版；PC 版是 pc_csaitab（payload 结构不同）
isDeepseek: 1
personifiedswitch: 0
landingpageswitch: (空)
User-Agent: 手机 UA
Cookie: BAIDUID=...; H_WISE_SIDS=...
x-chat-message: query:<urlencoded>,
                anti_ext:{"inputT":1500},
                enter_type:chat_url,re_rank:1,modelName:<模型名>,sa:bkb
```

**关键字段 `chat_token`**

```
chat_token = base64( token | md5(query) | nowMs | lid ) + "-" + lid + "-3"
```

* `token` / `lid` 来自首页 HTML；
* `md5(query)` 必须与 `message.query[0].data.text.query` **逐字节一致**（不一致 → `hints.type = tokenFail`）；
* 时间戳只用于展示，实测过期不校验；
* 伪造随机 token/lid + 真 cookie → `tokenFail`，说明服务端会校验身份对（必须来自同一次预热）。

**payload 骨架**

```json
{
  "message": {
    "inputMethod": "chat_search",
    "isRebuild": false,
    "content": {"query":"","agentInfo":{"agent_id":[""],"params":"{\"agt_rk\":1,\"agt_sess_cnt\":1}"},"agentInfoList":[],"qtype":0},
    "searchInfo": {
      "re_rank": "1", "sa": "bkb", "enter_type": "chat_url",
      "chatParams": {"setype":"csaitab","chat_samples":"WISE_NEW_CSAITAB","chat_token":"<见上>","scene":""},
      "isPrivateChat": false,
      "usedModel": {"modelName":"DeepSeek-V4","modelFunction":{"thinkMode":"0","deepSearch":"0","internetSearch":"0"}},
      "landingPage":"aitab","isInnovate":2,"showMindMap":false,
      "deepDecisionInfo": {"isDeepDecision": 0}
    },
    "source": "wise_csaitab",
    "query": [{"type":"TEXT","data":{"text":{"query":"<问题>","text_type":""}}}],
    "anti_ext": {"inputT": 1500}
  },
  "fromNa": true,
  "rank": 1
}
```

## 4. 流式响应

`Content-Type: text/event-stream`，事件：`basedata` / `ping` / `message`。

```
event:message
data:{"status":0,"qid":"...","pkgId":"<msgId>_<seq>","sessionId":"...",
  "data":{"message":{
    "metaData":{"state":"generating-resp","endTurn":false,
                "speedInfo":{"costs":{"promptTokenLabel":3852,"completionTokenLabel":280},
                             "labels":{"modelName":"DEEPSEEK_V4"}}},
    "content":{"generator":{
        "component":"markdown-yiyan",
        "text":"",
        "data":{"value":"水墨"},        ← 正文增量（拼接即可）
        "usedModel":{"modelName":"DeepSeek-V4"}
    }}}}}
```

* **正文**：`generator.component` 以 `markdown` 开头，内容在 `generator.data.value`（增量，逐片拼接）。
* **思考链**：`generator.component == "thinkingSteps"`，内容在 `generator.data.reasoningContentArr[0]`（**全量**，取最长者）。
* 其它组件（`questionClosely` / `input` / 搜索卡片）不是正文，需要忽略。
* **结束**：`metaData.state == "generate-complete"` 且 `metaData.endTurn == true`。
* **用量**：`metaData.speedInfo.costs.promptTokenLabel / completionTokenLabel`。
* **撤回/截断**：`generator.showType == "replace"` 且新文本比已输出的短 → 上游在重写内容。本实现保留已输出部分并记 `withdraw` 标记。

## 5. 失败模式

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `hints.parts[].type = tokenFail` + "抱歉，出了点小问题" | chat_token 里的 md5 与 query 不符 / token-lid 不匹配 | 重新预热或修正 md5 |
| `"😩抱歉，出了点小问题，请稍后重试"` 且无 tokenFail | anti_ext / x-chat-message 与 body 不一致 | 同步 header 与 body |
| 200 但只有 basedata + ping | cookie 缺失 | 带上完整 cookie |
| 正文为空但 tokens 有值 | 只解析了 `generator.text`，忽略了 `data.value` | 按 component 解析 |

## 6. 其它端点（备用）

```
GET  /aichat/api/messages/list?tk=<base64(token|ts|lid)>-lid&from=aisearch_home   POST {"page":1,"pageSize":20}
GET  /aichat/api/messages/conf?tk=...
GET  /csaitab/history/list?from=chat_search&token=...
GET  /aichat/longtask/list?taskType=1&token=...
GET  /aichat/api/aitabserver?ctl=guidewords
```

这些端点同样只需 `token|lid` 组合的 `tk`（不带 md5、不带 `-3` 后缀），可用于读取历史（防撤回的补充手段）。

## 7. 限制

* 单次请求是**无状态**的：服务端不保存上下文，多轮必须由客户端拼进 prompt。
* 同一匿名身份高频请求会被风控（表现为持续"抱歉"），因此需要会话池 + 最小请求间隔。
* 回答长度受模型自身限制，`max_tokens` 无法透传。
* 网页端没有原生 function calling，工具调用依赖 prompt 协议（本仓库用 `@@tool_calls@@`，因为 `<...>` 会被 HTML 过滤）。
