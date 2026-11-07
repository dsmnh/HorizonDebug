#!/usr/bin/env python3
"""HorizonDebug 调试客户端：驱动文件命令桥与代理端 JSON 调试桥。

用法：
    python tools/debug_client.py srv <命令...>      写一条命令到服务端 cmd.txt
    python tools/debug_client.py cli <命令...>      写一条命令到客户端 cmd-client.txt
    python tools/debug_client.py readsrv [n]        查看服务端 cmd.out 尾部（默认 40 行）
    python tools/debug_client.py readcli [n]        查看客户端 cmd-client.out 尾部
    python tools/debug_client.py status             查询代理端调试桥自检信息
    python tools/debug_client.py servers            列出代理子服（名称与端口）
    python tools/debug_client.py probe [channel]    向插件消息信道发送探测负载
    python tools/debug_client.py bridge '<json>'    发送一行自定义 JSON 到调试桥

环境变量（本机取值见工作区文档 测试环境.md）：
    HORIZON_DEBUG_SERVER   服务端根目录（默认当前目录），命令写入其 dumps/horizondebug/ 下
    HORIZON_DEBUG_CLIENT   客户端 .minecraft 目录（cli/readcli 需要）
    HORIZON_DEBUG_BRIDGE   代理端调试桥地址 host:port
    HORIZON_DEBUG_SECRET   调试桥 secret（status/servers/probe/bridge 自动注入）

注意：命令文件必须是 UTF-8 无 BOM；PowerShell 5 的 Set-Content -Encoding UTF8 会写入 BOM
导致命令首字符损坏，写文件一律使用本脚本。
"""
import json
import os
import socket
import sys
from pathlib import Path

DIR = "dumps/horizondebug"


def server_root() -> Path:
    return Path(os.environ.get("HORIZON_DEBUG_SERVER", "."))


def client_root() -> Path:
    value = os.environ.get("HORIZON_DEBUG_CLIENT")
    if not value:
        sys.exit("HORIZON_DEBUG_CLIENT 未设置：指向客户端实例的 .minecraft 目录")
    return Path(value)


def write_cmd(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # 追加模式 + 显式 \n：监听线程按「最后一个换行」截取完整行，尾部未写完的内容留在原处
    with open(path, "a", encoding="utf-8", newline="\n") as f:
        f.write(text.rstrip("\n") + "\n")
    print(f"written: {path} <- {text}")


def tail(path: Path, n: int) -> None:
    if not path.is_file():
        print(f"(no output yet: {path})")
        return
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    print("\n".join(lines[-n:]))


def bridge_request(req: dict) -> None:
    address = os.environ.get("HORIZON_DEBUG_BRIDGE")
    if not address:
        sys.exit("HORIZON_DEBUG_BRIDGE 未设置：格式 host:port")
    if "secret" not in req:
        secret = os.environ.get("HORIZON_DEBUG_SECRET")
        if secret:
            req["secret"] = secret
    host, _, port = address.partition(":")
    with socket.create_connection((host, int(port)), timeout=15) as s:
        s.sendall((json.dumps(req, ensure_ascii=False) + "\n").encode("utf-8"))
        data = s.makefile("rb").readline().decode("utf-8", errors="replace")
    print(json.dumps(json.loads(data), ensure_ascii=False, indent=2))


def main() -> None:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    cmd, rest = args[0], args[1:]
    if cmd == "srv":
        if not rest:
            sys.exit("用法: srv <命令...>")
        write_cmd(server_root() / DIR / "cmd.txt", " ".join(rest))
    elif cmd == "cli":
        if not rest:
            sys.exit("用法: cli <命令...>")
        write_cmd(client_root() / DIR / "cmd-client.txt", " ".join(rest))
    elif cmd == "readsrv":
        tail(server_root() / DIR / "cmd.out", int(rest[0]) if rest else 40)
    elif cmd == "readcli":
        tail(client_root() / DIR / "cmd-client.out", int(rest[0]) if rest else 40)
    elif cmd == "status":
        bridge_request({"op": "status"})
    elif cmd == "servers":
        bridge_request({"op": "servers"})
    elif cmd == "probe":
        req = {"op": "probe"}
        if rest:
            req["channel"] = rest[0]
        for extra in rest[1:]:
            if extra.startswith("--player="):
                req["player"] = extra.split("=", 1)[1]
            elif extra.startswith("--payload="):
                req["payload"] = extra.split("=", 1)[1]
        bridge_request(req)
    elif cmd == "bridge":
        if not rest:
            sys.exit("用法: bridge '<json>'")
        bridge_request(json.loads(" ".join(rest)))
    else:
        print(__doc__)
        sys.exit(f"未知子命令: {cmd}")


if __name__ == "__main__":
    main()
