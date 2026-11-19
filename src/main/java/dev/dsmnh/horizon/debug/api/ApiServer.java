package dev.dsmnh.horizon.debug.api;

import dev.dsmnh.horizon.debug.HorizonDebug;
import dev.dsmnh.horizon.debug.bot.FakePlayerActions;
import dev.dsmnh.horizon.debug.bot.FakePlayerFactory;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 服务端 JSONL 调试 API：{@code dumps/horizondebug/api.jsonl} 进、{@code api-out.jsonl} 出。
 *
 * <p>请求一行一个 JSON 对象：{@code {"id":"1","op":"bot.spawn","args":{"name":"01"}}}；
 * 响应也是单行 JSON，{@code id} 原样回显，便于脚本按 id 配对。空行与 {@code #} 开头的行忽略。
 *
 * <p>执行必须在服务端主线程（1.7.10 没有线程队列 API），因此这里的监听线程只负责读文件入队，
 * 真正的执行由 {@code FakePlayerTick} 在 tick 末尾调用 {@link #drain(MinecraftServer)} 完成。
 */
public final class ApiServer {

    private static final String IN_FILE = "api.jsonl";
    private static final String OUT_FILE = "api-out.jsonl";
    private static final long POLL_MS = 500L;

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<String>();

    private static volatile boolean running;
    private static Thread watcher;
    private static File dir;

    private ApiServer() {
    }

    /** 启动监听；{@code [debug] jsonApi=false} 时不启动 */
    public static synchronized void start(File serverDir) {
        if (running || serverDir == null) return;
        if (!HorizonDebug.jsonApi) return;
        dir = serverDir;
        running = true;
        watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop();
            }
        }, "HorizonDebug-api");
        watcher.setDaemon(true);
        watcher.start();
        HorizonDebug.info("[HorizonDebug] JSONL 调试 API 已启动：" + new File(dir, IN_FILE).getPath());
    }

    public static synchronized void stop() {
        running = false;
        watcher = null;
        QUEUE.clear();
    }

    /** 在服务端主线程执行排队的请求 */
    public static void drain(MinecraftServer server) {
        if (!running || QUEUE.isEmpty()) return;
        String line;
        while ((line = QUEUE.poll()) != null) {
            handleLine(server, line);
        }
    }

    private static void watchLoop() {
        File in = new File(dir, IN_FILE);
        File out = new File(dir, OUT_FILE);
        while (running) {
            try {
                harvest(in);
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                HorizonDebug.debug("[HorizonDebug] API 监听异常: " + t);
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** 读走完整行，未写完的尾部留回文件 */
    private static void harvest(File in) throws Exception {
        if (!in.isFile() || in.length() == 0) return;
        String content = read(in);
        int cut = content.lastIndexOf('\n');
        if (cut < 0) return;
        String complete = content.substring(0, cut + 1);
        String rest = content.substring(cut + 1);
        write(in, rest);
        for (String raw : complete.split("\n")) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            QUEUE.add(line);
        }
    }

    // ------------------------------------------------------------ 执行

    private static void handleLine(MinecraftServer server, String line) {
        long t0 = System.currentTimeMillis();
        Object parsed = Json.tryParse(line);
        Map<String, Object> req = Json.asMap(parsed);
        if (req == null) {
            reply(Json.obj("id", "", "ok", Boolean.FALSE, "ms", Long.valueOf(0),
                    "error", Json.obj("code", "BAD_JSON", "message", "请求不是合法的 JSON 对象",
                            "raw", abbreviate(line))));
            return;
        }
        String id = Json.str(req, "id", "");
        String op = Json.str(req, "op", "");
        String side = Json.str(req, "side", "server");
        Map<String, Object> args = Json.asMap(req.get("args"));
        if (args == null) args = Json.obj();

        try {
            if ("client".equalsIgnoreCase(side)) {
                throw new ApiException("UNKNOWN_OP", "side=client 由客户端通道处理，服务端 API 不接");
            }
            Object result = dispatch(server, op, args);
            reply(Json.obj("id", id, "ok", Boolean.TRUE,
                    "ms", Long.valueOf(System.currentTimeMillis() - t0), "result", result));
        } catch (ApiException e) {
            reply(Json.obj("id", id, "ok", Boolean.FALSE,
                    "ms", Long.valueOf(System.currentTimeMillis() - t0),
                    "error", Json.obj("code", e.code(), "message", e.getMessage())));
        } catch (Throwable t) {
            reply(Json.obj("id", id, "ok", Boolean.FALSE,
                    "ms", Long.valueOf(System.currentTimeMillis() - t0),
                    "error", Json.obj("code", "INTERNAL", "message", describe(t))));
        }
    }

    private static Object dispatch(MinecraftServer server, String op, Map<String, Object> args) {
        if (op == null || op.isEmpty()) throw new ApiException("BAD_ARGS", "缺少 op 字段");
        if ("ping".equals(op)) {
            return Json.obj("pong", Boolean.TRUE, "side", "server");
        }
        if ("server.status".equals(op)) {
            return Json.obj("bots", Integer.valueOf(FakePlayerFactory.count()),
                    "max", Integer.valueOf(HorizonDebug.botMax),
                    "botEnabled", Boolean.valueOf(HorizonDebug.botEnabled));
        }
        if (op.startsWith("bot.")) {
            return botOp(server, op.substring(4), args);
        }
        throw new ApiException("UNKNOWN_OP", "未知 op：" + op);
    }

    private static Object botOp(MinecraftServer server, String action, Map<String, Object> args) {
        if (!HorizonDebug.botEnabled) throw new ApiException("BAD_ARGS", "假玩家未启用（config [bot] enabled）");
        // list 不需要 name：先处理，避免调用方为了查列表编一个名字
        if ("list".equals(action)) {
            List<Object> items = new ArrayList<Object>();
            for (String line : FakePlayerFactory.list()) items.add(line);
            return Json.obj("count", Integer.valueOf(FakePlayerFactory.count()), "items", items);
        }
        String name = Json.str(args, "name", null);
        if (name == null || name.isEmpty()) throw new ApiException("BAD_ARGS", "缺少 name");
        // despawn all 用原始值判断：加前缀后 "all" 会被改写成 "Bot_all"
        if ("despawn".equals(action) && "all".equalsIgnoreCase(name)) {
            List<String> names = FakePlayerFactory.names();
            for (String one : names) FakePlayerFactory.despawn(server, one);
            return Json.obj("despawned", Integer.valueOf(names.size()));
        }
        name = withPrefix(name);

        if ("spawn".equals(action)) {
            if (FakePlayerFactory.count() >= HorizonDebug.botMax) {
                throw new ApiException("LIMIT", "已达上限 " + HorizonDebug.botMax);
            }
            String r = FakePlayerFactory.spawn(server, name,
                    Json.integer(args, "dim"), Json.num(args, "x"), Json.num(args, "y"), Json.num(args, "z"));
            return fromResult(r);
        }
        if ("despawn".equals(action)) {
            return fromResult(FakePlayerFactory.despawn(server, name));
        }
        if ("list".equals(action)) {
            List<Object> items = new ArrayList<Object>();
            for (String line : FakePlayerFactory.list()) items.add(line);
            return Json.obj("count", Integer.valueOf(FakePlayerFactory.count()), "items", items);
        }
        if ("say".equals(action)) {
            String text = Json.str(args, "text", null);
            if (text == null || text.isEmpty()) throw new ApiException("BAD_ARGS", "缺少 text");
            String r = FakePlayerFactory.say(name, text);
            // 聊天走独立线程（Crucible 禁止主线程触发异步聊天事件），这里只确认已投递
            if (r.startsWith("queued")) {
                return Json.obj("detail", r.length() > 7 ? r.substring(7) : "", "async", Boolean.TRUE);
            }
            return fromResult(r);
        }
        // 其余动作用统一入口：state/inv/hand/move/stop/tp/jump/sneak/sprint/look/dig/place/use/attack/give/cmd
        List<String> argv = new ArrayList<String>();
        argv.add(name);
        if ("tp".equals(action)) {
            addNum(argv, args, "x");
            addNum(argv, args, "y");
            addNum(argv, args, "z");
            addNum(argv, args, "dim");
        } else if ("move".equals(action)) {
            addNum(argv, args, "forward");
            addNum(argv, args, "strafe");
            addNum(argv, args, "ticks");
        } else if ("look".equals(action)) {
            addNum(argv, args, "yaw");
            addNum(argv, args, "pitch");
        } else if ("dig".equals(action) || "place".equals(action) || "use".equals(action)) {
            addNum(argv, args, "x");
            addNum(argv, args, "y");
            addNum(argv, args, "z");
            if ("dig".equals(action)) {
                if (Json.bool(args, "force", false)) argv.add("on");
            } else {
                addNum(argv, args, "face");
            }
        } else if ("attack".equals(action)) {
            String target = Json.str(args, "target", "nearest");
            argv.add(target);
        } else if ("give".equals(action)) {
            String item = Json.str(args, "item", null);
            if (item == null) throw new ApiException("BAD_ARGS", "缺少 item");
            argv.add(item);
            addNum(argv, args, "count");
            addNum(argv, args, "meta");
        } else if ("sneak".equals(action) || "sprint".equals(action)) {
            if (args.containsKey("on")) argv.add(Json.bool(args, "on", true) ? "on" : "off");
        } else if ("cmd".equals(action)) {
            String command = Json.str(args, "command", null);
            if (command == null) throw new ApiException("BAD_ARGS", "缺少 command");
            for (String part : command.split(" ")) argv.add(part);
        }
        return fromResult(FakePlayerActions.handle(action, argv.toArray(new String[argv.size()])));
    }

    private static void addNum(List<String> argv, Map<String, Object> args, String key) {
        Double v = Json.num(args, key);
        if (v == null) return;
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            argv.add(String.valueOf(v.longValue()));
        } else {
            argv.add(String.valueOf(v));
        }
    }

    /** 把 {@code ok: ...} 转成 {@code {"ok":true,"detail":"..."}}，其余抛对应错误码 */
    private static Object fromResult(String r) {
        if (r == null) throw new ApiException("INTERNAL", "空结果");
        if (r.startsWith("ok")) {
            return Json.obj("detail", r.length() > 4 ? r.substring(4) : "");
        }
        if (r.startsWith("not-found")) throw new ApiException("NOT_FOUND", r);
        if (r.startsWith("bad-") || r.startsWith("usage") || r.startsWith("empty-")
                || r.startsWith("unknown-action") || r.startsWith("exists")) {
            throw new ApiException("BAD_ARGS", r);
        }
        if (r.startsWith("not-on-ground")) throw new ApiException("BAD_ARGS", r);
        if (r.startsWith("queued")) throw new ApiException("INTERNAL", "不应出现：投递类结果需单独处理");
        throw new ApiException("INTERNAL", r);
    }

    private static String withPrefix(String raw) {
        String prefix = HorizonDebug.botPrefix == null ? "" : HorizonDebug.botPrefix;
        if (prefix.isEmpty() || raw.startsWith(prefix)) return raw;
        return prefix + raw;
    }

    private static void reply(Map<String, Object> obj) {
        if (dir == null) return;
        append(new File(dir, OUT_FILE), Json.write(obj));
    }

    private static synchronized void append(File f, String line) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            try {
                w.write(line);
                w.write("\n");
            } finally {
                w.close();
            }
        } catch (Exception e) {
            HorizonDebug.debug("[HorizonDebug] API 输出写入失败: " + e);
        }
    }

    private static String read(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        return sb.toString();
    }

    private static void write(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) sb.append(" @ ").append(st[0]);
        return sb.toString();
    }
}
