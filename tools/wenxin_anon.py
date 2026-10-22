#!/usr/bin/env python3
"""
文心助手手机版（免登录）纯 Python 客户端 —— 协议参考实现。

不依赖浏览器，只用标准库。用于：跨端复用（桌面 / 服务器 / 脚本）与协议验证。
Android APK 里的 Java 实现与本文件逻辑一一对应。

用法：
    c = WenxinAnon()
    c.warm()                                    # 拿匿名身份（token / lid / cookie）
    r = c.chat("你好", model="DeepSeek-V4")      # 非流式
    c.chat("你好", model="DeepSeek-V4", on_delta=print)   # 流式

关键协议（2026-10 实测）：
  * 身份：GET https://wenxin.baidu.com/ → HTML 内嵌 aiTabFrameBaseData 的 token(8hex) / lid(19位)
  * 对话：POST https://chat.baidu.com/aichat/api/conversation
          chat_token = base64(token|md5(query)|nowMs|lid) + "-" + lid + "-3"
          header x-chat-message 必须同时带 query / anti_ext / modelName
  * SSE：正文在 generator.component 以 markdown 开头时的 generator.data.value（增量拼接）
         思考在 thinkingSteps 的 reasoningContentArr[0]（也是增量片段）
  * 识图：query 里加 {"type":"IMAGE","data":{"image":{"image_url":<公网URL或data:URL>,"upload_flag":1}}}
"""
import base64
import hashlib
import http.cookiejar
import json
import re
import threading
import time
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
      "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")

HOME_URL = "https://wenxin.baidu.com/"
CONV_URL = "https://chat.baidu.com/aichat/api/conversation"

MODEL_MAP = {
    "deepseek-v4-pro": "DeepSeek-V4",
    "deepseek-v4-flash": "DeepSeek-V4-Flash",
    "deepseek-r1": "DeepSeek-R1",
    "ernie-5.1": "ERINE-5.1",
    "smartmode": "smartMode",
}


def map_model(name):
    if not name:
        return "DeepSeek-V4"
    n = name.lower()
    if "flash" in n:
        return "DeepSeek-V4-Flash"
    if "v4" in n:
        return "DeepSeek-V4"
    if "r1" in n or "reason" in n or "think" in n:
        return "DeepSeek-R1"
    if "ernie" in n or "wenxin" in n:
        return "ERINE-5.1"
    if "smart" in n or "auto" in n:
        return "smartMode"
    return MODEL_MAP.get(n, "DeepSeek-V4")


class WenxinAnon:
    """一次性匿名身份（严格无状态：一个身份只服务一轮对话）"""

    def __init__(self, timeout=180):
        self.cj = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
        self.token = ""
        self.lid = ""
        self.timeout = timeout

    # ---------------- 1. 预热 ----------------
    def warm(self):
        last = None
        for attempt in range(3):
            try:
                req = urllib.request.Request(HOME_URL, headers={
                    "User-Agent": UA, "Accept": "text/html,application/xhtml+xml",
                    "Accept-Language": "zh-CN,zh;q=0.9"})
                html = self.opener.open(req, timeout=30).read().decode("utf-8", "ignore")
                m = re.search(r'name="aiTabFrameBaseData">(.*?)</script>', html, re.S)
                if not m:
                    raise RuntimeError("baseData not found (%d bytes)" % len(html))
                d = json.loads(m.group(1))
                self.token, self.lid = d["token"], d["lid"]
                return self
            except Exception as e:  # 网络抖动重试
                last = e
                time.sleep(0.8 * (attempt + 1))
        raise RuntimeError("warm failed: %s" % last)

    def cookie_str(self):
        return "; ".join(f"{c.name}={c.value}" for c in self.cj)

    # ---------------- 2. 构造请求 ----------------
    def chat_token(self, query):
        s = "%s|%s|%d|%s" % (self.token, hashlib.md5(query.encode()).hexdigest(),
                             int(time.time() * 1000), self.lid)
        return base64.b64encode(s.encode()).decode() + "-" + self.lid + "-3"

    def build(self, query, model="DeepSeek-V4", think=True, search=False, images=None):
        internal = map_model(model)
        query_items = []
        for url in (images or [])[:4]:
            query_items.append({"type": "IMAGE", "data": {"image": {"image_url": url, "upload_flag": 1}}})
        query_items.append({"type": "TEXT", "data": {"text": {"query": query, "text_type": ""}}})

        body = {
            "message": {
                "inputMethod": "chat_search",
                "isRebuild": False,
                "content": {
                    "query": "",
                    "agentInfo": {"agent_id": [""], "params": '{"agt_rk":1,"agt_sess_cnt":1}'},
                    "agentInfoList": [], "qtype": 0,
                },
                "searchInfo": {
                    "srcid": "", "order": "", "tplname": "", "dqaKey": "", "re_rank": "1",
                    "ori_lid": "", "sa": "bkb", "enter_type": "chat_url",
                    "chatParams": {"setype": "csaitab", "chat_samples": "WISE_NEW_CSAITAB",
                                   "chat_token": self.chat_token(query), "scene": ""},
                    "isPrivateChat": False,
                    "usedModel": {"modelName": internal, "modelFunction": {
                        "thinkMode": "1" if think else "0",
                        "deepThink": "1" if think else "0",
                        "internetSearch": "1" if search else "0",
                        "deepSearch": "1" if search else "0",
                    }},
                    "landingPageSwitch": "", "landingPage": "aitab", "ecomFrom": "",
                    "hasLocPermission": "", "isInnovate": 2, "applid": "", "a_lid": "",
                    "showMindMap": False, "deepDecisionInfo": {"isDeepDecision": 0},
                },
                "from": "", "source": "wise_csaitab",
                "query": query_items,
                "anti_ext": {"inputT": 1500},
            },
            "fromNa": True, "rank": 1,
        }
        return body

    # ---------------- 3. 发送 ----------------
    def chat(self, query, model="DeepSeek-V4", think=True, search=False, images=None,
             on_delta=None, on_think=None, timeout=None):
        body = self.build(query, model, think, search, images)
        headers = {
            "User-Agent": UA, "Accept": "text/event-stream", "Content-Type": "application/json",
            "Accept-Language": "zh-CN", "Accept-Encoding": "identity",
            "Origin": "https://chat.baidu.com", "Referer": "https://wenxin.baidu.com/",
            "source": "wise_csaitab", "isDeepseek": "1", "personifiedswitch": "0",
            "landingpageswitch": "",
            "x-chat-message": ("query:" + urllib.parse.quote(query)
                               + ",anti_ext:" + urllib.parse.quote('{"inputT":1500}')
                               + ",enter_type:chat_url,re_rank:1,modelName:"
                               + map_model(model) + ",sa:bkb"),
            "Cookie": self.cookie_str(),
        }
        data = json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(CONV_URL, data=data, headers=headers, method="POST")
        resp = self.opener.open(req, timeout=timeout or self.timeout)
        return parse_stream(resp, on_delta=on_delta, on_think=on_think)


def parse_stream(resp, on_delta=None, on_think=None):
    """解析 SSE；返回 {text, think, model, tokens, finished, error, withdrew}"""
    out = {"text": "", "think": "", "model": "", "tokens": {}, "finished": False,
           "error": None, "withdrew": False}
    ev, data = None, []
    for raw in resp:
        line = raw.decode("utf-8", "ignore").rstrip("\r\n")
        if line == "":
            if ev == "message" and data:
                _handle("\n".join(data), out, on_delta, on_think)
            ev, data = None, []
            continue
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].strip())
    return out


def _handle(payload, out, on_delta, on_think):
    try:
        j = json.loads(payload)
    except Exception:
        return
    msg = (j.get("data") or {}).get("message") or {}
    meta = msg.get("metaData") or {}
    content = msg.get("content") or {}
    g = content.get("generator") or {}
    if g:
        comp = g.get("component") or ""
        d = g.get("data") or {}
        piece = g.get("text") or ""
        if not piece and comp.startswith("markdown"):
            piece = d.get("value") or ""
        if piece and (comp.startswith("markdown") or g.get("text")):
            if g.get("showType") == "replace" and len(piece) < len(out["text"]):
                out["withdrew"] = True          # 上游试图缩短 → 保持已输出内容
            else:
                out["text"] += piece
                if on_delta:
                    on_delta(piece)
        rc = d.get("reasoningContentArr") or []
        if rc and isinstance(rc[0], str) and rc[0]:
            cur, piece = out["think"], rc[0]
            if len(piece) > len(cur) and piece.startswith(cur):
                add, out["think"] = piece[len(cur):], piece
            else:
                add, out["think"] = piece, cur + piece
            if on_think:
                on_think(add)
        um = d.get("usedModel")
        if isinstance(um, str) and um:
            out["model"] = um
        elif isinstance(um, dict) and um.get("modelName"):
            out["model"] = um["modelName"]
        for part in ((content.get("hints") or {}).get("parts") or []):
            t = part.get("text") or ""
            if "抱歉" in t or part.get("type") == "tokenFail":
                out["error"] = t
    costs = ((meta.get("speedInfo") or {}).get("costs")) or {}
    if costs.get("promptTokenLabel") is not None:
        out["tokens"] = {"prompt": costs.get("promptTokenLabel"),
                         "completion": costs.get("completionTokenLabel")}
    labels = ((meta.get("speedInfo") or {}).get("labels")) or {}
    if labels.get("modelName"):
        out["model"] = labels["modelName"]
    if meta.get("state") == "generate-complete" and meta.get("endTurn") is True:
        out["finished"] = True


class WenxinPool:
    """预热池：每次取一个全新身份，用完即弃（与 APK 的 SessionPool 同策略）"""

    def __init__(self, size=3):
        self.size = size
        self._ready = []
        self._lock = threading.Lock()
        self._stop = False
        self._t = threading.Thread(target=self._refill, daemon=True)
        self._t.start()

    def _refill(self):
        while not self._stop:
            with self._lock:
                need = len(self._ready) < self.size
            if not need:
                time.sleep(0.2)
                continue
            try:
                c = WenxinAnon().warm()
                with self._lock:
                    if len(self._ready) < self.size:
                        self._ready.append(c)
            except Exception:
                time.sleep(1.0)

    def acquire(self, wait=15):
        deadline = time.time() + wait
        while time.time() < deadline:
            with self._lock:
                if self._ready:
                    return self._ready.pop(0)
            time.sleep(0.1)
        return WenxinAnon().warm()

    def close(self):
        self._stop = True


if __name__ == "__main__":
    import sys
    q = sys.argv[1] if len(sys.argv) > 1 else "用一句话介绍杭州"
    model = sys.argv[2] if len(sys.argv) > 2 else "deepseek-v4-pro"
    c = WenxinAnon().warm()
    t0 = time.time()
    r = c.chat(q, model=model, think=True,
               on_delta=lambda s: print(s, end="", flush=True),
               on_think=lambda s: None)
    print("\n---")
    print("[model]", r["model"], "| tokens", r["tokens"], "| err", r["error"],
          "| %0.1fs" % (time.time() - t0))
    if r["think"]:
        print("[think]", r["think"][:200])
