package dev.dsmnh.horizon.debug.command;

import dev.dsmnh.horizon.debug.debug.DebugBridge;
import dev.dsmnh.horizon.debug.metrics.TickStats;
import dev.dsmnh.horizon.debug.metrics.WorldNames;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 服务端诊断命令 {@code /horizondebug}（移植自 AEMobile 的 CommandAEMobileServer 诊断部分）：
 *
 * <ul>
 *   <li>{@code tps} —— 输出本模组采集的 TPS/MSPT 表，并同时执行一次 {@code /cofh tps}
 *       输出其原文，一条命令完成对照；结果写入 {@code dumps/horizondebug/tps-compare.txt}，
 *       脚本无需解析聊天记录；</li>
 *   <li>{@code dims} —— 导出维度清单（dimid / 世界名 / provider 名 / 显示名），
 *       写入 {@code dumps/horizondebug/dims.txt}，补充世界中文表时使用；</li>
 *   <li>{@code say <text>} —— 从服务端广播一行聊天，验证「服务器聊天到达客户端 / App」。</li>
 * </ul>
 */
public class CommandHorizonDebug extends CommandBase {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    @Override
    public String getCommandName() {
        return "horizondebug";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/horizondebug tps | dims | say <text>";
    }

    /** 对所有人可见；管理子命令在 {@link #processCommand} 里单独检查权限 2 */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "tps" : args[0].toLowerCase(Locale.ROOT);
        if (!sender.canCommandSenderUseCommand(2, "horizondebug")) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[HorizonDebug] 权限不足：该子命令需要管理员"));
            return;
        }
        if ("tps".equals(sub)) {
            reportTps(sender);
            return;
        }
        if ("dims".equals(sub)) {
            reportDims(sender);
            return;
        }
        if ("say".equals(sub)) {
            broadcastTest(sender, args);
            return;
        }
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
    }

    // ------------------------------------------------------------ say

    /**
     * {@code /horizondebug say <text>}：从服务端广播一行聊天。
     *
     * <p>支持 {@code §} 颜色码，可用于验证客户端与手机端对服务器广播的渲染；
     * 广播走 {@code sendChatMsg}，覆盖服务端聊天下发的主路径。
     */
    private void broadcastTest(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[HorizonDebug] 用法：/horizondebug say <text>"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(' ');
            sb.append(args[i]);
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[HorizonDebug] 服务器尚未就绪"));
            return;
        }
        server.getConfigurationManager().sendChatMsg(new ChatComponentText(sb.toString()));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN
                + "[HorizonDebug] 已广播：" + sb));
    }

    // ------------------------------------------------------------ tps

    private void reportTps(ICommandSender sender) {
        TickStats.Snapshot snap = TickStats.get().snapshot();
        List<String> out = new ArrayList<String>();
        out.add("# HorizonDebug TPS 对照  " + TIME.format(new Date()));
        if (snap == null || snap.at == 0) {
            out.add("采集尚未开始（服务端刚启动？等待数秒后重试）");
            emit(sender, out, "tps-compare.txt");
            return;
        }

        out.add("");
        out.add("## 本模组（Forge TickEvent 计时，最近 5 秒窗口）");
        out.add(String.format(Locale.ROOT, "总体      TPS %6.2f  MSPT %7.2f  MAX %7.2f  ticks %d  在线 %d",
                snap.srvTps, snap.srvMspt, snap.srvMax, snap.srvTicks, snap.players));
        out.add(String.format(Locale.ROOT, "%-10s %-22s %7s %7s %7s %5s %5s %6s",
                "dim", "name", "TPS", "MSPT", "MAX", "P", "E", "chunk"));
        for (TickStats.WorldSnap w : snap.worlds) {
            out.add(String.format(Locale.ROOT, "%-10d %-22s %7.2f %7.2f %7.2f %5d %5d %6d  %s",
                    w.dim, w.name, w.tps, w.mspt, w.max, w.players, w.entities, w.chunks,
                    w.personal ? "(家园)" : ""));
        }

        // 对照：执行一次 cofh tps，捕获其聊天输出原文
        out.add("");
        out.add("## /cofh tps 原文");
        long t0 = System.nanoTime();
        List<String> cofh = runCaptured(sender, "cofh tps");
        long ms = (System.nanoTime() - t0) / 1000000L;
        if (cofh.isEmpty()) {
            out.add("（未捕获到输出：可能未安装 CoFHCore，或其输出不经过 addChatMessage）");
        } else {
            out.addAll(cofh);
        }
        out.add(String.format(Locale.ROOT, "（执行耗时 %d ms）", ms));

        emit(sender, out, "tps-compare.txt");
    }

    /** 用一个「只收集消息」的 sender 执行命令，取得原始输出 */
    private List<String> runCaptured(ICommandSender delegate, String command) {
        List<String> lines = new ArrayList<String>();
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return lines;
            server.getCommandManager().executeCommand(new CapturingSender(delegate, lines), command);
        } catch (Throwable t) {
            lines.add("执行失败: " + t);
        }
        return lines;
    }

    // ------------------------------------------------------------ dims

    private void reportDims(ICommandSender sender) {
        TickStats.Snapshot snap = TickStats.get().snapshot();
        List<String> out = new ArrayList<String>();
        out.add("# 维度清单  " + TIME.format(new Date())
                + "  （Multiverse alias 已加载 " + WorldNames.aliasCount() + " 条）");
        out.add(String.format(Locale.ROOT, "%-6s %-24s %-26s %-16s %s", "dim", "world", "provider", "display", "flags"));
        if (snap != null) {
            for (TickStats.WorldSnap w : snap.worlds) {
                out.add(String.format(Locale.ROOT, "%-6d %-24s %-26s %-16s %s",
                        w.dim, w.rawName, w.provider, w.name,
                        (w.personal ? "personal " : "") + (w.loaded ? "" : "unloaded")));
            }
        }
        out.add("");
        out.add("补名字：修改 config/HorizonDebug.cfg 的 [tps] names（DIM70=暮色森林），或修改 Multiverse 的 alias");
        emit(sender, out, "dims.txt");
    }

    // ------------------------------------------------------------ 输出

    /** 输出到聊天栏（最多 30 行，避免刷屏）并全量写文件 */
    private void emit(ICommandSender sender, List<String> lines, String fileName) {
        int shown = Math.min(lines.size(), 30);
        for (int i = 0; i < shown; i++) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + lines.get(i)));
        }
        if (lines.size() > shown) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY
                    + "… 其余 " + (lines.size() - shown) + " 行见 dumps/horizondebug/" + fileName));
        }
        File dir = DebugBridge.serverDir();
        if (dir == null) return;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, fileName)), "UTF-8");
            try {
                for (String line : lines) {
                    w.write(line);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 只做一件事：把 {@code addChatMessage} 的内容收集到 list。
     * 其余方法转发给真正的 sender（权限按最高放行，否则 {@code cofh tps} 会被拒绝）。
     */
    private static final class CapturingSender implements ICommandSender {
        private final ICommandSender delegate;
        private final List<String> lines;

        CapturingSender(ICommandSender delegate, List<String> lines) {
            this.delegate = delegate;
            this.lines = lines;
        }

        @Override
        public String getCommandSenderName() {
            return delegate.getCommandSenderName();
        }

        @Override
        public IChatComponent func_145748_c_() {
            return delegate.func_145748_c_();
        }

        @Override
        public void addChatMessage(IChatComponent component) {
            lines.add(component == null ? "" : component.getUnformattedText());
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return delegate.getPlayerCoordinates();
        }

        @Override
        public World getEntityWorld() {
            return delegate.getEntityWorld();
        }
    }
}
