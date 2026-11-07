package dev.dsmnh.horizon.debug.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HorizonDebug 代理端调试桥（命令执行与探测逻辑移植自 AEMobileBridge）。
 *
 * <p>在代理上监听一个本地 TCP 端口，接收一行 JSON 请求、回一行 JSON 响应。
 * 支持四个诊断 op：{@code exec}（以玩家身份执行代理命令并捕获回显）、
 * {@code probe}（向插件消息信道发送探测负载，验证信道连通）、
 * {@code status}（插件自检）、{@code servers}（列出子服名与端口）。
 * 与业务桥（HorizonBridge / AEMobileBridge）相互独立，端口错开部署。
 */
public final class HorizonDebugBridge extends Plugin {

    /** 探测负载默认发往 ServerUtilities 的 TAB 信道（与 AEMobileBridge 的 tab_probe 一致） */
    public static final String DEFAULT_PROBE_CHANNEL = "SU|TabHF";

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_SECRET = "horizon-debug";
    private static final int DEFAULT_PORT = 44446;
    private static final int DEFAULT_CAPTURE_MS = 800;

    private final Map<String, long[]> rate = new ConcurrentHashMap<String, long[]>();
    private final Set<String> offlinePermissions = new HashSet<String>();
    private final Set<String> allowedCommands = new HashSet<String>();
    private final List<String> prefixes = new ArrayList<String>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private String bind = "127.0.0.1";
    private String secret = DEFAULT_SECRET;
    private int port = DEFAULT_PORT;
    private int defaultCaptureMs = DEFAULT_CAPTURE_MS;

    @Override
    public void onEnable() {
        loadConfig();
        startServer();
        getLogger().info("HorizonDebug listening on " + bind + ":" + port
                + " commands=" + allowedCommands + " prefixes=" + prefixes
                + " offlinePermissions=" + offlinePermissions);
    }

    @Override
    public void onDisable() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ config

    private void loadConfig() {
        try {
            File folder = getDataFolder();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                getLogger().warning("cannot create " + folder);
            }
            File file = new File(folder, "config.yml");
            ConfigurationProvider provider = ConfigurationProvider.getProvider(YamlConfiguration.class);
            if (!file.isFile()) {
                Configuration defaults = new Configuration();
                defaults.set("bind", "127.0.0.1");
                defaults.set("port", DEFAULT_PORT);
                defaults.set("secret", DEFAULT_SECRET);
                defaults.set("capture-ms", DEFAULT_CAPTURE_MS);
                defaults.set("allow-commands", Arrays.asList("help"));
                defaults.set("prefixes", new ArrayList<String>());
                defaults.set("offline-permissions", new ArrayList<String>());
                provider.save(defaults, file);
            }
            Configuration c = provider.load(file);
            bind = c.getString("bind", "127.0.0.1");
            port = c.getInt("port", DEFAULT_PORT);
            secret = c.getString("secret", DEFAULT_SECRET);
            defaultCaptureMs = c.getInt("capture-ms", DEFAULT_CAPTURE_MS);
            allowedCommands.clear();
            for (String s : c.getStringList("allow-commands")) {
                if (s != null && !s.trim().isEmpty()) allowedCommands.add(s.trim().toLowerCase(Locale.ROOT));
            }
            prefixes.clear();
            prefixes.addAll(c.getStringList("prefixes"));
            offlinePermissions.clear();
            offlinePermissions.addAll(c.getStringList("offline-permissions"));
        } catch (Throwable t) {
            getLogger().warning("config load failed, using defaults: " + t);
            errors.incrementAndGet();
        }
    }

    // ------------------------------------------------------------ server

    private void startServer() {
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "HorizonDebug-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bind, port));
        } catch (Throwable t) {
            getLogger().severe("cannot bind " + bind + ":" + port + " — " + t);
            return;
        }
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (Throwable t) {
                if (running) getLogger().warning("accept failed: " + t);
                break;
            }
            Thread worker = new Thread(new ClientHandler(socket), "HorizonDebug-client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(12000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String line = reader.readLine();
                if (line == null) return;
                JsonObject response = handle(line);
                writer.println(GSON.toJson(response));
            } catch (Throwable t) {
                errors.incrementAndGet();
                getLogger().warning("request failed: " + t);
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ handle

    private JsonObject handle(String line) {
        requests.incrementAndGet();
        try {
            JsonObject req = new JsonParser().parse(line).getAsJsonObject();
            String op = str(req, "op").trim().toLowerCase(Locale.ROOT);

            String givenSecret = str(req, "secret");
            if (secret != null && !secret.isEmpty() && !secret.equals(givenSecret)) {
                return error("BAD_SECRET");
            }

            if ("status".equals(op)) {
                return statusOut();
            }
            if ("servers".equals(op)) {
                return serversOut();
            }
            if ("probe".equals(op)) {
                return probeOut(req);
            }
            if (!op.isEmpty() && !"exec".equals(op)) return error("UNKNOWN_OP");

            return execOut(req);
        } catch (Throwable t) {
            errors.incrementAndGet();
            return error("EXCEPTION:" + t);
        }
    }

    /** 插件自检：配置、运行时长与计数器 */
    private JsonObject statusOut() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("name", getDescription().getName());
        out.addProperty("version", getDescription().getVersion());
        out.addProperty("bind", bind);
        out.addProperty("port", port);
        out.addProperty("uptimeMs", System.currentTimeMillis() - startedAt);
        JsonArray cmds = new JsonArray();
        for (String s : allowedCommands) cmds.add(new JsonPrimitive(s));
        out.add("allowCommands", cmds);
        JsonArray pre = new JsonArray();
        for (String s : prefixes) pre.add(new JsonPrimitive(s));
        out.add("prefixes", pre);
        JsonArray perms = new JsonArray();
        for (String s : offlinePermissions) perms.add(new JsonPrimitive(s));
        out.add("offlinePermissions", perms);
        out.addProperty("requests", requests.get());
        out.addProperty("rateLimited", rateLimited.get());
        out.addProperty("errors", errors.get());
        return out;
    }

    /** 列出代理配置的子服（名称与地址端口），排查「按端口反查子服」一类问题时使用 */
    private JsonObject serversOut() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray list = new JsonArray();
        for (ServerInfo info : getProxy().getServers().values()) {
            JsonObject one = new JsonObject();
            one.addProperty("name", info.getName());
            InetSocketAddress address = info.getAddress();
            one.addProperty("address", address == null ? "" : address.getAddress().getHostAddress());
            one.addProperty("port", address == null ? 0 : address.getPort());
            list.add(one);
        }
        out.add("servers", list);
        out.addProperty("online", getProxy().getOnlineCount());
        return out;
    }

    /**
     * 向插件消息信道发送探测负载：默认故意发一段截断的坏 JSON，目标模组解析失败会打印
     * "Failed to parse ..." 警告 —— 客户端日志出现该警告即说明代理到客户端的信道连通，
     * 问题在文案或渲染而非投递。可用 {@code channel} / {@code payload} 覆盖默认值，
     * 用 {@code player} 只探测单个玩家（缺省探测所有在线玩家）。
     */
    private JsonObject probeOut(JsonObject req) {
        String channel = req.has("channel") && !str(req, "channel").isEmpty()
                ? str(req, "channel") : DEFAULT_PROBE_CHANNEL;
        byte[] payload;
        if (req.has("payload") && !str(req, "payload").isEmpty()) {
            payload = str(req, "payload").getBytes(StandardCharsets.UTF_8);
        } else {
            payload = ("{\"probe\":\"horizondebug\"," + System.currentTimeMillis())
                    .getBytes(StandardCharsets.UTF_8);
        }
        int count = 0;
        String target = str(req, "player").trim();
        if (!target.isEmpty()) {
            ProxiedPlayer p = getProxy().getPlayer(target);
            if (p == null) {
                JsonObject out = error("PLAYER_NOT_ONLINE");
                out.addProperty("player", target);
                return out;
            }
            p.sendData(channel, payload);
            count = 1;
        } else {
            for (ProxiedPlayer p : getProxy().getPlayers()) {
                p.sendData(channel, payload);
                count++;
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("channel", channel);
        out.addProperty("probed", count);
        out.addProperty("payload", new String(payload, StandardCharsets.UTF_8));
        return out;
    }

    /**
     * 以某个玩家身份执行一条代理命令，捕获其回显（不发公屏）。
     *
     * <p>在线玩家：权限与分组委托真实 ProxiedPlayer；离线玩家：虚拟 sender，
     * 权限按 config 的 {@code offline-permissions} 前缀匹配放行。命令执行后等待
     * {@code captureMs}（100–10000，默认 {@code capture-ms}）收集异步返回的聊天行 ——
     * 搜歌一类的异步命令需要足够长的捕获窗口。
     */
    private JsonObject execOut(JsonObject req) {
        String playerName = str(req, "player").trim();
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) return error("BAD_PLAYER");

        String limited = rateCheck(playerName);
        if (limited != null) {
            rateLimited.incrementAndGet();
            return error(limited);
        }

        String command = str(req, "command").trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || command.length() > 512
                || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return error("BAD_COMMAND");
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!allowedCommands.contains(root)) return error("COMMAND_NOT_ALLOWED:" + root);

        int captureMs = req.has("captureMs") ? req.get("captureMs").getAsInt() : defaultCaptureMs;
        captureMs = Math.max(100, Math.min(10000, captureMs));

        ProxiedPlayer online = getProxy().getPlayer(playerName);
        CaptureSender sender = new CaptureSender(online, playerName, offlinePermissions);
        boolean found = getProxy().getPluginManager().dispatchCommand(sender, command);

        try {
            Thread.sleep(captureMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", found);
        out.addProperty("found", found);
        out.addProperty("online", online != null);
        if (!found) out.addProperty("error", "UNKNOWN_COMMAND");
        JsonArray arr = new JsonArray();
        for (String l : cleanLines(sender.lines())) arr.add(new JsonPrimitive(l));
        out.add("lines", arr);
        return out;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("found", false);
        out.addProperty("online", false);
        out.addProperty("error", message);
        out.add("lines", new JsonArray());
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    /** 去除 {@code §} 颜色码与 config 配置的前缀，再剔除空行 */
    private List<String> cleanLines(List<String> raw) {
        List<String> out = new ArrayList<String>();
        for (String l : raw) {
            if (l == null) continue;
            String s = l.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            for (String p : prefixes) {
                if (p != null && !p.isEmpty() && s.startsWith(p)) {
                    s = s.substring(p.length());
                    break;
                }
            }
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 单玩家 1 秒一条、每分钟 30 条 */
    private String rateCheck(String player) {
        long now = System.currentTimeMillis();
        long[] st = rate.get(player);
        if (st == null) {
            st = new long[]{0L, 0L, now};
            rate.put(player, st);
        }
        synchronized (st) {
            if (now - st[2] >= 60000L) {
                st[2] = now;
                st[1] = 0L;
            }
            if (st[1] >= 30) return "RATE_LIMITED";
            if (now - st[0] < 1000L) return "RATE_LIMITED";
            st[0] = now;
            st[1]++;
        }
        return null;
    }
}
