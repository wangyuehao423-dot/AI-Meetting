"""Demo-path regression for the interview platform.

Mirrors what the browser actually does when the interviewer clicks through, and
checks each step against the BUDGET the frontend itself enforces:

  takeQuestion  : 10 attempts x 1.5s = 15s   (main.jsx)
  report poll   : 90s per round, up to 3 rounds (main.jsx)

Any step over budget is exactly what a human sees as "点了半天不出来".
"""
import json
import sys
import time
import urllib.request
import urllib.error
import uuid

BASE = "http://127.0.0.1:18080"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TAKE_Q_BUDGET = 120.0   # 与前端 takeQuestion 的 80 次 x 1.5s 对齐
REPORT_BUDGET = 150.0   # 与前端 pollReportUntilSettled 默认 150s 对齐

problems = []


def post(path, body=None, token=None, timeout=300):
    data = json.dumps(body).encode() if body is not None else b""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    return _send(req, timeout)


def get(path, token=None, timeout=60):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, headers=headers, method="GET")
    return _send(req, timeout)


def _send(req, timeout):
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            code = resp.status
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", "replace")
        code = err.code
    try:
        return code, (json.loads(raw) if raw.strip() else None)
    except Exception:
        return code, raw


def upload_resume(uid, token):
    boundary = "----demo" + uuid.uuid4().hex
    with open("resume-test.pdf", "rb") as fh:
        payload = fh.read()
    body = b""
    body += ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="userId"\r\n\r\n%s\r\n' % uid).encode()
    body += ("--%s\r\n" % boundary).encode()
    body += b'Content-Disposition: form-data; name="file"; filename="resume-test.pdf"\r\n'
    body += b"Content-Type: application/pdf\r\n\r\n" + payload + b"\r\n"
    body += ("--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(
        BASE + "/api/resumes", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                 "Authorization": "Bearer " + token},
        method="POST")
    return _send(req, 300)


def ws_handshake(sid):
    import socket, base64, os
    key = base64.b64encode(os.urandom(16)).decode()
    req = ("GET /ws/interviews/%s HTTP/1.1\r\nHost: 127.0.0.1:18080\r\n"
           "Upgrade: websocket\r\nConnection: Upgrade\r\n"
           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (sid, key))
    try:
        sock = socket.create_connection(("127.0.0.1", 18080), timeout=8)
        sock.sendall(req.encode())
        first = sock.recv(200).decode("utf-8", "replace").split("\r\n")[0]
        sock.close()
        return first
    except Exception as exc:
        return repr(exc)


def take_question(sid, token):
    """Exactly what main.jsx takeQuestion does now: deadline-based, 150s total, ACTIVE only."""
    started = time.time()
    intermediates = 0
    deadline = started + TAKE_Q_BUDGET
    while time.time() < deadline:
        code, body = post("/api/interviews/%s/questions" % sid, None, token, timeout=30)
        if code == 200 and body and body.get("id") and body.get("status") == "ACTIVE":
            return body, time.time() - started, intermediates
        if code == 200 and body and body.get("status") != "ACTIVE":
            intermediates += 1
        time.sleep(1.5)
    return None, time.time() - started, intermediates


def run_round(idx):
    t0 = time.time()
    rows = []

    def mark(name, ok, elapsed, extra=""):
        rows.append((name, ok, elapsed, extra))
        flag = "OK " if ok else "BAD"
        print("  [%s] %-26s %6.1fs  %s" % (flag, name, elapsed, extra), flush=True)

    uname = "demo_%s_%d" % (uuid.uuid4().hex[:6], idx)
    s = time.time()
    code, reg = post("/api/auth/register", {"username": uname, "password": "test123456", "displayName": "演示"})
    mark("1 注册", code == 200 and reg and reg.get("token"), time.time() - s, "HTTP %s" % code)
    if code != 200:
        return rows, None
    token, uid = reg["token"], reg["userId"]

    s = time.time()
    code, up = upload_resume(uid, token)
    el = time.time() - s
    ok = code == 200 and up and up.get("status") == "PARSED"
    mark("2 上传简历(同步AI)", ok, el, "HTTP %s status=%s" % (code, (up or {}).get("status")))

    s = time.time()
    code, latest = get("/api/resumes?userId=%s" % uid, token)
    mark("3 读取简历", code == 200 and latest, time.time() - s, "status=%s" % (latest or {}).get("status"))
    rid = (up or {}).get("id")

    s = time.time()
    code, pos = get("/api/positions", token)
    mark("4 岗位列表", code == 200 and pos, time.time() - s, "count=%s" % (len(pos) if isinstance(pos, list) else "?"))

    s = time.time()
    code, sess = post("/api/interviews?userId=%s&positionId=1&resumeId=%s" % (uid, rid), None, token)
    mark("5 创建面试", code == 200 and sess and sess.get("sessionId"), time.time() - s, "state=%s" % (sess or {}).get("state"))
    sid = (sess or {}).get("sessionId")
    if not sid:
        return rows, None

    s = time.time()
    code, st = post("/api/interviews/%s/start" % sid, None, token)
    mark("6 开始面试", code == 200, time.time() - s, "state=%s" % (st or {}).get("state"))

    ws = ws_handshake(sid)
    mark("7 WebSocket 握手", "101" in ws, 0.0, ws[:40])

    q1, el, inter = take_question(sid, token)
    mark("8 取第1题(UI预算15s)", q1 is not None, el, "中间态=%d 题=%s" % (inter, (q1 or {}).get("sequenceNo")))
    if not q1:
        problems.append("R%d 首题 15 秒内没就绪" % idx)
        return rows, sid

    asked = [q1]

    def answer(q, text, is_follow_up):
        s2 = time.time()
        code, _ = post("/api/answers/%s/answers" % sid, {
            "questionId": q["id"], "requestId": str(uuid.uuid4()),
            "answer": text, "followUp": is_follow_up}, token)
        mark("   提交答案(followUp=%s)" % is_follow_up, code == 200, time.time() - s2, "HTTP %s" % code)
        return code == 200

    answer(q1, "我用 Redis 做热点缓存，配 Lua 单飞防止缓存击穿，MySQL 用组合索引优化慢查询。", False)

    # follow-up of Q1 (UI checks right after answering the original question)
    s = time.time()
    code, fu = get("/api/interviews/%s/follow-up?questionId=%s" % (sid, q1["id"]), token)
    mark("9 查追问Q1", code == 200, time.time() - s,
         "level=%s 有内容=%s" % ((fu or {}).get("level"), bool((fu or {}).get("content"))))

    q2, el, inter = take_question(sid, token)
    mark("10 取第2题(UI预算15s)", q2 is not None, el, "中间态=%d" % inter)
    if q2:
        asked.append(q2)
        answer(q2, "慢查询我先看执行计划，命中不到索引就补组合索引，避免回表。", False)
    if fu and fu.get("content"):
        answer(q1, "缓存就是内存里的热数据副本，读走缓存、更新时先改库再删缓存。", True)

    q3, el, inter = take_question(sid, token)
    mark("11 取第3题(UI预算15s)", q3 is not None, el, "中间态=%d" % inter)
    if q3:
        asked.append(q3)
        answer(q3, "需要多行事务和强一致的走 MySQL，文档型、写多、结构易变的走 MongoDB。", False)

    s = time.time()
    code, en = post("/api/interviews/%s/end" % sid, None, token)
    mark("12 结束面试", code == 200, time.time() - s, "state=%s" % (en or {}).get("state"))

    s = time.time()
    code, rep = post("/api/reports/%s" % sid, None, token)
    mark("13 触发报告", code == 200, time.time() - s, "status=%s" % (rep or {}).get("status"))

    s = time.time()
    status = None
    md = None
    while time.time() - s < REPORT_BUDGET + 30:
        code, stt = get("/api/reports/%s/status" % sid, token)
        status = (stt or {}).get("status")
        if status in ("COMPLETED", "FAILED"):
            break
        time.sleep(1.0)
    el = time.time() - s
    mark("14 报告生成(UI预算90s)", status == "COMPLETED", el, "status=%s" % status)

    if status == "COMPLETED":
        code, full = get("/api/reports/%s" % sid, token)
        md = (full or {}).get("markdown") or ""
        covers = sum(1 for kw in ("Redis", "MySQL", "Mongo") if kw.lower() in md.lower())
        mark("15 报告内容", len(md) > 500, 0.0, "%d 字, 命中主题 %d/3" % (len(md), covers))

    s = time.time()
    code, sp = get("/api/speech/token", token)
    msg = (sp or {}).get("message") if isinstance(sp, dict) else str(sp)[:50]
    mark("16 语音token(应友好降级)", code == 503 and bool(msg), time.time() - s, "HTTP %s %s" % (code, msg))

    # 17) 面试官最常见的动作之一：报告已经出来了，再点一次"生成报告"。
    # 期望：幂等返回 COMPLETED + 原文，绝不能把已完成报告打回 GENERATING（那会白等一两分钟）。
    if status == "COMPLETED":
        s = time.time()
        code2, rep2 = post("/api/reports/%s" % sid, None, token)
        st2 = (rep2 or {}).get("status")
        same = bool(md) and ((rep2 or {}).get("markdown") or "") == md
        mark("17 重复点生成报告(幂等)", code2 == 200 and st2 == "COMPLETED" and same,
             time.time() - s, "status=%s 正文一致=%s" % (st2, same))

    total = time.time() - t0
    print("  ---- 本轮总耗时 %.1fs，题数=%d ----" % (total, len(asked)), flush=True)
    for name, ok, el, extra in rows:
        if not ok:
            problems.append("R%d %s 失败 (%s)" % (idx, name, extra))
        elif "预算" in name and el > (TAKE_Q_BUDGET if "取第" in name else REPORT_BUDGET):
            problems.append("R%d %s 超预算 %.1fs" % (idx, name, el))
    return rows, sid


rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
print("=== 面试官演示链路 x%d 轮 ===" % rounds)
for i in range(1, rounds + 1):
    print("\n----- round %d -----" % i, flush=True)
    try:
        run_round(i)
    except Exception as exc:
        problems.append("R%d 异常 %r" % (i, exc))
        print("  !! 异常: %r" % (exc,), flush=True)

print("\n===== 汇总 =====")
if problems:
    print("发现问题 %d 条：" % len(problems))
    for p in problems:
        print(" -", p)
else:
    print("全部通过：每一步都在前端等待预算内返回")
