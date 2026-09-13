#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
无败 · native image 端到端测试

对一个「已经跑起来的服务」(通常是 GraalVM native 可执行文件 target/game)发真实
HTTP 请求,复刻 JVM 侧 RevivalFormatE2ETest / RosterSmokeTest 的核心流程。

存在的理由:JVM 测试跑不到 native 的反射/资源注册缺口——缺 hint 时 JVM 一切正常,
native 才会在启动或首个写操作抛 MissingReflectionRegistrationError。所以 native
产物必须由真实 HTTP 调用验收。

覆盖场景:
  S1 两圈海选(每圈 32 人、不同裁判)→ 复活赛 → 32强 → 16强 → 8强 → 半决赛 → 决赛
  S2 名次段边界同分:不开加赛,赛段直接结算
  S3 晋级线同分:圈内二海 → 三海链式加赛,晋级名额精确
  S4 名单冒烟:默认名单 / 候选 / 装配幂等
  S5 查询与导出(Excel 反射路径、SSE 连接)

用法:
    E2E_BASE_URL=http://127.0.0.1:18080 python3 scripts/native-e2e.py

退出码 0 = 全部通过;非 0 = 有断言失败(会打印失败点)。
"""

import json
import html
import io
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from urllib.parse import urlencode

BASE = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
USERNAME = os.environ.get("E2E_USERNAME", "admin")
PASSWORD = os.environ.get("E2E_PASSWORD", "123456")
TIMEOUT = float(os.environ.get("E2E_TIMEOUT", "60"))

SCENARIOS = []
PASSED = 0


class Fail(AssertionError):
    """断言失败(业务结果不符合预期)。"""


def scenario(fn):
    SCENARIOS.append(fn)
    return fn


def check(cond, msg):
    global PASSED
    if not cond:
        raise Fail(msg)
    PASSED += 1


def eq(actual, expected, msg):
    check(actual == expected, f"{msg}(期望 {expected!r},实际 {actual!r})")


# --------------------------------------------------------------------------- #
# HTTP 基础层
# --------------------------------------------------------------------------- #


def raw(method, path, body=None, token=None, timeout=None, accept=None):
    """发一次请求,返回 (status, headers, payload bytes)。不校验业务 code。"""
    data = None
    # 默认 */*:根路径返回 HTML,写死 application/json 会被内容协商拒成 406
    headers = {"Accept": accept or "*/*"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout or TIMEOUT) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def json_call(method, path, body=None, token=None, params=None):
    """发一次 JSON 请求并返回解析后的完整响应体。"""
    if params:
        path = path + "?" + urlencode(params)
    status, _, payload = raw(method, path, body=body, token=token)
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise Fail(f"{method} {path} 返回非 JSON(status={status}): {payload[:300]!r}")


def sse_open(path, token=None, timeout=5):
    """建立 SSE 连接并返回 (status, headers)。

    拿到响应头即关闭,不读事件流——否则会挂在长连接上等到超时。
    """
    headers = {"Accept": "text/event-stream"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, headers=headers, method="GET")
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers)
    try:
        return resp.status, dict(resp.headers)
    finally:
        resp.close()


def post_file(path, field, filename, content, params=None, token=None):
    """multipart/form-data 上传一个文件(用于选手 Excel 导入)。"""
    boundary = "----e2e" + uuid.uuid4().hex
    chunks = []
    for key, value in (params or {}).items():
        chunks.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
        )
    chunks.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".encode()
    )
    chunks.append(content)
    chunks.append(f"\r\n--{boundary}--\r\n".encode())
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=b"".join(chunks),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            payload = resp.read()
    except urllib.error.HTTPError as e:
        payload = e.read()
    return json.loads(payload.decode("utf-8"))


def make_xlsx(rows):
    """手工拼一个最小 xlsx(内联字符串),用来验证导入侧能读「外部生成」的文件。"""

    def esc(value):
        return (str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))

    body = []
    for r, row in enumerate(rows, start=1):
        cells = "".join(
            f'<c r="{col}{r}" t="inlineStr"><is><t xml:space="preserve">{esc(v)}</t></is></c>'
            for col, v in zip("ABCDEFGH", row)
        )
        body.append(f'<row r="{r}">{cells}</row>')
    parts = {
        "[Content_Types].xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            "</Types>",
        "_rels/.rels":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            "</Relationships>",
        "xl/workbook.xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            "</Relationships>",
        "xl/worksheets/sheet1.xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'<sheetData>{"".join(body)}</sheetData></worksheet>',
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items():
            z.writestr(name, text)
    return buf.getvalue()


def xlsx_sheet_names(payload):
    """从 xlsx 字节里读出工作表名(按写入顺序)。"""
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        xml = z.read("xl/workbook.xml").decode("utf-8")
    # 非 ASCII 会被写成 &#xXXXX; 字符引用,读出来要反转义
    return [html.unescape(name) for name in re.findall(r'<sheet[^>]*name="([^"]+)"', xml)]


class Client:
    """带登录态的 API 客户端。"""

    def __init__(self):
        self.token = None

    def call(self, method, path, body=None, token=None, params=None):
        obj = json_call(method, path, body=body, token=token or self.token, params=params)
        code = obj.get("code")
        if str(code) != "200":
            raise Fail(f"{method} {path} 失败: code={code} msg={obj.get('msg')}")
        return obj.get("data")

    def page(self, method, path, params=None):
        obj = json_call(method, path, token=self.token, params=params)
        code = obj.get("code")
        if str(code) != "200":
            raise Fail(f"{method} {path} 失败: code={code} msg={obj.get('msg')}")
        return obj

    def login(self):
        data = self.call("POST", "/login", {"username": USERNAME, "password": PASSWORD})
        self.token = data["token"]


# --------------------------------------------------------------------------- #
# 业务封装
# --------------------------------------------------------------------------- #


def new_tournament(c, name):
    data = c.call("POST", "/game/tournament", {
        "name": name,
        "status": 0,
        "logicalWidth": 1920,
        "logicalHeight": 1080,
        "themeConfig": "{\"bgColor\":\"#000000\",\"fontFamily\":\"Roboto\"}",
    })
    return data["id"]


def new_referee(c, tid, name):
    # 裁判新增接口返回 R<Void>(不带主键),按赛事+姓名回查拿 ID
    c.call("POST", "/game/referee", {"tournamentId": tid, "name": name})
    rows = c.page("GET", "/game/referee/list",
                  {"tournamentId": tid, "name": name, "pageNum": 1, "pageSize": 50})["data"]
    eq(len(rows), 1, f"新增裁判 {name} 后回查数量异常")
    return rows[0]["id"]


def audition_rule(circles, advance_count, max_score, circle_referees, circle_advances):
    return json.dumps({
        "mode": "AUDITION",
        "circles": circles,
        "advanceCount": advance_count,
        "circleAdvanceCounts": circle_advances,
        "maxScore": max_score,
        "circleRefereeIds": circle_referees,
    }, ensure_ascii=False)


def knockout_rule(teams, advance):
    return json.dumps({
        "mode": "KNOCKOUT",
        "knockout": {
            "singleRound": True,
            "teamsCount": teams,
            "advanceCount": advance,
            "format": "BO1",
            "pairingMode": "SEQUENTIAL",
        },
        "scoring": {"matchMode": "STANDARD"},
    }, ensure_ascii=False)


def new_stage(c, tid, name, mode, start, end, prev_id, rule):
    body = {
        "tournamentId": tid,
        "name": name,
        "stageMode": mode,
        "status": "DRAFT",
        "teamCountStart": start,
        "teamCountEnd": end,
        "isInitialized": 0,
        "ruleConfig": rule,
    }
    if prev_id is not None:
        body["prevStageId"] = prev_id
    return c.call("POST", "/game/stage", body)["id"]


def add_competitors(c, tid, stage_id, count, tag="选手"):
    for i in range(1, count + 1):
        c.call("POST", "/game/competitor", {
            "tournamentId": tid,
            "stageId": stage_id,
            "type": 0,
            "name": f"{tag}{i}",
            "number": str(i),
            "seedRank": i,
        })


def add_rank_group(c, stage_id, source_stage_id, result_filter, zone, rank_start, rank_end):
    """向目标赛段追加一个「来源赛段 + 名次区间」取人组。"""
    group = {
        "sourceStageId": source_stage_id,
        "resultFilter": result_filter,
        "rankStart": rank_start,
        "rankEnd": rank_end,
        "rankByZone": True,
        "fillMode": "AUTO",
        "quota": 0,
    }
    if zone:
        group["zone"] = zone
    return c.call("POST", f"/game/stage/{stage_id}/roster/groups", {
        "sourceStageId": source_stage_id,
        "resultFilter": result_filter,
        "fillMode": "AUTO",
        "groups": [group],
    })


def drop_roster_group(c, stage_id, index):
    c.call("DELETE", f"/game/stage/{stage_id}/roster/groups/{index}")


def roster(c, stage_id):
    return c.call("GET", f"/game/stage/{stage_id}/roster")


def apply_roster(c, stage_id):
    return c.call("POST", f"/game/stage/{stage_id}/roster/apply")


def stage_status(c, stage_id):
    return c.call("GET", f"/game/stage/{stage_id}")["status"]


def start_stage(c, stage_id):
    c.call("PUT", f"/game/stage/{stage_id}/start")


def complete_stage(c, stage_id):
    return c.call("PUT", f"/game/stage/{stage_id}/complete")["status"]


def stage_matches(c, stage_id, exclude_tiebreak=False):
    rows = c.page("GET", "/game/match/list",
                  {"stageId": stage_id, "pageNum": 1, "pageSize": 500})["data"]
    if exclude_tiebreak:
        rows = [m for m in rows if not str(m.get("remark") or "").startswith("同分加赛")]
    rows.sort(key=lambda m: (m.get("displayRow") or 0, m["id"]))
    return rows


def tiebreak_matches(c, stage_id):
    rows = [m for m in stage_matches(c, stage_id)
            if str(m.get("remark") or "").startswith("同分加赛")]
    rows.sort(key=lambda m: (m.get("displayRow") or 0, m["id"]))
    return rows


def match_status(c, match_id):
    return c.call("GET", f"/game/match/{match_id}")["status"]


def participants(c, match_id):
    rows = c.page("GET", "/game/matchParticipant/list",
                  {"matchId": match_id, "pageNum": 1, "pageSize": 500})["data"]
    rows = [p for p in rows if p.get("competitorId")]
    rows.sort(key=lambda p: (p.get("displaySlotIndex") or 0))
    return rows


def count_competitors(c, stage_id, outcome=None):
    params = {"stageId": stage_id, "pageNum": 1, "pageSize": 1}
    if outcome:
        params["outcomeStatus"] = outcome
    return c.page("GET", "/game/competitor/list", params)["total"]


def submit_scores(c, match_id, referee_ids, score_of):
    """逐裁判提交打分(海选/排名:分值制)。score_of(index) 决定第 index 个上场选手的分。"""
    parts = participants(c, match_id)
    for referee_id in referee_ids:
        body = {
            "matchId": match_id,
            "refereeId": referee_id,
            "scores": [
                {"competitorId": p["competitorId"], "score": score_of(i)}
                for i, p in enumerate(parts)
            ],
        }
        c.call("POST", f"/game/match/{match_id}/submit-result", body)


def start_and_score_circle(c, match_id, referee_ids, score_of):
    ensure_match_gaming(c, match_id)
    submit_scores(c, match_id, referee_ids, score_of)


def ensure_match_gaming(c, match_id):
    """把场次推进到可判罚状态并返回。海选/排名赛开赛段时场次已直接 GAMING。"""
    status = match_status(c, match_id)
    if status == "PENDING":
        c.call("POST", f"/game/match/{match_id}/start")
        status = match_status(c, match_id)
    check(status == "GAMING", f"场次 {match_id} 当前状态不可判罚: {status}")


def finish_knockout_match(c, match_id):
    """开始并判定一场淘汰赛:首位胜。轮空场次开始后会自动结算,直接跳过。"""
    status = match_status(c, match_id)
    if status == "SETTLED":
        return
    if status == "PENDING":
        c.call("POST", f"/game/match/{match_id}/start")
        if match_status(c, match_id) == "SETTLED":
            return
    parts = participants(c, match_id)
    check(len(parts) >= 2, f"淘汰赛场次 {match_id} 参赛方不足 2(实际 {len(parts)})")
    outcomes = {p["competitorId"]: ("WIN" if i == 0 else "LOSS") for i, p in enumerate(parts)}
    c.call("POST", f"/game/match/{match_id}/submit-result",
           {"matchId": match_id, "outcomes": outcomes})


def run_knockout_stage(c, stage_id):
    """开始赛段(自动初始化+生成对阵)→ 逐场判定 → 结算。"""
    start_stage(c, stage_id)
    for m in stage_matches(c, stage_id):
        finish_knockout_match(c, m["id"])
    eq(complete_stage(c, stage_id), "SETTLED", f"淘汰赛赛段 {stage_id} 未结算")


def build_revival_chain(c, tid, audition, referee_count=4):
    """海选(2 圈) + 复活 → 32强 → 16强 → 8强 → 半决赛 → 决赛。"""
    revival = new_stage(c, tid, "复活赛", "KNOCKOUT", 32, 16, audition, knockout_rule(32, 16))
    round32 = new_stage(c, tid, "32强", "KNOCKOUT", 32, 16, revival, knockout_rule(32, 16))
    round16 = new_stage(c, tid, "16强", "KNOCKOUT", 16, 8, round32, knockout_rule(16, 8))
    round8 = new_stage(c, tid, "8强", "KNOCKOUT", 8, 4, round16, knockout_rule(8, 4))
    semi = new_stage(c, tid, "半决赛", "KNOCKOUT", 4, 2, round8, knockout_rule(4, 2))
    final = new_stage(c, tid, "决赛", "KNOCKOUT", 2, 1, semi, knockout_rule(2, 1))
    return revival, round32, round16, round8, semi, final


# --------------------------------------------------------------------------- #
# 场景
# --------------------------------------------------------------------------- #


@scenario
def s0_health_and_login(c):
    """首页、登录、赛事列表:验证静态资源、Sa-Token 与 MyBatis mapper 全部可用。"""
    status, _, _ = raw("GET", "/")
    eq(status, 200, "首页静态资源不可访问(native 未打进前端 dist?)")

    c.login()
    check(bool(c.token), "登录未返回 token")

    page = c.page("GET", "/game/tournament/list", {"pageNum": 1, "pageSize": 5})
    check(isinstance(page.get("data"), list), "赛事列表缺少 data 数组")

    # 鉴权边界:无 token 必须被拒
    status, _, payload = raw("GET", "/game/tournament/list?pageNum=1&pageSize=1")
    check(status != 200 or b'"code":200' not in payload,
          "无 token 访问受保护接口竟然成功")


@scenario
def s1_two_circle_audition_revival_to_champion(c):
    """主流程:两圈海选 → 复活赛 → 32强 → … → 决赛冠军。"""
    tid = new_tournament(c, "E2E两圈海选复活赛制-" + uuid.uuid4().hex[:6])
    refs = [new_referee(c, tid, f"裁判{i}") for i in range(1, 5)]

    audition = new_stage(c, tid, "海选", "AUDITION", 0, 16, None,
                         audition_rule(2, 16, 100, [[refs[0], refs[1]], [refs[2], refs[3]]], [8, 8]))
    revival, round32, round16, round8, semi, final = build_revival_chain(c, tid, audition)

    # 复活赛名单 = 海选每圈第 9~24 名;32 强名单 = 海选每圈前 8
    add_rank_group(c, revival, audition, "ANY", "ZONE-1", 9, 24)
    add_rank_group(c, revival, audition, "ANY", "ZONE-2", 9, 24)
    drop_roster_group(c, revival, 0)
    add_rank_group(c, round32, audition, "ADVANCE", "ZONE-1", 1, 8)
    add_rank_group(c, round32, audition, "ADVANCE", "ZONE-2", 1, 8)

    add_competitors(c, tid, audition, 64)
    start_stage(c, audition)

    circles = stage_matches(c, audition)
    eq(len(circles), 2, "海选应生成 2 个圈场次")
    eq(circles[0].get("displayZone"), "ZONE-1", "第一个圈应为 ZONE-1")
    eq(circles[1].get("displayZone"), "ZONE-2", "第二个圈应为 ZONE-2")
    eq(len(participants(c, circles[0]["id"])), 32, "ZONE-1 应均分 32 人")
    eq(len(participants(c, circles[1]["id"])), 32, "ZONE-2 应均分 32 人")

    start_and_score_circle(c, circles[0]["id"], [refs[0], refs[1]], lambda i: 100 - i)
    start_and_score_circle(c, circles[1]["id"], [refs[2], refs[3]], lambda i: 100 - i)

    eq(complete_stage(c, audition), "SETTLED", "海选未结算")
    eq(count_competitors(c, audition, "ADVANCE"), 16, "海选晋级人数")
    eq(count_competitors(c, audition, "ELIMINATED"), 48, "海选淘汰人数")

    # 出口候选:复活两组各 16 人
    candidates = c.call("GET", f"/game/stage/{revival}/roster/candidates")["groups"]
    eq(len(candidates), 2, "复活赛候选组数")
    eq(sorted(len(g["competitors"]) for g in candidates), [16, 16], "复活赛每组候选人数")

    # 复活赛 32 → 16
    eq(apply_roster(c, revival), 32, "复活赛装配人数")
    eq(count_competitors(c, revival), 32, "复活赛参赛方总数")
    run_knockout_stage(c, revival)
    eq(count_competitors(c, revival, "ADVANCE"), 16, "复活赛晋级人数")

    # 32 强 = 16 复活晋级 + 16 海选直入
    eq(apply_roster(c, round32), 32, "32强装配人数")
    # sourceStageId 不在列表接口的过滤字段里,按返回数据自行统计来源
    round32_rows = c.page("GET", "/game/competitor/list",
                          {"stageId": round32, "pageNum": 1, "pageSize": 500})["data"]
    direct = sum(1 for r in round32_rows if str(r.get("sourceStageId")) == str(audition))
    eq(direct, 16, "32强上海选直入人数")
    run_knockout_stage(c, round32)
    eq(count_competitors(c, round32, "ADVANCE"), 16, "32强晋级人数")

    for stage_id, expect in ((round16, 8), (round8, 4), (semi, 2), (final, 1)):
        eq(apply_roster(c, stage_id), expect * 2, f"赛段 {stage_id} 装配人数")
        run_knockout_stage(c, stage_id)
        eq(count_competitors(c, stage_id, "ADVANCE"), expect, f"赛段 {stage_id} 晋级人数")

    eq(stage_status(c, final), "SETTLED", "决赛未结算")


@scenario
def s2_rank_boundary_tie_does_not_spawn_tiebreak(c):
    """名次段边界同分(第 24、25 名)→ 不开加赛,赛段直接结算。"""
    tid = new_tournament(c, "E2E名次段边界同分-" + uuid.uuid4().hex[:6])
    judge = new_referee(c, tid, "裁判A")
    audition = new_stage(c, tid, "海选", "AUDITION", 0, 8, None,
                         audition_rule(1, 8, 100, [[judge]], [8]))
    revival = new_stage(c, tid, "复活赛", "KNOCKOUT", 32, 16, audition, knockout_rule(32, 16))

    add_rank_group(c, revival, audition, "ANY", None, 9, 24)
    drop_roster_group(c, revival, 0)

    add_competitors(c, tid, audition, 26)
    start_stage(c, audition)
    circles = stage_matches(c, audition)
    eq(len(circles), 1, "单圈海选应只有 1 个圈场次")

    # 第 24、25 名同分(名次段末端,不在晋级线上)
    def tie_score(i):
        slot = i + 1
        return (100 - 23) if slot in (24, 25) else (100 - i)

    start_and_score_circle(c, circles[0]["id"], [judge], tie_score)

    eq(complete_stage(c, audition), "SETTLED", "名次段边界同分不应阻塞结算")
    eq(len(tiebreak_matches(c, audition)), 0, "名次段边界同分不应开加赛")
    eq(count_competitors(c, audition, "ADVANCE"), 8, "海选晋级人数")

    candidate_total = sum(len(g["competitors"])
                          for g in c.call("GET", f"/game/stage/{revival}/roster/candidates")["groups"])
    eq(candidate_total, 16, "复活赛候选人数应为名次段 9~24 共 16 人")


@scenario
def s3_advance_line_tie_spawns_second_audition(c):
    """晋级线同分 → 圈内二海;二海再同分 → 三海;最终名额精确、正式圈数不变。"""
    tid = new_tournament(c, "E2E晋级线二海-" + uuid.uuid4().hex[:6])
    z1 = new_referee(c, tid, "圈1裁判")
    z2 = new_referee(c, tid, "圈2裁判")
    audition = new_stage(c, tid, "海选", "AUDITION", 0, 16, None,
                         audition_rule(2, 16, 100, [[z1], [z2]], [8, 8]))
    round32 = new_stage(c, tid, "32强", "KNOCKOUT", 32, 16, audition, knockout_rule(32, 16))

    add_competitors(c, tid, audition, 32)
    start_stage(c, audition)
    circles = stage_matches(c, audition)
    eq(len(circles), 2, "海选应生成 2 个圈场次")

    # 圈1 第 8、9 名同分(晋级线并列)
    def tie_score(i):
        slot = i + 1
        return (100 - 7) if slot in (8, 9) else (100 - i)

    start_and_score_circle(c, circles[0]["id"], [z1], tie_score)
    start_and_score_circle(c, circles[1]["id"], [z2], lambda i: 100 - i)

    eq(complete_stage(c, audition), "GAMING", "晋级线同分应保持赛段进行中并开加赛")
    chain = tiebreak_matches(c, audition)
    eq(len(chain), 1, "首轮应产生 1 场二海")
    check("晋级名额" in (chain[0].get("remark") or ""), "二海备注应说明晋级名额")
    eq(count_competitors(c, audition, "PENDING"), 2, "并列两人应保持待定")
    eq(count_competitors(c, audition, "ADVANCE"), 15, "明确晋级者应为 7+8")

    # 二海又同分 → 自动再开一轮(三海)
    ensure_match_gaming(c, chain[0]["id"])
    submit_scores(c, chain[0]["id"], [z1], lambda i: 80)
    chain = tiebreak_matches(c, audition)
    eq(len(chain), 2, "二海同分应再开三海")
    eq(match_status(c, chain[0]["id"]), "SETTLED", "二海应自动结算")
    eq(stage_status(c, audition), "GAMING", "三海未决出前赛段应保持进行中")

    # 三海分出胜负 → 结算,圈1 恰好 8 人晋级
    ensure_match_gaming(c, chain[1]["id"])
    submit_scores(c, chain[1]["id"], [z1], lambda i: 90 - i)
    eq(complete_stage(c, audition), "SETTLED", "三海决出后赛段应可结算")
    eq(count_competitors(c, audition, "ADVANCE"), 16, "海选总晋级人数")
    eq(count_competitors(c, audition, "PENDING"), 0, "结算后不应再有待定")
    eq(len(stage_matches(c, audition, exclude_tiebreak=True)), 2, "加赛不应计入正式圈数")

    candidate_total = sum(len(g["competitors"])
                          for g in c.call("GET", f"/game/stage/{round32}/roster/candidates")["groups"])
    eq(candidate_total, 16, "32强候选应为 16 名晋级者")


@scenario
def s4_roster_smoke(c):
    """名单冒烟:默认名单、候选、装配幂等。"""
    tid = new_tournament(c, "E2E名单冒烟-" + uuid.uuid4().hex[:6])
    judge = new_referee(c, tid, "裁判S")
    audition = new_stage(c, tid, "海选", "AUDITION", 0, 4, None,
                         audition_rule(1, 4, 10, [[judge]], [4]))
    round4 = new_stage(c, tid, "4强", "KNOCKOUT", 4, 2, audition, knockout_rule(4, 2))

    default_roster = roster(c, round4)
    check(default_roster.get("groups"), "新建赛段应自动合成默认名单组")
    # 来源赛段未结算前,默认名单处于「等待来源」
    eq(default_roster.get("state"), "WAIT_SOURCE", "默认名单状态")

    add_competitors(c, tid, audition, 8)
    start_stage(c, audition)
    circle = stage_matches(c, audition)[0]
    start_and_score_circle(c, circle["id"], [judge], lambda i: 10 - i)
    eq(complete_stage(c, audition), "SETTLED", "海选未结算")

    candidates = c.call("GET", f"/game/stage/{round4}/roster/candidates")["groups"]
    eq(sum(len(g["competitors"]) for g in candidates), 4, "4强候选应为 4 名晋级者")

    eq(apply_roster(c, round4), 4, "首次装配应带入 4 人")
    # 名单进入 CONFIRMED 后再次装配被锁拒绝(返回 0),不会重复写入
    eq(apply_roster(c, round4), 0, "已确认名单应拒绝重复装配")
    eq(count_competitors(c, round4), 4, "重复装配不应产生重复参赛方")
    eq(roster(c, round4)["state"], "CONFIRMED", "装配后名单状态应为 CONFIRMED")


@scenario
def s5_query_and_sse(c):
    """查询接口与 SSE:海选结果、赛段流程、预排、观众端大屏连接。

    Excel 导出/导入不在此验证:FastExcel 的 BeanMap 走打包版 cglib 生成运行期类,
    而 GraalVM native 不支持运行期类定义,导出必然失败。属已知限制,详见 README
    「native 产物已知限制」。
    """
    tid = new_tournament(c, "E2E查询导出-" + uuid.uuid4().hex[:6])
    judge = new_referee(c, tid, "裁判E")
    audition = new_stage(c, tid, "海选", "AUDITION", 0, 2, None,
                         audition_rule(1, 2, 100, [[judge]], [2]))
    round2 = new_stage(c, tid, "决赛", "KNOCKOUT", 2, 1, audition, knockout_rule(2, 1))

    add_competitors(c, tid, audition, 4)
    start_stage(c, audition)
    circle = stage_matches(c, audition)[0]
    start_and_score_circle(c, circle["id"], [judge], lambda i: 100 - i)
    eq(complete_stage(c, audition), "SETTLED", "海选未结算")

    # 海选结果(含加赛明细口径)
    result = c.call("GET", f"/game/stage/{audition}/audition-result")
    check(isinstance(result, dict), "海选结果应为对象")

    # 赛段流程 + 预排
    check(c.call("GET", f"/game/stage/flow/{tid}") is not None, "赛段流程为空")
    check(c.call("GET", f"/game/stage/prebracket/{round2}") is not None, "预排为空")

    # SSE:导播端凭赛事 authKey 注册屏幕 → 观众端连接该屏幕(大屏实时投射主链路)
    auth_key = c.call("GET", f"/game/tournament/authKey/{tid}")["authKey"]
    check(bool(auth_key), "赛事 authKey 为空")
    screen = "e2e-screen-" + uuid.uuid4().hex[:6]
    status, headers = sse_open(
        f"/tournament/screen/control?screenIds={screen}"
        f"&terminalId={uuid.uuid4().hex[:8]}&tournamentId={tid}",
        token=auth_key,
    )
    eq(status, 200, "SSE 导播端连接失败")
    check("event-stream" in (headers.get("Content-Type") or ""),
          "SSE 导播端响应类型不是 event-stream")

    status, headers = sse_open(
        f"/tournament/screen/view?screenId={screen}&terminalId={uuid.uuid4().hex[:8]}")
    eq(status, 200, "SSE 观众端连接失败")
    check("event-stream" in (headers.get("Content-Type") or ""),
          "SSE 观众端响应类型不是 event-stream")


@scenario
def s6_excel_import_and_audition_export(c):
    """真正在用的两处 Excel:选手导入 + 海选结果导出。

    换掉 FastExcel(cglib 运行期生成 BeanMap)后,这两条路径必须仍然可用——
    导出走 fastexcel 写出、导入走 fastexcel-reader 解析,都不依赖运行期类生成。
    """
    tid = new_tournament(c, "E2E-excel-" + uuid.uuid4().hex[:6])
    judge = new_referee(c, tid, "裁判X")
    audition = new_stage(c, tid, "海选", "AUDITION", 0, 2, None,
                         audition_rule(1, 2, 100, [[judge]], [2]))
    final = new_stage(c, tid, "决赛", "KNOCKOUT", 2, 1, audition, knockout_rule(2, 1))

    add_competitors(c, tid, audition, 4)
    start_stage(c, audition)
    circle = stage_matches(c, audition)[0]
    start_and_score_circle(c, circle["id"], [judge], lambda i: 100 - i)
    eq(complete_stage(c, audition), "SETTLED", "海选未结算")

    # 1) 海选结果导出:多 sheet(海选成绩 + 加赛表)
    status, _, payload = raw("GET", f"/game/stage/{audition}/export-audition-result", token=c.token)
    eq(status, 200, "海选结果导出失败")
    check(payload[:2] == b"PK", f"海选结果导出不是 xlsx: {payload[:80]!r}")
    names = xlsx_sheet_names(payload)
    check("海选成绩" in names, f"海选结果缺少「海选成绩」工作表: {names}")

    # 2) 选手导入:上传一份「外部生成」的 xlsx(内联字符串),验证表头映射
    rows = [
        ["选手名称", "身份唯一标识", "标签", "备注"],
        ["导入甲", "ID-A", "bboy", "备注A"],
        ["导入乙", "ID-B", "bgirl", "备注B"],
        ["", "", "", ""],  # 空行应被跳过
    ]
    resp = post_file("/game/player/import", "file", "players.xlsx", make_xlsx(rows),
                     params={"tournamentId": tid}, token=c.token)
    eq(resp.get("code"), 200, f"选手导入失败: {resp.get('msg')}")
    check("2" in str(resp.get("msg")), f"导入数量不符: {resp.get('msg')}")

    players = c.page("GET", "/game/player/list",
                     {"tournamentId": tid, "pageNum": 1, "pageSize": 50})["data"]
    eq({p["name"] for p in players}, {"导入甲", "导入乙"}, "导入后的选手名单")
    id_cards = {p["idCard"] for p in players}
    eq(id_cards, {"ID-A", "ID-B"}, "导入后的身份标识")

    # 3) 导出模板(空数据也要能出表头)
    status, _, payload = raw("POST", "/game/player/import-template", body=None, token=c.token)
    eq(status, 200, "选手导入模板导出失败")
    check(payload[:2] == b"PK", "选手导入模板不是 xlsx")


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #


def wait_ready(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            status, _, _ = raw("GET", "/", timeout=5)
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def main():
    print(f"native e2e → {BASE}")
    if not wait_ready():
        print(f"服务在 {BASE} 未就绪,无法开始")
        return 2

    c = Client()
    failed = []
    for fn in SCENARIOS:
        label = f"{fn.__name__}"
        started = time.time()
        try:
            fn(c)
            print(f"  ✓ {label}  ({time.time() - started:.1f}s)")
        except Exception as e:  # noqa: BLE001 - 逐场景收集失败,便于一次跑完全部
            print(f"  ✗ {label}  ({time.time() - started:.1f}s)")
            print(f"      {type(e).__name__}: {e}")
            failed.append(label)

    print()
    if failed:
        print(f"失败 {len(failed)}/{len(SCENARIOS)}:{', '.join(failed)}")
        return 1
    print(f"全部通过:{len(SCENARIOS)} 个场景 / {PASSED} 项断言")
    return 0


if __name__ == "__main__":
    sys.exit(main())
