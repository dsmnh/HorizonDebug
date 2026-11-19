package dev.dsmnh.horizon.debug.bot;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 假玩家工厂：在服务端进程内注入 {@link EntityPlayerMP}，使其与原版登录收尾流程完全一致。
 *
 * <p>注入步骤（对应设计文档的阶段 0 验证点）：
 * <ol>
 *   <li>用 {@link EmbeddedChannel} 充当连接，调用 {@link NetworkManager#channelActive} 让
 *       网络管理器持有真实 channel —— 1.7.10 的 {@code setConnectionState} 内部要读
 *       {@code channel.attr(...)}，没有 channel 会抛空指针；</li>
 *   <li>把连接状态直接置为 {@code PLAY}，跳过登录与 FML 握手（握手只影响模组列表校验，
 *       不影响玩家进入世界）；</li>
 *   <li>构造 {@link NetHandlerPlayServer}（构造器会回填 playerNetServerHandler）；</li>
 *   <li>交给 {@code ServerConfigurationManager.initializeConnectionToPlayer}，由原版完成
 *       玩家数据读取、加入玩家列表、触发 PlayerLoggedInEvent 与初始包下发。</li>
 * </ol>
 *
 * <p>出站包写入 {@link EmbeddedChannel} 的队列后无人消费，必须每 tick 排空，否则堆积占内存。
 * 该职责由 {@link FakePlayerTick} 承担。
 *
 * <p>所有方法都必须在服务端主线程调用（命令执行与 tick 阶段天然满足）。
 */
public final class FakePlayerFactory {

    /** 名称（小写）到假玩家的映射；保持插入顺序便于列举 */
    private static final Map<String, Bot> BOTS = new LinkedHashMap<String, Bot>();

    /**
     * 聊天投递线程。
     *
     * <p>gtnh1 跑在 Crucible（Cauldron 系）上，Bukkit 的异步事件
     * （{@code AsyncPlayerChatEvent}）不允许在主线程触发，否则抛
     * {@code IllegalStateException: AsyncPlayerChatEvent cannot be triggered asynchronously
     * from primary server thread}。原版 {@code processChatMessage} 内部会走
     * {@code ServerConfigurationManager.sendChatMsg} → Bukkit 事件，因此只能换线程执行。
     *
     * <p>主线程此时若阻塞等待会拖慢 tick，所以采用「投递即返回」，结果经
     * {@link #takeChatResults()} 交给 tick 处理器写日志。
     */
    private static final ExecutorService CHAT_WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "HorizonDebug-Chat");
            t.setDaemon(true);
            return t;
        }
    });

    /** 聊天投递结果（name + 结论），由 tick 处理器取走并写日志 */
    private static final ConcurrentLinkedQueue<String> CHAT_RESULTS = new ConcurrentLinkedQueue<String>();

    private FakePlayerFactory() {
    }

    /** 一个假玩家的全部句柄 */
    public static final class Bot {
        public final String name;
        public final EntityPlayerMP player;
        public final NetworkManager net;
        public final NetHandlerPlayServer handler;
        public final EmbeddedChannel channel;

        Bot(String name, EntityPlayerMP player, NetworkManager net,
            NetHandlerPlayServer handler, EmbeddedChannel channel) {
            this.name = name;
            this.player = player;
            this.net = net;
            this.handler = handler;
            this.channel = channel;
        }

        /** 丢弃积压的出站包（假玩家不会真正发送，留着只会占内存） */
        public int drain() {
            int n = 0;
            while (channel.readOutbound() != null) {
                n++;
                if (n > 20000) break;
            }
            return n;
        }
    }

    /**
     * 注入一个假玩家。
     *
     * @param dim 维度 id；{@code null} 表示主世界
     * @param x   X 坐标；三项都为 {@code null} 时使用该维度的出生点
     * @return 成功返回 {@code ok: ...}，失败返回错误描述
     */
    public static synchronized String spawn(MinecraftServer server, String name, Integer dim,
                                            Double x, Double y, Double z) {
        String key = name.toLowerCase(Locale.ROOT);
        if (BOTS.containsKey(key)) return "exists: " + name;

        int dimension = dim == null ? 0 : dim;
        WorldServer world = server.worldServerForDimension(dimension);
        if (world == null) return "bad-dim: " + dimension;

        GameProfile profile = new GameProfile(offlineUuid(name), name);
        EntityPlayerMP player = new EntityPlayerMP(server, world, profile, new ItemInWorldManager(world));
        player.dimension = dimension;

        NetworkManager net = new NetworkManager(false);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelHandlerContext ctx = channel.pipeline().firstContext();
        if (ctx == null) return "no-pipeline-context";
        try {
            net.channelActive(ctx);
        } catch (Throwable t) {
            return "channel-active-failed: " + t;
        }
        net.setConnectionState(EnumConnectionState.PLAY);

        NetHandlerPlayServer handler = new NetHandlerPlayServer(server, net, player);

        try {
            server.getConfigurationManager().initializeConnectionToPlayer(net, player, handler);
        } catch (Throwable t) {
            return "initialize-failed: " + describe(t);
        }

        if (x != null && y != null && z != null) {
            player.setPosition(x, y, z);
            handler.setPlayerLocation(x, y, z, player.rotationYaw, player.rotationPitch);
        }
        if (dev.dsmnh.horizon.debug.HorizonDebug.botInvulnerable) {
            // 只挡伤害，不改游戏模式：测试仍需正常挖掘与交互
            player.capabilities.disableDamage = true;
        }

        Bot bot = new Bot(name, player, net, handler, channel);
        bot.drain();
        BOTS.put(key, bot);
        return "ok: " + name + " dim=" + player.dimension
                + " pos=" + round(player.posX) + "," + round(player.posY) + "," + round(player.posZ);
    }

    /** 登出并释放连接；返回 {@code ok} 或错误描述 */
    public static synchronized String despawn(MinecraftServer server, String name) {
        Bot bot = BOTS.remove(name.toLowerCase(Locale.ROOT));
        if (bot == null) return "not-found: " + name;
        try {
            server.getConfigurationManager().playerLoggedOut(bot.player);
        } catch (Throwable t) {
            return "logged-out-failed: " + describe(t);
        }
        try {
            bot.net.closeChannel(new ChatComponentText("horizondebug: despawn"));
        } catch (Throwable ignored) {
            // 连接本就是虚构的，关闭失败不影响登出结果
        }
        bot.drain();
        bot.channel.close();
        return "ok: " + name;
    }

    /**
     * 以假玩家身份发一条聊天或命令（走原版 C01 处理路径，与真实输入等价）。
     *
     * <p>投递到独立线程执行：Crucible 禁止在主线程触发 Bukkit 异步聊天事件。
     * 因此本方法只保证「已投递」，实际结果进入 {@link #results()}，
     * 由 tick 处理器写进服务端日志。
     */
    public static String say(String name, final String text) {
        final Bot bot = BOTS.get(name.toLowerCase(Locale.ROOT));
        if (bot == null) return "not-found: " + name;
        if (text == null || text.isEmpty()) return "empty-text";
        CHAT_WORKER.submit(new Runnable() {
            @Override
            public void run() {
                String line;
                try {
                    bot.handler.processChatMessage(new C01PacketChatMessage(text));
                    line = "ok " + bot.name + " > " + text;
                } catch (Throwable t) {
                    line = "FAIL " + bot.name + ": " + describe(t);
                }
                CHAT_RESULTS.add(line);
            }
        });
        return "queued: " + bot.name + " > " + text;
    }

    /** 取走（并清空）待记录的聊天结果；由 tick 处理器在服务端主线程调用 */
    static List<String> takeChatResults() {
        List<String> out = new ArrayList<String>();
        String line;
        while ((line = CHAT_RESULTS.poll()) != null) {
            out.add(line);
        }
        return out;
    }

    /** 列举在线假玩家 */
    public static List<String> list() {
        List<String> out = new ArrayList<String>();
        for (Bot bot : BOTS.values()) {
            EntityPlayerMP p = bot.player;
            out.add(bot.name + " dim=" + p.dimension
                    + " pos=" + round(p.posX) + "," + round(p.posY) + "," + round(p.posZ)
                    + " hp=" + round(p.getHealth())
                    + " yaw=" + round(p.rotationYaw));
        }
        return out;
    }

    /** 在线假玩家的名称列表（原始大小写） */
    public static List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Bot bot : BOTS.values()) {
            out.add(bot.name);
        }
        return out;
    }

    /** 当前在线假玩家数量 */
    public static int count() {
        return BOTS.size();
    }

    /** 名称是否已被占用 */
    public static boolean exists(String name) {
        return BOTS.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** 按名称（大小写不敏感）取假玩家；不存在返回 {@code null} */
    static Bot find(String name) {
        return name == null ? null : BOTS.get(name.toLowerCase(Locale.ROOT));
    }

    /** 服务端停止时清空，避免残留句柄 */
    public static synchronized void reset() {
        BOTS.clear();
        CHAT_RESULTS.clear();
        FakePlayerActions.reset();
    }

    /** 每个 tick 排空所有假玩家的出站队列，并推进进行中的动作 */
    static void drainAll() {
        for (Bot bot : BOTS.values()) {
            bot.drain();
        }
        FakePlayerActions.tick();
    }

    // ------------------------------------------------------------ 工具

    /** 离线模式的 UUID 算法（与服务器 online-mode=false 时的取值一致） */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 异常描述：类型 + 消息 + 首个堆栈帧，便于在命令输出里定位 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) sb.append(" @ ").append(st[0]);
        return sb.toString();
    }
}
