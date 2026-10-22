#!/usr/bin/env python3
"""端到端验收：对着运行中的服务（本地 JVM 或 APK）跑真实对话/思考/识图/工具/多轮。

用法： python3 tools/e2e_api.py http://127.0.0.1:8787 sk-wenxin
"""
import base64
import json
import sys
import time
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787").rstrip("/")
KEY = sys.argv[2] if len(sys.argv) > 2 else "sk-wenxin"
FAILED = []


def call(path, body=None, stream=False, timeout=180):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + KEY)
    resp = urllib.request.urlopen(req, timeout=timeout)
    if stream:
        return resp
    return json.loads(resp.read().decode())


def chat(messages, model="deepseek-v4-pro", stream=False, tools=None, extra=None):
    body = {"model": model, "messages": messages, "stream": stream}
    if tools:
        body["tools"] = tools
    if extra:
        body.update(extra)
    if not stream:
        d = call("/v1/chat/completions", body)
        m = d["choices"][0]["message"]
        return m.get("content") or "", m.get("tool_calls") or [], m.get("reasoning_content") or ""
    r = call("/v1/chat/completions", body, stream=True)
    text, think = [], []
    for raw in r:
        line = raw.decode("utf-8", "ignore").strip()
        if not line.startswith("data: ") or line == "data: [DONE]":
            continue
        try:
            d = json.loads(line[6:])
        except Exception:
            continue
        delta = (d.get("choices") or [{}])[0].get("delta") or {}
        if delta.get("content"):
            text.append(delta["content"])
        if delta.get("reasoning_content"):
            think.append(delta["reasoning_content"])
    return "".join(text), [], "".join(think)


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  → " + str(detail)[:180]) if detail else ""))
    if not ok:
        FAILED.append(name)


def png_data_url():
    """纯 Python 生成一张 32x32 左红右蓝的 PNG（不依赖 Pillow）"""
    import struct
    import zlib
    w = h = 32
    rows = b""
    for _y in range(h):
        row = b"\x00"
        for x in range(w):
            row += b"\xff\x30\x30" if x < w // 2 else b"\x30\x30\xff"
        rows += row

    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xffffffff)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(rows))
           + chunk(b"IEND", b""))
    return "data:image/png;base64," + base64.b64encode(png).decode()


def main():
    print("target:", BASE)
    h = call("/healthz")
    check("healthz", h.get("ok") is True, h)

    # 1) 无状态多轮：三次独立提问必须有三个不同答案（防"永远回同一句"）
    qs = ["1+1等于几？只回答数字", "2+2等于几？只回答数字", "8+9等于几？只回答数字"]
    ans = []
    t0 = time.time()
    for q in qs:
        txt, _, _ = chat([{"role": "user", "content": q}], extra={"thinking": False})
        ans.append(txt.strip())
    check("多轮无状态（答案互不相同）", len(set(ans)) == 3, ans)
    check("算术正确", "2" in ans[0] and "4" in ans[1] and "17" in ans[2], ans)
    print("    3 次平均耗时 %.1fs" % ((time.time() - t0) / 3))

    # 2) 上下文（每次全量发送时模型应记得前文）
    msgs = [{"role": "user", "content": "我叫小明，职业是程序员"},
            {"role": "assistant", "content": "好的，小明，记住了。"},
            {"role": "user", "content": "我叫什么？职业是什么？只回答：名字+职业"}]
    txt, _, _ = chat(msgs, model="deepseek-v4-flash", extra={"thinking": False})
    check("上下文全量传递", "小明" in txt, txt[:80])

    # 3) 思考默认开启
    txt, _, think = chat([{"role": "user", "content": "13乘17等于多少？"}], stream=True)
    check("深度思考（reasoning_content）", len(think) > 10, "think=%d字 text=%d字" % (len(think), len(txt)))
    check("思考题答案正确", "221" in txt, txt[:80])

    # 4) 关闭思考
    txt2, _, think2 = chat([{"role": "user", "content": "13乘17等于多少？"}], stream=True,
                           extra={"thinking": False})
    check("可关闭思考", len(think2) == 0 and "221" in txt2, "think=%d" % len(think2))

    # 5) 识图
    url = png_data_url()
    txt, _, _ = chat([{"role": "user", "content": [
        {"type": "text", "text": "这张图左边和右边分别是什么颜色？一句话"},
        {"type": "image_url", "image_url": {"url": url}}]}], extra={"thinking": False})
    low = txt.lower()
    check("识图（红/蓝）", ("红" in txt or "red" in low) and ("蓝" in txt or "blue" in low), txt[:120])

    # 6) 工具调用
    tools = [{"type": "function", "function": {
        "name": "get_weather", "description": "查询城市天气",
        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}]
    _, calls, _ = chat([{"role": "user", "content": "杭州天气怎么样"}], tools=tools)
    ok = bool(calls) and calls[0]["function"]["name"] == "get_weather" and "杭州" in calls[0]["function"]["arguments"]
    check("工具调用", ok, calls)

    # 7) 工具结果回传
    if ok:
        msgs = [{"role": "user", "content": "杭州天气怎么样"},
                {"role": "assistant", "content": None, "tool_calls": calls},
                {"role": "tool", "tool_call_id": calls[0].get("id", "call_1"), "name": "get_weather",
                 "content": "杭州 25℃ 晴"}]
        txt, _, _ = chat(msgs, model="deepseek-v4-flash", extra={"thinking": False})
        check("工具结果回传", len(txt) > 5, txt[:80])

    # 8) 联网搜索开关（只验证接口不报错；内容受上游实验影响）
    txt, _, _ = chat([{"role": "user", "content": "今天的日期是几月几日"}], extra={"search": True, "thinking": False})
    check("联网搜索开关", len(txt) > 0, txt[:80])

    # 9) 防撤回存档
    h2 = call("/v1/history?limit=3")
    check("防撤回存档", isinstance(h2.get("data"), list) and len(h2["data"]) > 0,
          "records=%d" % len(h2.get("data") or []))

    print("\n==== 结果：%d 项失败 ====" % len(FAILED))
    if FAILED:
        print("失败项:", FAILED)
        sys.exit(1)


if __name__ == "__main__":
    main()
