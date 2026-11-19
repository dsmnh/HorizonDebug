package dev.dsmnh.horizon.debug.control;

import dev.dsmnh.horizon.debug.HorizonDebug;
import dev.dsmnh.horizon.debug.api.ApiException;
import dev.dsmnh.horizon.debug.api.Json;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 客户端 JSONL 调试 API：{@code dumps/horizondebug/api-client.jsonl -> api-client-out.jsonl}。
 *
 * <p>与服务端 API 同构（同一套请求/响应形状与错误码），但读写的是客户端目录下的独立文件：
 * 单机时客户端与服务端共用 {@code dumps/horizondebug}，若也读写 {@code api.jsonl}，两边的
 * 监听线程会互相抢行。请求里的 {@code side} 可写 {@code client} 或省略。
 *
 * <p>监听线程只负责把完整行入队，真正的执行发生在客户端 tick（{@link #drain}），因为所有
 * 原版客户端调用都要求主线程。
 */
public final class ClientApi {

    private static final String IN_FILE = "api-client.jsonl";
    private static final String OUT_FILE = "api-client-out.jsonl";
    private static final long POLL_MS = 400L;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<String>();

    private static volatile boolean running;
    private static Thread watcher;
    private static File inFile;
    private static File outFile;

    private ClientApi() {
    }

    /** 由 {@code ClientProxy} 在配置加载后调用；{@code [control] enabled=false} 时不启动 */
    public static synchronized void start(File gameDir) {
        if (running) return;
        if (gameDir == null) return;
        if (!HorizonDebug.controlEnabled || !HorizonDebug.debugEnabled) {
            HorizonDebug.debug("control 通道未启用，客户端 JSONL API 不启动");
            return;
        }
        File dir = new File(gameDir, "dumps/horizondebug");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            HorizonDebug.warn("客户端 JSONL API 无法创建目录：" + dir.getPath());
            return;
        }
        inFile = new File(dir, IN_FILE);
        outFile = new File(dir, OUT_FILE);
        running = true;
        watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop();
            }
        }, "HorizonDebug-client-api");
        watcher.setDaemon(true);
        watcher.start();
        HorizonDebug.info("客户端 JSONL 调试 API 已启动：" + inFile.getPath());
    }

    public static synchronized void stop() {
        running = false;
        watcher = null;
        QUEUE.clear();
    }

    /** 客户端 tick 调用：主线程执行排队的请求 */
    public static void drain(Minecraft mc) {
        String line;
        while ((line = QUEUE.poll()) != null) {
            long t0 = System.currentTimeMillis();
            String response = handleLine(mc, line, t0);
            if (response != null) append(response);
        }
    }

    // ------------------------------------------------------------ 监听线程

    private static void watchLoop() {
        while (running) {
            try {
                String chunk = harvest(inFile);
                if (chunk != null) {
                    for (String line : chunk.split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        QUEUE.add(t);
                    }
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                HorizonDebug.debug("客户端 API 监听异常：" + t);
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** 取走文件里以换行结尾的完整行，剩余部分写回（与 DebugBridge 同一套切行策略） */
    private static String harvest(File file) throws IOException {
        if (file == null || !file.isFile()) return null;
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            long len = raf.length();
            if (len == 0L) return null;
            byte[] buf = new byte[(int) Math.min(len, 1 << 20)];
            raf.seek(0L);
            raf.readFully(buf);
            String text = new String(buf, UTF8);
            int cut = text.lastIndexOf('\n');
            if (cut < 0) return null;
            String done = text.substring(0, cut);
            String rest = text.substring(cut + 1);
            raf.setLength(0L);
            byte[] out = rest.getBytes(UTF8);
            raf.write(out);
            return done;
        } finally {
            raf.close();
        }
    }

    private static synchronized void append(String line) {
        if (outFile == null) return;
        try {
            FileOutputStream out = new FileOutputStream(outFile, true);
            try {
                out.write((line + "\n").getBytes(UTF8));
            } finally {
                out.close();
            }
        } catch (UnsupportedEncodingException e) {
            HorizonDebug.warn("客户端 API 写响应失败：" + e);
        } catch (IOException e) {
            HorizonDebug.warn("客户端 API 写响应失败：" + e);
        }
    }

    // ------------------------------------------------------------ 处理

    private static String handleLine(Minecraft mc, String line, long t0) {
        Object parsed = Json.tryParse(line);
        Map<String, Object> req = Json.asMap(parsed);
        if (req == null) {
            String raw = line.length() > 120 ? line.substring(0, 120) + "..." : line;
            return error("", "BAD_JSON", "请求必须是 JSON 对象：" + raw, t0);
        }
        String id = Json.str(req, "id", "");
        String side = Json.str(req, "side", "client").toLowerCase(Locale.ROOT);
        if ("server".equals(side) || "bot".equals(side)) {
            return error(id, "UNKNOWN_OP", "side=" + side + " 应发给服务端 API（api.jsonl）", t0);
        }
        String op = Json.str(req, "op", "");
        Map<String, Object> args = Json.asMap(req.get("args"));
        if (args == null) args = Json.asMap(req);
        try {
            Object result = dispatch(mc, op, args);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":").append(Json.write(id))
              .append(",\"ok\":true,\"ms\":").append(System.currentTimeMillis() - t0)
              .append(",\"result\":").append(Json.write(result)).append('}');
            return sb.toString();
        } catch (ApiException e) {
            return error(id, e.code(), e.getMessage(), t0);
        } catch (Throwable t) {
            return error(id, "INTERNAL", String.valueOf(t), t0);
        }
    }

    private static String error(String id, String code, String message, long t0) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(Json.write(id))
          .append(",\"ok\":false,\"ms\":").append(System.currentTimeMillis() - t0)
          .append(",\"error\":").append(Json.write(Json.obj("code", code, "message", message))).append('}');
        return sb.toString();
    }

    private static Object dispatch(Minecraft mc, String op, Map<String, Object> args) {
        String name = op == null ? "" : op.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("client.")) lower = lower.substring("client.".length());
        if (lower.startsWith("ctl.")) lower = lower.substring("ctl.".length());

        if ("ping".equals(lower)) return Json.obj("pong", Boolean.TRUE, "side", "client",
                "inGame", Boolean.valueOf(mc != null && mc.thePlayer != null));
        if (mc == null) throw new ApiException("NOT_IN_GAME", "客户端尚未初始化");

        // 主菜单也要能连服：自动化链路的第一环，必须排在 thePlayer 判空之前。
        if ("connect".equals(lower)) {
            String host = Json.str(args, "host", "127.0.0.1");
            Integer port = Json.integer(args, "port");
            int p = port == null ? 25565 : port.intValue();
            cpw.mods.fml.client.FMLClientHandler handler = cpw.mods.fml.client.FMLClientHandler.instance();
            handler.setupServerList();
            handler.connectToServer(mc.currentScreen,
                    new net.minecraft.client.multiplayer.ServerData("horizondebug-test", host + ":" + p));
            return Json.obj("connecting", host + ":" + p);
        }

        if ("state".equals(lower)) return detail(ClientActions.state(mc));
        if ("scan".equals(lower)) {
            Integer r = Json.integer(args, "radius");
            return detail(ClientActions.scan(mc, r == null ? 8 : r.intValue()));
        }
        if ("shot".equals(lower) || "screenshot".equals(lower)) {
            return detail(ClientActions.shot(mc, Json.str(args, "name", null)));
        }
        if ("inv".equals(lower) || "inventory".equals(lower)) return detail(ClientActions.inventory(mc));
        if ("keys".equals(lower)) return detail(ClientActions.keys(mc));
        if ("screen".equals(lower)) return detail(ClientActions.screen(mc));
        if ("names".equals(lower)) return detail(ClientActions.dumpNames());
        if ("open".equals(lower) || "open-screen".equals(lower)) {
            return fromResult(ClientActions.openScreen(mc, Json.str(args, "name", null)));
        }
        if ("events".equals(lower)) {
            Integer n = Json.integer(args, "count");
            List<String> lines = ClientEvents.recent(n == null ? 20 : n.intValue());
            return Json.obj("count", Integer.valueOf(lines.size()), "items", lines);
        }
        if ("events-clear".equals(lower)) {
            ClientEvents.clear();
            return Json.obj("cleared", Boolean.TRUE);
        }
        if ("script".equals(lower)) {
            Object steps = args == null ? null : args.get("steps");
            if (!(steps instanceof List)) throw new ApiException("BAD_ARGS", "steps 必须是数组");
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) steps;
            String r = ClientScript.start(mc, list);
            return fromResult(r);
        }
        if ("script-stop".equals(lower)) return fromResult(ClientScript.stop(mc));
        if ("script-status".equals(lower)) {
            List<String> log = ClientScript.recent(10);
            return Json.obj("status", ClientScript.status(), "running", Boolean.valueOf(ClientScript.isRunning()),
                    "log", log);
        }
        if ("key".equals(lower) || "press".equals(lower) || "release".equals(lower)) {
            String key = Json.str(args, "name", Json.str(args, "key", null));
            if (key == null) throw new ApiException("BAD_ARGS", "缺少 name");
            boolean down = "release".equals(lower) ? false : Json.bool(args, "down", true);
            return fromResult(ClientInput.press(mc, key, down));
        }
        if ("hotbar".equals(lower)) {
            Integer slot = Json.integer(args, "slot");
            if (slot == null) throw new ApiException("BAD_ARGS", "缺少 slot");
            return fromResult(ClientInput.hotbar(mc, slot.intValue()));
        }
        if ("look".equals(lower)) {
            Double x = Json.num(args, "x");
            if (x != null) {
                Double y = Json.num(args, "y");
                Double z = Json.num(args, "z");
                if (y == null || z == null) throw new ApiException("BAD_ARGS", "看向坐标需要 x/y/z");
                return fromResult(ClientInput.lookAt(mc, x.doubleValue(), y.doubleValue(), z.doubleValue()));
            }
            Double yaw = Json.num(args, "yaw");
            Double pitch = Json.num(args, "pitch");
            if (yaw == null || pitch == null) throw new ApiException("BAD_ARGS", "缺少 yaw/pitch");
            return fromResult(ClientInput.look(mc, yaw.floatValue(), pitch.floatValue()));
        }
        if ("turn".equals(lower)) {
            Double dyaw = Json.num(args, "dyaw");
            Double dpitch = Json.num(args, "dpitch");
            if (dyaw == null || dpitch == null) throw new ApiException("BAD_ARGS", "缺少 dyaw/dpitch");
            return fromResult(ClientInput.turn(mc, dyaw.floatValue(), dpitch.floatValue()));
        }
        if ("dig".equals(lower)) {
            return fromResult(ClientActions.dig(mc, Json.integer(args, "x"), Json.integer(args, "y"),
                    Json.integer(args, "z")));
        }
        if ("place".equals(lower)) {
            return fromResult(ClientActions.place(mc, Json.integer(args, "x"), Json.integer(args, "y"),
                    Json.integer(args, "z"), Json.integer(args, "face")));
        }
        if ("use".equals(lower)) return fromResult(ClientActions.use(mc));
        if ("attack".equals(lower)) {
            return fromResult(ClientActions.attack(mc, Json.str(args, "target", "crosshair")));
        }
        if ("click".equals(lower)) {
            Integer id = Json.integer(args, "id");
            if (id == null) throw new ApiException("BAD_ARGS", "缺少 id");
            return fromResult(ClientActions.clickButton(mc, id.intValue()));
        }
        if ("clickat".equals(lower)) {
            Integer x = Json.integer(args, "x");
            Integer y = Json.integer(args, "y");
            if (x == null || y == null) throw new ApiException("BAD_ARGS", "缺少 x/y");
            Integer button = Json.integer(args, "button");
            return fromResult(ClientActions.clickAt(mc, x.intValue(), y.intValue(),
                    button == null ? 0 : button.intValue()));
        }
        if ("close".equals(lower)) return fromResult(ClientActions.closeScreen(mc));
        if ("chat".equals(lower)) {
            String text = Json.str(args, "text", "");
            if (text.trim().isEmpty()) throw new ApiException("BAD_ARGS", "缺少 text");
            mc.thePlayer.sendChatMessage(text);
            return Json.obj("sent", Boolean.TRUE, "text", text);
        }
        if ("cmd".equals(lower) || "command".equals(lower)) {
            String line = Json.str(args, "command", Json.str(args, "text", ""));
            if (line.startsWith("/")) line = line.substring(1);
            if (line.trim().isEmpty()) throw new ApiException("BAD_ARGS", "缺少 command");
            int r = net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, line);
            if (r == 0) {
                mc.thePlayer.sendChatMessage("/" + line);
                return Json.obj("sent_to_server", Boolean.TRUE, "command", "/" + line);
            }
            return Json.obj("client_result", Integer.valueOf(r), "command", "/" + line);
        }
        throw new ApiException("UNKNOWN_OP", "未知 op：" + op);
    }

    private static Object detail(String result) {
        return fromResult(result);
    }

    /** 把控制层的 {@code ok: / bad-xxx: / not-found: } 结果转成 API 语义 */
    private static Object fromResult(String result) {
        if (result == null) return Json.obj("detail", "");
        String r = result.trim();
        String code = null;
        if (r.startsWith("ok:") || r.startsWith("ok=") || "ok".equals(r)) {
            String body = r.startsWith("ok:") ? r.substring(3).trim() : "ok";
            return Json.obj("detail", body);
        }
        if (r.startsWith("not-in-game")) code = "NOT_IN_GAME";
        else if (r.startsWith("not-found")) code = "NOT_FOUND";
        else if (r.startsWith("disabled")) code = "DISABLED";
        else if (r.startsWith("bad-") || r.startsWith("empty-") || r.startsWith("unknown-action")
                || r.startsWith("usage")) code = "BAD_ARGS";
        else if (r.startsWith("failed")) code = "FAILED";
        else if (r.startsWith("error")) code = "INTERNAL";
        if (code == null) return Json.obj("detail", r);
        throw new ApiException(code, r);
    }

    /** 供文本通道复用的行执行入口（不做 JSON 编解码） */
    public static String exec(Minecraft mc, String op, Map<String, Object> args, long t0) {
        try {
            Object result = dispatch(mc, op, args);
            return Json.write(result);
        } catch (ApiException e) {
            return "error[" + e.code() + "] " + e.getMessage();
        } catch (Throwable t) {
            return "error[INTERNAL] " + t;
        }
    }

    /** 队列里是否有待执行项（仅供诊断） */
    public static int pending() {
        return QUEUE.size();
    }

    /** 当前读写的文件路径，供日志与工具确认 */
    public static String paths() {
        List<String> parts = new ArrayList<String>();
        parts.add(inFile == null ? "(未启动)" : inFile.getPath());
        parts.add(outFile == null ? "(未启动)" : outFile.getPath());
        return parts.get(0) + " -> " + parts.get(1);
    }
}
