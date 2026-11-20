#!/usr/bin/env python3
"""服务端 MSPT 基线对比：先量空载，再拉起 N 个假玩家重量，给出增幅与验收判定。

用法：
    python tools/hperf.py                        # 默认 10 个假玩家、每档 5 次采样
    python tools/hperf.py --bots 10 --samples 5 --warmup 6 --threshold 5
    python tools/hperf.py --json                 # 只打印 JSON 报告

验收口径来自设计文档 §2「反目标」：10 个假玩家时 MSPT 增幅 < 5 判通过。
假玩家在对比结束后一律清场（包括原本就在场的），因此本脚本只用于测试服。
"""
import argparse
import json
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import hdebug


def sample(samples: int, interval: float, timeout: float):
    """连续采样 server.tps 顶层的 mspt / tps / ticks"""
    rows = []
    for _ in range(samples):
        resp = hdebug.request("server", "server.tps", {}, timeout)
        if resp is None or not resp.get("ok"):
            raise SystemExit("server.tps 没有正常响应，确认服务端在跑且 debug.jsonApi 已开启")
        result = resp["result"]
        if int(result.get("ticks", 0)) < 20:
            # 窗口刚重置（例如刚启服），这一笔会拉低均值，跳过
            time.sleep(interval)
            continue
        rows.append({
            "mspt": float(result.get("mspt", 0.0)),
            "tps": float(result.get("tps", 0.0)),
            "max": float(result.get("max", 0.0)),
        })
        time.sleep(interval)
    if not rows:
        raise SystemExit("采样窗口内没有拿到有效数据（ticks 一直不足）")
    return {
        "mspt": round(statistics.mean(r["mspt"] for r in rows), 3),
        "tps": round(statistics.mean(r["tps"] for r in rows), 2),
        "max": round(max(r["max"] for r in rows), 2),
        "samples": len(rows),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bots", type=int, default=10)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--warmup", type=float, default=6.0, help="拉起假玩家后等多少秒再开始采样")
    parser.add_argument("--threshold", type=float, default=5.0, help="允许的 MSPT 增量上限")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    hdebug.request("server", "bot.despawn", {"name": "all"}, args.timeout)
    time.sleep(args.warmup)
    base = sample(args.samples, args.interval, args.timeout)

    spawn = hdebug.request("server", "bot.spawnmany",
                           {"prefix": "perf", "count": args.bots, "dim": 0}, args.timeout)
    if spawn is None or not spawn.get("ok"):
        raise SystemExit("bot.spawnmany 失败：" + json.dumps(spawn, ensure_ascii=False))
    made = int(spawn["result"].get("count", 0))
    time.sleep(args.warmup)
    loaded = sample(args.samples, args.interval, args.timeout)

    hdebug.request("server", "bot.despawn", {"name": "all"}, args.timeout)

    delta = round(loaded["mspt"] - base["mspt"], 3)
    report = {
        "bots": made,
        "threshold": args.threshold,
        "baseline": base,
        "loaded": loaded,
        "deltaMspt": delta,
        "ratio": round(loaded["mspt"] / base["mspt"], 3) if base["mspt"] else None,
        "ok": delta < args.threshold,
    }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"假玩家 {made} 个：基线 MSPT {base['mspt']}（TPS {base['tps']}，{base['samples']} 次采样）"
              f" → 加载后 {loaded['mspt']}（TPS {loaded['tps']}）")
        print(f"  增量 {delta} mspt（阈值 {args.threshold}），最大单 tick {loaded['max']}ms"
              f" 判定：{'通过' if report['ok'] else '不通过'}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
