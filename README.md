# HorizonDebug

Horizon 生态的调试功能独立仓库：把 AEMobile 客户端、服务端与 BC 代理端的调试能力剥离成
独立产物，生产环境不装、测试环境装齐即可自动化联调。移植自 AEMobile（MIT）的调试代码，
许可证见 [LICENSE](LICENSE)。

## 组成

| 产物 | 形态 | 内容 |
|---|---|---|
| `horizondebug` mod | Forge 1.7.10（服务端 + 客户端同一个 jar） | 文件命令桥（服务端 / 客户端两条通道）+ `/horizondebug` 诊断命令 + TPS/MSPT 采集 |
| `HorizonDebugBridge` | BungeeCord/HexaCord 插件（Java 8） | JSON TCP 调试桥：`exec` / `probe` / `status` / `servers` 四个诊断 op |
| `tools/debug_client.py` | Python 脚本 | 驱动文件命令桥与调试桥的命令行客户端 |

```
├── build.gradle 等          Forge mod 的 Gradle 工程（GTNH ExampleMod1.7.10 模板）
├── src/main/java/...        mod 源码（dev.dsmnh.horizon.debug）
├── bridge/                  BC 插件源码（dev.dsmnh.horizon.debug.bridge）+ build.ps1
└── tools/                   调试客户端脚本
```

## mod：文件命令桥

`config/HorizonDebug.cfg` 的 `[debug] enabled=true` 时启动（默认 false），产物在
`dumps/horizondebug/` 下：

| 文件 | 侧 | 说明 |
|---|---|---|
| `cmd.txt` | 服务端 | 写一行命令 → 下一个 tick 由服务端命令管理器执行 |
| `cmd.out` | 服务端 | 结果追加：`[HH:mm:ss] > 命令` / `[HH:mm:ss] < result=N 或 error=… (耗时ms)` |
| `cmd-client.txt` | 客户端 | 客户端命令通道，一行一条 |
| `cmd-client.out` | 客户端 | 结果追加，多行结果以 ` | ` 之外的方式按行追加（chatlog/chatjson 保留换行） |

- 命令文件 UTF-8 无 BOM；`#` 开头为注释行；监听线程每 800ms 轮询一次，按「最后一个换行」
  截取完整行，未写完的尾部留在原处等待下次拼接。
- `/horizondebug tps` 与 `/horizondebug dims` 的产物 `tps-compare.txt` / `dims.txt` 不依赖
  debug 开关，随命令写入同一目录。
- `/horizondebug` 子命令（均需权限 2）：`tps`（本模组 TPS/MSPT 表 + `/cofh tps` 原文对照）、
  `dims`（维度清单）、`say <text>`（服务端广播一行测试聊天，支持 `§` 颜色码）。
  Thermos/LuckPerms 环境下需为 default 组授予
  `dev.dsmnh.horizon.debug.command.CommandHorizonDebug`。

### 客户端命令（cmd-client.txt）

| 命令 | 作用 |
|---|---|
| `connect <host> [port]` | 主菜单直连服务器（唯一在主菜单可用的命令） |
| `chat <文本>` | 以玩家身份发送聊天（经 `ServerChatEvent`，与真实输入等价） |
| `cmd <命令>` | 客户端命令优先，无人认领时转发服务端 |
| `info` | 玩家名 / 坐标 / 维度 / 世界 / 血量 / 饥饿 / 模式 |
| `inv` | 背包 36 格（物品名带客户端本地化） |
| `hand` | 手持物品（显示名 + 注册名 + meta + 数量） |
| `ach <成就ID>` | 触发成就（`addStat`，触发 Forge `AchievementEvent`） |
| `chatlog [n]` | 最近 n 条聊天（纯文本，默认 10） |
| `chatjson [n]` | 最近 n 条聊天的组件 JSON（核对 hoverEvent / clickEvent） |
| `players` | 当前世界玩家名 |
| `biome` | 所在生物群系 |
| `nei` | 反射调用 NEI 发送手持物品链接（未装 NEI 时返回错误） |

客户端未进入游戏时只有 `connect` 可执行，其余返回 `not-in-game`。

### 世界显示名覆盖

`config/HorizonDebug.cfg` 的 `[tps] names`（`DIM70=暮色森林` 或 `70=暮色森林`）。
优先级：Multiverse alias > 该配置 > 内置维度表 > provider 英文名 > 世界文件夹名。

## mod：构建

```bash
# Java 8（zulu8）必需；版本号取自 git tag，无 tag 时构建失败
export JAVA_HOME=<Java 8 JDK 路径>
./gradlew build -x test
```

产物 `build/libs/horizondebug-1.7.10-<tag>.jar`；服务端放 `mods/`，客户端放
`.minecraft/mods/`（同一个 jar，客户端类只被 `ClientProxy` 引用，专用服务器不加载）。

## BC 插件：HorizonDebugBridge

构建（本机路径不入仓库，取值见工作区文档）：

```powershell
$env:BC_API_JAR = <BungeeCord/HexaCord API jar 路径>   # 同时提供 shaded Gson
$env:JAVA_HOME  = <Java 8 JDK 路径>
cd bridge; ./build.ps1        # 产物 HorizonDebugBridge.jar，复制到代理 plugins/
```

首次运行在 `plugins/HorizonDebug/config.yml` 生成默认配置：

| 键 | 默认 | 说明 |
|---|---|---|
| `bind` | `127.0.0.1` | 调试桥只对内监听；暴露到外网等于公开命令执行 |
| `port` | `44446` | 与业务桥（默认 44445）错开 |
| `secret` | `horizon-debug` | 请求里的 `secret` 必须一致 |
| `capture-ms` | `800` | `exec` 的默认捕获窗口 |
| `allow-commands` | `[help]` | `exec` 允许的根命令白名单 |
| `prefixes` | `[]` | 回显行剥离的前缀 |
| `offline-permissions` | `[]` | 离线虚拟 sender 的权限前缀放行 |

### JSON 协议

连接 `bind:port`，发送一行 UTF-8 JSON，读一行 JSON 响应，连接即关：

```json
{"op":"exec","secret":"…","player":"Steve","command":"help 1","captureMs":3000}
{"op":"probe","secret":"…","channel":"SU|TabHF","player":"Steve"}
{"op":"status","secret":"…"}
{"op":"servers","secret":"…"}
```

- `exec`：以玩家身份执行代理命令并捕获回显（不发公屏）。在线玩家权限委托真实连接；
  离线玩家用虚拟 sender，按 `offline-permissions` 前缀放行。`captureMs` 100–10000，
  异步命令（搜歌）需足够长的窗口。限流：单玩家 1 秒 1 条、每分钟 30 条。
- `probe`：向插件消息信道发送探测负载。默认向 `SU|TabHF` 发一段故意截断的坏 JSON ——
  目标模组解析失败会打印 `Failed to parse …` 警告，客户端日志出现该警告即证明
  代理到客户端信道连通，问题在文案或渲染而非投递。`channel` / `payload` / `player`
  可覆盖默认值。
- `status`：插件自检（配置、运行时长、请求/限流/错误计数）。
- `servers`：代理子服清单（名称与端口），排查「按端口反查子服」一类问题。

错误码：`BAD_SECRET` / `BAD_PLAYER` / `BAD_COMMAND` / `COMMAND_NOT_ALLOWED:<root>` /
`PLAYER_NOT_ONLINE` / `UNKNOWN_OP` / `RATE_LIMITED` / `EXCEPTION:<描述>`。

## tools/debug_client.py

```bash
export HORIZON_DEBUG_SERVER=<服务端根目录>
export HORIZON_DEBUG_CLIENT=<客户端>/.minecraft
export HORIZON_DEBUG_BRIDGE=127.0.0.1:44446
export HORIZON_DEBUG_SECRET=horizon-debug

python tools/debug_client.py srv say hello          # 服务端执行 say hello
python tools/debug_client.py readsrv                # 读 cmd.out
python tools/debug_client.py cli connect 127.0.0.1 25565
python tools/debug_client.py readcli
python tools/debug_client.py status                 # 调试桥自检
python tools/debug_client.py probe                  # 探测 SU|TabHF 信道
```

## 上线约束

- 生产服（正式服）**不安装本 mod 与插件**；已安装的测试服转正式前，把 `[debug] enabled`
  保持为 false（默认值），并核对 BC 插件 `bind` 仍为 `127.0.0.1`。
- 本 mod 与 AEMobile / HorizonModServer 相互独立、可并存：产物目录
  `dumps/horizondebug/`、配置文件、命令名均不冲突。

## 版本

版本号由 git tag 提供（`v0.1.0` 起）；mod 构建无 tag 会直接失败。
