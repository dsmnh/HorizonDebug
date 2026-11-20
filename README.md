# HorizonDebug

Horizon 生态的调试功能独立仓库：把 AEMobile 客户端、服务端与 BC 代理端的调试能力剥离成
独立产物，生产环境不装、测试环境装齐即可自动化联调。移植自 AEMobile（MIT）的调试代码，
许可证见 [LICENSE](LICENSE)。

## 组成

| 产物 | 形态 | 内容 |
|---|---|---|
| `horizondebug` mod | Forge 1.7.10（服务端 + 客户端同一个 jar） | 文件命令桥（服务端 / 客户端两条通道）+ `/horizondebug` 诊断命令 + TPS/MSPT 采集 + 假玩家 + 客户端操控 + JSONL 调试 API |
| `HorizonDebugBridge` | BungeeCord/HexaCord 插件（Java 8） | JSON TCP 调试桥：`exec` / `probe` / `status` / `servers` 四个诊断 op |
| `tools/debug_client.py` | Python 脚本 | 驱动文件命令桥与调试桥的命令行客户端 |
| `tools/hdebug.py` | Python 脚本 | 驱动 JSONL 调试 API（假玩家与客户端操控）的命令行入口 |

```
├── build.gradle 等          Forge mod 的 Gradle 工程（GTNH ExampleMod1.7.10 模板）
├── src/main/java/...        mod 源码（dev.dsmnh.horizon.debug）
├── bridge/                  BC 插件源码（dev.dsmnh.horizon.debug.bridge）+ build.ps1
└── tools/                   调试客户端脚本（debug_client.py 文件桥；hdebug.py JSONL API）
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

## mod：假玩家（bot）

在服务端进程内注入真实 `EntityPlayerMP`（不使用外部协议客户端），假玩家会出现在 `/list`
与玩家列表、触发 `PlayerLoggedInEvent`，可聊天、执行命令、移动、被攻击。默认关闭。

```ini
[bot]
    B:enabled=true       # 总开关
    I:max=10             # 同时在线上限
    S:prefix=Bot_        # 名称前缀，便于业务模组与真实玩家区分
    B:invulnerable=true  # 免伤害与免饥饿
    B:auto-op=true       # 登录即给 OP，登出收回（业务命令要权限 2）
```

`auto-op` 让假玩家能直接跑 `/aemobile`、`/cofh` 这类权限 2 的命令，省掉手工
`op`。授权走 `ServerConfigurationManager.func_152605_a`（addOp），登出走
`func_152610_b`（removeOp），所以 `ops.json` 只在进程被强杀时才可能残留。
公开服应与 `bot.enabled` 一起关掉。

命令：`/horizondebug bot spawn <name> [dim] [x y z]`、`despawn <name>|all`、`list`、
`say <name> <text>`，以及动作 `state` / `inv` / `hand` / `move` / `stop` / `tp` / `jump` /
`sneak` / `sprint` / `look` / `dig` / `place` / `use` / `attack` / `give` / `cmd` /
`op` / `deop` / `respawn`。

`respawn` 走「登出再登录」而不是 `C16PacketClientStatus`：1.7.10 的 PERFORM_RESPAWN
分支在血量仍大于 0 时直接返回，被 `/kill` 的假玩家血量虽满但 `isDead` 为真，
只有重新登录才能恢复可用状态。

聊天走非主线程投递：Crucible（Cauldron 系）要求在异步线程触发 `AsyncPlayerChatEvent`，
主线程直接调用会被拒绝并抛 `IllegalStateException`，故 `say` 交给独立守护线程执行，
结果由服务端 tick 回写日志。

## mod：客户端操控（client control）

驱动**真实客户端**的输入层：直接置位 `KeyBinding`、覆盖 `movementInput` 做移动、
写旋转字段并重算准星、调用 `PlayerControllerMP` 挖放用攻击、反射点 GUI 按钮、截图。
默认关闭。

```ini
[control]
    B:enabled=false          # 总开关
    B:allow-gui=true         # 允许读/点 GUI
    B:allow-click=true       # 允许挖/放/使用
    B:allow-screenshot=true  # 允许截图
    I:script-max-steps=200   # 单次脚本步数上限
```

- 1.7.10 运行期 `GuiScreen` 与 `Minecraft.debugFPS` 等成员是混淆名（`field_xxxxxx` /
  `func_xxxxxx`），代码一律通过「候选名依次尝试」的反射助手读取，不绑定单一名字。
- 截图走 `ScreenShotHelper.saveScreenshot`，产物固定在 `<dir>/screenshots/<name>`，
  因此文件名由调用方补 `.png`，落到 `dumps/horizondebug/shots/screenshots/`。
- 窗口失焦时关闭 `pauseOnLostFocus`，避免每次失焦弹暂停菜单打断脚本。

## mod：JSONL 调试 API

`[debug] enabled=true` 且 `jsonApi=true` 时启动。请求/响应为一行一条 JSON：

| 文件 | 侧 |
|---|---|
| `api.jsonl` → `api-out.jsonl` | 服务端（假玩家） |
| `api-client.jsonl` → `api-client-out.jsonl` | 客户端（操控） |

```json
{"id":"a1","side":"server","op":"bot.spawn","args":{"name":"Bot_01","dim":0}}
{"id":"a2","side":"client","op":"look","args":{"yaw":90,"pitch":10}}
```

响应 `{"id","ok":true,"ms","result":{...}}` 或 `{"id","ok":false,"error":{"code","message"}}`。
错误码：`BAD_JSON` / `UNKNOWN_OP` / `BAD_ARGS` / `NOT_IN_GAME` / `NOT_FOUND` / `LIMIT` /
`BUSY` / `DISABLED` / `FAILED` / `INTERNAL`。两侧分文件，单机（客户端兼服务端）也不会抢行。

op 一览：

- 服务端观测：`ping`、`server.status`、`server.tps`、`server.dims`、`server.players`。
  `server.tps` 返回总览与逐世界 MSPT/TPS/实体/区块；`server.players` 把在线者分成
  `real` 与 `bots` 两组，便于脚本断言「真玩家进了服」或「假玩家都在线」。
- 服务端假玩家：`bot.spawn`、`bot.despawn`、`bot.list`、`bot.spawnmany`（一次拉起
  `count` 个，参数 `prefix`/`count`/可选 `dim`/`x`/`y`/`z`）、`bot.op`/`bot.deop`、
  `bot.respawn`、`bot.say` 与全部 bot 动作。
  `bot.cmd` 会把命令的回显一并带回（`result.detail` 是首行，`result.lines` 是其余各行），
  所以能直接读到 `/gt chunks`、`/aemobile` 这类命令的输出，而不是只拿到一个返回码。
- 客户端：`ping`、`state`、`scan`、`inv`、`keys`、`shot`、`screen`、`open`、`click`、
  `clickat`、`close`、`events`、`events-clear`、`key`、`press`、`release`、`hotbar`、`look`、
  `turn`、`dig`、`place`、`use`、`attack`、`chat`、`cmd`、`connect`、`script`、
  `script-stop`、`script-status`、`chatlog`、`chatjson`。`inv` 读背包，`keys` 打出
  当前键位绑定的键码，用它能查出本机整合包把「前进」改到了哪个键。`chatlog`
  返回最近若干条聊天（纯文本），`chatjson` 多带一份原始 JSON 组件，二者都只读
  客户端本地缓存（上限 120 条），不依赖服务端。

动作脚本为时间轴数组，每步只允许一个动作键（`at` 除外），`at` 为相对毫秒：

```json
{"op":"script","args":{"steps":[
  {"at":0,    "key":{"name":"forward","down":true}},
  {"at":900,  "key":{"name":"forward","down":false}},
  {"at":1000, "look":{"yaw":90,"pitch":5}},
  {"at":1200, "chat":"队伍测试"}
]}}
```

## tools/hdebug.py

JSONL 统一入口（`[debug] enabled` 开启即可用，无需 RCON）：

```bash
export HORIZON_DEBUG_SERVER=/path/to/server
export HORIZON_DEBUG_CLIENT=/path/to/instance/.minecraft

python tools/hdebug.py srv bot.spawn name=1
python tools/hdebug.py srv bot.list
python tools/hdebug.py srv bot.say name=Bot_1 text=hello
python tools/hdebug.py cli state
python tools/hdebug.py cli look yaw=90 pitch=10
python tools/hdebug.py cli script 'steps=[{"at":0,"key":{"name":"forward","down":true}}]'
python tools/hdebug.py srv-tail 20     # 或 cli-tail / srv-raw / cli-raw
```

`key=value` 的 value 先按 JSON 解析（`true` / `10` / 对象），失败再当字符串；
`HORIZON_DEBUG_TIMEOUT` 控制等待秒数（默认 15）。

## tools/hscenario.py

把一串请求写成场景文件一次跑完，按断言判定成败（退出码 0 表示全通过）：

```bash
python tools/hscenario.py tools/scenarios/bot-basic.json      # 假玩家基础动作
python tools/hscenario.py tools/scenarios/client-basic.json   # 客户端操控基础
python tools/hscenario.py tools/scenarios/cross-e2e.json      # 假玩家与真客户端并存
python tools/hscenario.py tools/scenarios/business-aemobile.json  # 业务命令：权限、TPS、维度、重生
python tools/hscenario.py tools/scenarios/business-chat.json      # 业务广播落到真客户端聊天栏
python tools/hscenario.py tools/scenarios/business-team.json      # ServerUtilities 组队：建队、邀请、加入、退出、解散
python tools/hscenario.py tools/scenarios/business-rank.json      # 榜单记分板：建榜、写分、累加、侧栏显示
python tools/hscenario.py tools/scenarios/business-me.json        # ME 设备登记：register / list / unregister
python tools/hscenario.py tools/scenarios/business-eu.json        # GT 全局能量网络入网与退网
```

每步只做一件事：`srv` / `cli`（op + args）、`sleep`（毫秒），或 `wait`（轮询到条件成立）。
`expect` 是断言列表，每项含点号路径 `path` 与 `eq` / `contains` / `exists` 之一：

```json
{"srv":"bot.state","args":{"name":"Bot_sc1"},
 "expect":[{"path":"result.detail","contains":"sneaking=true"}]}
```

跨秒的事件（等客户端进服、等世界加载、等异步命令回显）用 `wait` 而不是固定 `sleep`：
它按 `interval` 反复请求同一个 op，直到 `expect` 全部通过才继续，超时则记该步失败并给出
最后一次响应的差异。`timeout` 是该步的总预算（毫秒），默认 30000。

```json
{"wait":{"cli":"state","timeout":120000,"interval":1500,
         "expect":[{"path":"result.detail","contains":"name="}]}}
```

`--json` 输出完整报告（含每条响应），便于脚本二次处理。仓储内九个场景覆盖假玩家
基本动作、客户端观测与截图、两侧并存的端到端链路，以及五条业务链路：
`business-aemobile` 验证权限门（无 OP 被拒 → `bot.op` 放行 → `bot.deop` 再拒）、
TPS 与维度报表、重生；`business-chat` 验证 `/aemobile say` 的广播确实出现在真客户端
聊天栏，而 `/aemobile debug chat` 的注入只回 `seq`（走 App 聊天平面，不进游戏内聊天栏）；
`business-team` 走 ServerUtilities 的 `/team`，邀请必须先用 `team status_for <队> <玩家> invited`
再由被邀请者 `team join`（直接 join 会假成功：返回 0 且回显「已是组员」但队伍未变）；
`business-rank` 用原版记分板覆盖榜单链路（1.7.10 的 `/scoreboard players` 只有
set/add/remove/reset/list，没有 `operation`）；`business-me` 用 `/dsmnhmobile me` 做
ME 设备登记，坐标取执行者当前位置、清单格式为 `设备ID(dim:x,y,z)`（需服端装 HorizonModServer）；
`business-eu` 验证契约 `eu.join` 的模板命令 `/gt global_energy_join <玩家> <网络>`——入网回
`Success! … has joined …`，退网（重新加入自己名下网络）回 `has rejoined their own global energy network.`。

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
{"op":"players","secret":"…"}
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
- `players`（别名 `list`）：代理侧在线玩家，含所在子服与端口、Ping、是否 Forge 客户端
  与 mod 数量、UUID。与 mod 侧 `server.players` 分工：这里看「代理认不认识这个人」，
  那里看「子服里有没有这个实体」；两边对不上时问题在代理到子服的转发。

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
