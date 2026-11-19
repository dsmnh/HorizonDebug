#!/usr/bin/env python3
"""HorizonDebug JSONL 调试客户端：一条命令驱动服务端假玩家 API 与客户端操控 API。

用法：
    python tools/hdebug.py srv bot.spawn name=1
    python tools/hdebug.py srv bot.list
    python tools/hdebug.py srv bot.say name=Bot_1 text=hello
    python tools/hdebug.py cli state
    python tools/hdebug.py cli look yaw=90 pitch=10
    python tools/hdebug.py cli script 'steps=[{"at":0,"key":{"name":"forward","down":true}}]'
    python tools/hdebug.py srv raw '{"op":"bot.state","args":{"name":"Bot_1"}}'
    python tools/hdebug.py cli-tail 20

环境变量（本机取值见工作区文档 测试环境.md）：
    HORIZON_DEBUG_SERVER   服务端根目录，读写其 dumps/horizondebug/api{,-out}.jsonl
    HORIZON_DEBUG_CLIENT   客户端 .minecraft 目录，读写其 dumps/horizondebug/api-client{,-out}.jsonl
    HORIZON_DEBUG_TIMEOUT  单次请求等待秒数（默认 15）

请求按行 JSON 追加写入 api.jsonl / api-client.jsonl，模组每 tick 排空并追加响应到
-api-out.jsonl；本脚本按 id 匹配响应后打印 JSON。文件必须是 UTF-8 无 BOM。
"""
import json
import os
import sys
import time
import uuid
from pathlib import Path

DIR = "dumps/horizondebug"
SIDES = {
    "server": ("HORIZON_DEBUG_SERVER", "api.jsonl", "api-out.jsonl", "server"),
    "client": ("HORIZON_DEBUG_CLIENT", "api-client.jsonl", "api-client-out.jsonl", "client"),
}


def paths(side: str):
    env, in_name, out_name, label = SIDES[side]
    value = os.environ.get(env)
    if not value:
        sys.exit(f"{env} 未设置：指向{'服务端根目录' if side == 'server' else '客户端 .minecraft 目录'}")
    base = Path(value) / DIR
    return base / in_name, base / out_name, label


def parse_value(text: str):
    """把 key=value 里的 value 按 JSON 解析，失败则当字符串"""
    if text == "":
        return ""
    try:
        return json.loads(text)
    except (ValueError, json.JSONDecodeError):
        return text


def build_args(pairs):
    args = {}
    for item in pairs:
        if "=" not in item:
            sys.exit(f"参数必须是 key=value 形式：{item}")
        key, _, value = item.partition("=")
        args[key] = parse_value(value)
    return args


def wait_response(out_path: Path, req_id: str, skip_lines: int, timeout: float):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if out_path.is_file():
            lines = out_path.read_text(encoding="utf-8", errors="replace").splitlines()
            for line in lines[skip_lines:]:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except (ValueError, json.JSONDecodeError):
                    continue
                if obj.get("id") == req_id:
                    return obj
        time.sleep(0.2)
    return None


def request(side: str, op: str, args: dict, timeout: float):
    """发一条请求并返回响应字典；超时返回 None（供 scenarios 一类上层脚本复用）"""
    in_path, out_path, label = paths(side)
    in_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    skip = 0
    if out_path.is_file():
        skip = len(out_path.read_text(encoding="utf-8", errors="replace").splitlines())
    req_id = f"{label}-{uuid.uuid4().hex[:8]}"
    req = {"id": req_id, "side": label, "op": op}
    if args:
        req["args"] = args
    # 追加一行请求：模组按「最后一个换行」切行，未写完的尾部会等下次拼接
    with open(in_path, "a", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(req, ensure_ascii=False) + "\n")
    return wait_response(out_path, req_id, skip, timeout)


def send(side: str, op: str, args: dict, timeout: float) -> int:
    resp = request(side, op, args, timeout)
    if resp is None:
        _, out_path, _ = paths(side)
        print(f"timeout: {timeout:.0f}s 内没有收到响应（{out_path}）", file=sys.stderr)
        return 2
    print(json.dumps(resp, ensure_ascii=False, indent=2))
    return 0 if resp.get("ok") else 1


def tail(side: str, n: int) -> None:
    _, out_path, _ = paths(side)
    if not out_path.is_file():
        print(f"(no output yet: {out_path})")
        return
    lines = out_path.read_text(encoding="utf-8", errors="replace").splitlines()
    print("\n".join(lines[-n:]))


def main() -> int:
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 0
    timeout = float(os.environ.get("HORIZON_DEBUG_TIMEOUT", "15"))
    cmd, rest = argv[0], argv[1:]
    if cmd in ("srv", "cli"):
        if not rest:
            sys.exit(f"用法: {cmd} <op> [key=value ...]")
        side = "server" if cmd == "srv" else "client"
        return send(side, rest[0], build_args(rest[1:]), timeout)
    if cmd in ("srv-raw", "cli-raw"):
        if not rest:
            sys.exit(f"用法: {cmd} '<json>'")
        side = "server" if cmd == "srv-raw" else "client"
        spec = json.loads(" ".join(rest))
        return send(side, spec.get("op", ""), spec.get("args", {}), timeout)
    if cmd == "srv-tail":
        tail("server", int(rest[0]) if rest else 20)
        return 0
    if cmd == "cli-tail":
        tail("client", int(rest[0]) if rest else 20)
        return 0
    print(__doc__)
    sys.exit(f"未知子命令: {cmd}")


if __name__ == "__main__":
    sys.exit(main())
