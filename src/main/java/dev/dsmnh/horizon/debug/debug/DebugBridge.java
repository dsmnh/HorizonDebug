package dev.dsmnh.horizon.debug.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.dsmnh.horizon.debug.HorizonDebug;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 文件驱动的命令桥（移植自 AEMobile 同名类）。
 *
 * <p>向 {@code dumps/horizondebug/cmd.txt} 写一行命令，mod 在下一个 tick 执行，
 * 结果追加到同目录 {@code cmd.out}；客户端通道为 {@code cmd-client.txt} / {@code cmd-client.out}，
 * 由 {@link ClientDebugBridge} 在客户端 tick 里执行。仅 {@code [debug] enabled=true} 时启动监听线程，
 * 默认关闭。命令执行必须回主线程（1.7.10 没有 addScheduledTask）：服务端命令在
 * {@link TickEvent.ServerTickEvent} 的 END 阶段排空，客户端命令在客户端 tick 里执行。
 */
public final class DebugBridge {

    private static final String DIR = "dumps/horizondebug";
    private static final String CMD_FILE = "cmd.txt";
    private static final String CMD_CLIENT_FILE = "cmd-client.txt";
    private static final String OUT_FILE = "cmd.out";
    private static final String OUT_CLIENT_FILE = "cmd-client.out";
    /** 轮询间隔：响应及时与磁盘开销之间的折中值 */
    private static final long POLL_MS = 800L;

    private static final ConcurrentLinkedQueue<String> SERVER_QUEUE = new ConcurrentLinkedQueue<String>();
    private static final ConcurrentLinkedQueue<String> CLIENT_QUEUE = new ConcurrentLinkedQueue<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    private static volatile boolean running;
    private static Thread watcher;
    private static File serverDir;
    private static File clientDir;

    private DebugBridge() {
    }

    /** 由 CommonProxy.serverStarted 调用（此时 MinecraftServer 已就绪） */
    public static void startServer() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        // 目录无论如何都记下来：tps-compare.txt / dims.txt 由 /horizondebug 写入，不依赖 debug 开关
        serverDir = server.getFile(DIR);
        if (!HorizonDebug.debugEnabled) return;
        ensureWatcher("server");
    }

    /** 由 ClientProxy.init 调用（客户端目录 + 客户端命令通道） */
    public static void startClient(File gameDir) {
        clientDir = new File(gameDir, DIR);
        if (!HorizonDebug.debugEnabled) return;
        ensureWatcher("client");
    }

    public static void stop() {
        running = false;
        watcher = null;
        SERVER_QUEUE.clear();
        CLIENT_QUEUE.clear();
    }

    /** 服务端输出目录（tps-compare.txt / dims.txt 也写这里）；没有就返回 null */
    public static File serverDir() {
        return serverDir;
    }

    private static synchronized void ensureWatcher(String tag) {
        if (running) return;
        running = true;
        watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop();
            }
        }, "HorizonDebug-debug-" + tag);
        watcher.setDaemon(true);
        watcher.start();
        HorizonDebug.info("[HorizonDebug] debug 命令桥已启动（" + tag + "）；默认关闭，修改 config 后重启生效");
    }

    // ------------------------------------------------------------ 执行

    /** 在服务端 tick 里调用：把排队的命令交给命令管理器 */
    public static void drainOnServerThread(MinecraftServer server) {
        if (!running || SERVER_QUEUE.isEmpty()) return;
        File out = serverDir == null ? null : new File(serverDir, OUT_FILE);
        String line;
        while ((line = SERVER_QUEUE.poll()) != null) {
            long t0 = System.currentTimeMillis();
            String result;
            try {
                int r = server.getCommandManager().executeCommand(server, line);
                result = "result=" + r;
            } catch (Throwable t) {
                result = "error=" + t;
            }
            append(out, line, result, System.currentTimeMillis() - t0);
        }
    }

    /** 在客户端 tick 里取一条待执行的命令（没有就返回 null） */
    public static String pollClient() {
        if (!running) return null;
        return CLIENT_QUEUE.poll();
    }

    /** 客户端执行完回写结果 */
    public static void appendClient(String cmd, String result, long ms) {
        if (clientDir == null) return;
        append(new File(clientDir, OUT_CLIENT_FILE), cmd, result, ms);
    }

    // ------------------------------------------------------------ 文件监听

    private static void watchLoop() {
        File serverCmd = serverDir == null ? null : new File(serverDir, CMD_FILE);
        File clientCmd = clientDir == null ? null : new File(clientDir, CMD_CLIENT_FILE);
        while (running) {
            try {
                harvest(serverCmd, SERVER_QUEUE);
                harvest(clientCmd, CLIENT_QUEUE);
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // 监听线程不得退出：出现异常时等待后继续轮询
                HorizonDebug.debug("[HorizonDebug] debug 监听异常: " + t);
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** 读走文件里「完整的行」，未写完的尾部留在原处等待下次拼接 */
    private static void harvest(File cmdFile, ConcurrentLinkedQueue<String> queue) throws Exception {
        if (cmdFile == null || !cmdFile.isFile() || cmdFile.length() == 0) return;
        String content = read(cmdFile);
        int cut = content.lastIndexOf('\n');
        String complete = cut < 0 ? "" : content.substring(0, cut + 1);
        String rest = cut < 0 ? content : content.substring(cut + 1);
        write(cmdFile, rest);
        for (String raw : complete.split("\n")) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            queue.add(line);
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

    private static synchronized void append(File f, String cmd, String result, long ms) {
        if (f == null) return;
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            try {
                w.write("[" + TIME.format(new Date()) + "] > " + cmd + "\n");
                w.write("[" + TIME.format(new Date()) + "] < " + result + " (" + ms + "ms)\n");
            } finally {
                w.close();
            }
        } catch (Exception e) {
            HorizonDebug.debug("[HorizonDebug] debug 输出写入失败: " + e);
        }
    }

    /** 服务端 tick 事件：只负责把队列里的命令移交主线程执行 */
    public static final class ServerTickHandler {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) drainOnServerThread(server);
        }
    }
}
