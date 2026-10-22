"""文心手机版 免登录 API 客户端（纯 Python，无浏览器）"""
import json, re, time, base64, hashlib, http.cookiejar, urllib.request, urllib.parse

UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

class WenxinAnon:
    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
        self.token = self.lid = None

    def warm(self):
        req = urllib.request.Request("https://wenxin.baidu.com/", headers={"User-Agent": UA, "Accept": "text/html"})
        html = self.opener.open(req, timeout=30).read().decode("utf-8", "ignore")
        m = re.search(r'name="aiTabFrameBaseData">(.*?)</script>', html, re.S)
        d = json.loads(m.group(1))
        self.token, self.lid = d["token"], d["lid"]
        return d

    def cookie_str(self):
        return "; ".join(f"{c.name}={c.value}" for c in self.cj)

    def chat_token(self, query):
        s = f"{self.token}|{hashlib.md5(query.encode()).hexdigest()}|{int(time.time()*1000)}|{self.lid}"
        return base64.b64encode(s.encode()).decode() + f"-{self.lid}-3"

    def build(self, query, model="DeepSeek-V4", search="0"):
        return {
            "message": {
                "inputMethod": "chat_search", "isRebuild": False,
                "content": {"query": "", "agentInfo": {"agent_id": [""], "params": '{"agt_rk":1,"agt_sess_cnt":1}'},
                            "agentInfoList": [], "qtype": 0},
                "searchInfo": {
                    "srcid": "", "order": "", "tplname": "", "dqaKey": "", "re_rank": "1", "ori_lid": "",
                    "sa": "bkb", "enter_type": "chat_url",
                    "chatParams": {"setype": "csaitab", "chat_samples": "WISE_NEW_CSAITAB",
                                   "chat_token": self.chat_token(query), "scene": ""},
                    "isPrivateChat": False,
                    "usedModel": {"modelName": model, "modelFunction": {"thinkMode": "0", "deepSearch": "0", "internetSearch": search}},
                    "landingPageSwitch": "", "landingPage": "aitab", "ecomFrom": "", "hasLocPermission": "",
                    "isInnovate": 2, "applid": "", "a_lid": "", "showMindMap": False,
                    "deepDecisionInfo": {"isDeepDecision": 0}},
                "from": "", "source": "wise_csaitab",
                "query": [{"type": "TEXT", "data": {"text": {"query": query, "text_type": ""}}}],
                "anti_ext": {"inputT": 1500},
            },
            "fromNa": True, "rank": 1,
        }

    def stream(self, body, timeout=180, headers_extra=None):
        h = {
            "User-Agent": UA, "Accept": "text/event-stream", "Content-Type": "application/json",
            "Origin": "https://chat.baidu.com", "Referer": "https://wenxin.baidu.com/",
            "source": "wise_csaitab", "isDeepseek": "1", "personifiedswitch": "0",
            "landingpageswitch": "", "accept-language": "zh-CN",
            "x-chat-message": self._chat_msg_header(body),
            "cookie": self.cookie_str(),
        }
        if headers_extra: h.update(headers_extra)
        data = json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request("https://chat.baidu.com/aichat/api/conversation", data=data, headers=h, method="POST")
        return self.opener.open(req, timeout=timeout)

    def _chat_msg_header(self, body):
        m = body["message"]
        q = m["query"][0]["data"]["text"]["query"]
        si = m["searchInfo"]
        return ("query:" + urllib.parse.quote(q)
                + ",anti_ext:" + urllib.parse.quote(json.dumps(m["anti_ext"], separators=(",", ":")))
                + ",enter_type:" + si["enter_type"] + ",re_rank:" + si["re_rank"]
                + ",modelName:" + si["usedModel"]["modelName"] + ",sa:" + si["sa"])


def parse_sse_stream(resp, on_delta=None, on_think=None, on_event=None):
    """解析文心 SSE：正文在 generator.data.value（markdown-* 组件，增量），思考在 thinkingSteps"""
    import re as _re
    res = {"text": "", "think": "", "model": "", "tokens": {}, "finished": False, "error": None, "events": []}
    raw = b""
    while True:
        chunk = resp.read(4096)
        if not chunk:
            break
        raw += chunk
        # 增量解析：按事件块切分，保留未完成尾部
        blocks = _re.split(rb"\n{2,}", raw)
        raw = blocks.pop() if blocks else b""
        for blk in blocks:
            txt = blk.decode("utf-8", "ignore").strip()
            if not txt:
                continue
            ev = data = None
            for line in txt.split("\n"):
                line = line.strip("\r")
                if line.startswith("event:"): ev = line[6:].strip()
                elif line.startswith("data:"): data = line[5:].strip()
            if not data:
                continue
            try: j = json.loads(data)
            except Exception: continue
            if ev == "basedata":
                res["events"].append("basedata")
                if on_event: on_event("basedata", j)
                continue
            if ev != "message":
                continue
            msg = j.get("data", {}).get("message", {})
            meta = msg.get("metaData", {}) or {}
            content = msg.get("content", {}) or {}
            g = content.get("generator")
            if g:
                comp = g.get("component") or ""
                d = g.get("data") or {}
                piece = g.get("text") or ""
                if not piece and isinstance(d.get("value"), str) and comp.startswith("markdown"):
                    piece = d["value"]
                if piece and (comp.startswith("markdown") or g.get("text")):
                    st = g.get("showType") or "append"
                    if st == "replace":
                        res["text"] = piece
                    else:
                        res["text"] += piece
                    if on_delta: on_delta(piece)
                rc = d.get("reasoningContentArr")
                if isinstance(rc, list) and rc and isinstance(rc[0], str) and rc[0]:
                    if len(rc[0]) > len(res["think"]):
                        add = rc[0][len(res["think"]):]
                        res["think"] = rc[0]
                        if on_think: on_think(add)
                if isinstance(d.get("usedModel"), str): res["model"] = d["usedModel"]
                elif isinstance(d.get("usedModel"), dict) and d["usedModel"].get("modelName"):
                    res["model"] = d["usedModel"]["modelName"]
                if g.get("modelInfo", {}).get("model_type"): res["model"] = res["model"] or g["modelInfo"]["model_type"]
                hints = (content.get("hints") or {}).get("parts") or []
                for p in hints:
                    if "抱歉" in (p.get("text") or "") or p.get("type") in ("tokenFail",):
                        res["error"] = p.get("text") or p.get("type")
            costs = (meta.get("speedInfo") or {}).get("costs") or {}
            if costs.get("promptTokenLabel") is not None:
                res["tokens"] = {"prompt": costs.get("promptTokenLabel"), "completion": costs.get("completionTokenLabel")}
                labels = (meta.get("speedInfo") or {}).get("labels") or {}
                if labels.get("modelName"): res["model"] = labels["modelName"]
            if meta.get("state") == "generate-complete" and meta.get("endTurn") is True:
                res["finished"] = True
    return res


if __name__ == "__main__":
    import sys, time as _t
    c = WenxinAnon(); bd = c.warm()
    print("warm:", c.token, c.lid)
    q = sys.argv[1] if len(sys.argv) > 1 else "用一句话介绍你自己"
    model = sys.argv[2] if len(sys.argv) > 2 else "DeepSeek-V4"
    t0 = _t.time()
    out = parse_sse_stream(c.stream(c.build(q, model=model)))
    print(f"[{_t.time()-t0:.1f}s] model={out['model']} tokens={out['tokens']} err={out['error']} finished={out['finished']}")
    print("=== TEXT ==="); print(out["text"][:1200])
    if out["think"]: print("=== THINK ==="); print(out["think"][:400])
