#!/usr/bin/env python3
"""HorizonDebug 场景脚本：把一串 JSONL 调试请求写成文件，一次跑完并按断言判定。

用法：
    python tools/hscenario.py <场景文件.json>
    python tools/hscenario.py <场景文件.json> --json      # 只打印最终 JSON 报告

场景文件形如：

    {
      "name": "假玩家基础动作",
      "steps": [
        {"srv": "bot.despawn", "args": {"name": "all"}},
        {"srv": "bot.spawn",   "args": {"name": "s1", "dim": 0}, "note": "拉起一个假玩家"},
        {"srv": "bot.state",   "args": {"name": "Bot_s1"},
         "expect": [{"path": "result.detail", "contains": "hp="}]},
        {"sleep": 1200},
        {"cli": "state", "expect": [{"path": "ok", "eq": true}]}
      ]
    }

每个步骤只做一件事：`srv`（服务端）、`cli`（客户端）、`sleep`（毫秒），
或 `wait`（轮询直到断言通过，用于等玩家进服、等世界加载这类跨秒事件）：

    {"wait": {"cli": "state", "timeout": 60000, "interval": 1000,
              "expect": [{"path": "result.detail", "contains": "name="}]}}

`expect` 是断言列表，每项含 `path`（点号路径，支持 result.detail 这类嵌套）以及
`eq` / `contains` / `exists` 之一；任一断言失败即记该步失败并继续跑完，
最后按失败数量返回退出码（0 全通过，1 有失败）。

环境变量与 tools/hdebug.py 一致（HORIZON_DEBUG_SERVER / HORIZON_DEBUG_CLIENT /
HORIZON_DEBUG_TIMEOUT）。
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import hdebug


def dig(obj, path: str):
    """按点号路径取值；中途缺失返回 (False, None)"""
    cur = obj
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        else:
            return False, None
    return True, cur


def check(resp, expect):
    """返回失败原因列表；空列表表示全部通过"""
    fails = []
    for rule in expect or []:
        path = rule.get("path", "")
        found, value = dig(resp, path)
        if "exists" in rule:
            if found != bool(rule["exists"]):
                fails.append(f"{path} 存在性应为 {rule['exists']}，实际 {found}")
            continue
        if not found:
            fails.append(f"缺少 {path}")
            continue
        if "eq" in rule:
            if value != rule["eq"]:
                fails.append(f"{path} 应为 {rule['eq']!r}，实际 {value!r}")
        elif "contains" in rule:
            if str(rule["contains"]) not in str(value):
                fails.append(f"{path} 应包含 {rule['contains']!r}，实际 {value!r}")
        else:
            fails.append(f"{path} 的断言缺少 eq/contains/exists")
    return fails


def run(scenario: dict, timeout: float):
    results = []
    failed = 0
    for index, step in enumerate(scenario.get("steps", [])):
        label = f"#{index}"
        if "sleep" in step:
            ms = int(step["sleep"])
            time.sleep(ms / 1000.0)
            results.append({"step": label, "kind": "sleep", "ms": ms, "ok": True})
            continue
        if "wait" in step:
            entry = wait_step(label, step["wait"], timeout)
            if not entry["ok"]:
                failed += 1
            results.append(entry)
            continue
        side = "server" if "srv" in step else "client" if "cli" in step else None
        if side is None:
            results.append({"step": label, "ok": False, "error": "步骤既不是 srv/cli 也不是 sleep"})
            failed += 1
            continue
        op = step["srv"] if side == "server" else step["cli"]
        args = step.get("args") or {}
        resp = hdebug.request(side, op, args, timeout)
        if resp is None:
            results.append({"step": label, "side": side, "op": op, "ok": False,
                            "error": f"{timeout:.0f}s 内没有响应"})
            failed += 1
            continue
        fails = check(resp, step.get("expect"))
        entry = {"step": label, "side": side, "op": op, "ok": not fails and bool(resp.get("ok"))}
        if step.get("note"):
            entry["note"] = step["note"]
        if not resp.get("ok"):
            entry["error"] = (resp.get("error") or {}).get("message", "")
        if fails:
            entry["fails"] = fails
        entry["response"] = resp
        if not entry["ok"]:
            failed += 1
        results.append(entry)
    return results, failed


def wait_step(label: str, spec: dict, timeout: float) -> dict:
    """反复请求某个 op，直到 expect 全部通过或超时；返回一步的结果"""
    side = "server" if "srv" in spec else "client" if "cli" in spec else None
    if side is None:
        return {"step": label, "kind": "wait", "ok": False, "error": "wait 里既没有 srv 也没有 cli"}
    op = spec["srv"] if side == "server" else spec["cli"]
    args = spec.get("args") or {}
    budget = float(spec.get("timeout", 30000))
    interval = max(0.05, float(spec.get("interval", 500)) / 1000.0)
    deadline = time.time() + budget / 1000.0
    attempts = 0
    fails = ["尚未执行"]
    last = None
    while True:
        attempts += 1
        last = hdebug.request(side, op, args, timeout)
        if last is not None:
            fails = check(last, spec.get("expect"))
            if not fails and last.get("ok"):
                return {"step": label, "kind": "wait", "side": side, "op": op, "ok": True,
                        "attempts": attempts, "response": last}
            if not fails and not last.get("ok"):
                fails = ["响应 ok=false：" + str((last.get("error") or {}).get("message", ""))]
        else:
            fails = [f"{timeout:.0f}s 内没有响应"]
        if time.time() + interval > deadline:
            entry = {"step": label, "kind": "wait", "side": side, "op": op, "ok": False,
                     "attempts": attempts, "fails": fails, "error": f"{budget:.0f}ms 内未满足条件"}
            if last is not None:
                entry["response"] = last
            return entry
        time.sleep(interval)


def main() -> int:
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 0
    path = Path(argv[0])
    as_json = "--json" in argv
    if not path.is_file():
        sys.exit(f"场景文件不存在：{path}")
    scenario = json.loads(path.read_text(encoding="utf-8"))
    timeout = float(scenario.get("timeout", 15))
    started = time.time()
    results, failed = run(scenario, timeout)
    report = {
        "name": scenario.get("name", path.stem),
        "steps": len(results),
        "failed": failed,
        "ms": int((time.time() - started) * 1000),
        "results": results,
    }
    if as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"场景 {report['name']}：{report['steps']} 步，失败 {failed}，用时 {report['ms']}ms")
        for entry in results:
            mark = "OK  " if entry.get("ok") else "FAIL"
            head = f"  {mark} {entry['step']}"
            if entry.get("kind") == "sleep":
                print(f"{head} sleep {entry['ms']}ms")
                continue
            print(f"{head} {entry.get('side')} {entry.get('op')}")
            for line in entry.get("fails", []):
                print(f"        - {line}")
            if entry.get("error"):
                print(f"        - {entry['error']}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
