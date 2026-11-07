package dev.dsmnh.horizon.debug.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.List;
import java.util.Locale;

/**
 * 客户端调试通道（移植自 AEMobile 同名类）：执行 {@code cmd-client.txt} 里的命令，代替玩家操作。
 *
 * <p>执行顺序：先给 {@code ClientCommandHandler}（客户端专属命令只有它认领），返回 0 时当作
 * 聊天发给服务端。子命令清单见仓库 README；结果追加到 {@code cmd-client.out}，多行用
 * {@code | } 连接（chatlog/chatjson 保留换行，供程序读取）。只由 {@code ClientProxy} 引用，
 * 专用服务器上不会被加载。
 */
public final class ClientDebugBridge {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        String line = DebugBridge.pollClient();
        if (line == null) return;

        long t0 = System.currentTimeMillis();
        String result;
        if (mc.thePlayer == null || mc.theWorld == null) {
            // 主菜单时只放行 connect：自动化测试必须先能自己进入服务器
            String head = line.trim().toLowerCase(Locale.ROOT);
            result = head.startsWith("connect")
                ? connect(mc, line.trim().substring("connect".length()).trim())
                : "not-in-game";
        } else {
            try {
                result = exec(mc, line);
            } catch (Throwable t) {
                result = "error=" + t;
            }
        }
        DebugBridge.appendClient(line, result, System.currentTimeMillis() - t0);
    }

    private static String exec(Minecraft mc, String line) {
        String cmd = line;
        String arg = "";
        int sp = line.indexOf(' ');
        if (sp > 0) {
            cmd = line.substring(0, sp);
            arg = line.substring(sp + 1).trim();
        }
        switch (cmd.toLowerCase(Locale.ROOT)) {
            case "chat":
                return chat(mc, arg);
            case "cmd":
                return command(mc, arg);
            case "info":
                return info(mc);
            case "inv":
                return inventory(mc);
            case "hand":
                return hand(mc);
            case "ach":
                return achievement(mc, arg);
            case "chatlog":
                return chatLog(arg, false);
            case "chatjson":
                return chatLog(arg, true);
            case "players":
                return players(mc);
            case "biome":
                return biome(mc);
            case "nei":
                return neiShare(mc);
            default:
                // 不带子命令时保持原行为：整行当命令处理
                return command(mc, line);
        }
    }

    // ------------------------------------------------------------ 动作

    /**
     * 主菜单连服：{@code connect <host> [port]}。
     *
     * <p>经 Forge 的 {@code FMLClientHandler.connectToServer} 进入，等价于在多人游戏列表里
     * 点击服务器条目；这是自动化从主菜单进入服务器的唯一入口。
     */
    private static String connect(Minecraft mc, String arg) {
        if (arg.isEmpty()) return "usage: connect <host> [port]";
        String[] parts = arg.trim().split(" +");
        String host = parts[0];
        int port = 25565;
        if (parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (Throwable t) {
                return "bad-port: " + parts[1];
            }
        }
        try {
            cpw.mods.fml.client.FMLClientHandler.instance().connectToServer(
                mc.currentScreen,
                new net.minecraft.client.multiplayer.ServerData("horizondebug-test", host + ":" + port));
            return "connecting " + host + ":" + port;
        } catch (Throwable t) {
            return "connect-error=" + t;
        }
    }

    /** 以玩家身份发聊天：服务端走 ServerChatEvent，与真人输入完全一致 */
    private static String chat(Minecraft mc, String text) {
        if (text.isEmpty()) return "empty-text";
        mc.thePlayer.sendChatMessage(text);
        return "chat-sent";
    }

    /** 客户端命令优先，无人认领再发给服务端 */
    private static String command(Minecraft mc, String cmd) {
        if (cmd.isEmpty()) return "empty-command";
        int r = ClientCommandHandler.instance.executeCommand(mc.thePlayer, cmd);
        if (r != 0) return "client-result=" + r;
        mc.thePlayer.sendChatMessage(cmd);
        return "sent-to-server";
    }

    /** 触发成就：1.7.10 的成就登记在客户端统计里，addStat 会触发 Forge 的 AchievementEvent */
    private static String achievement(Minecraft mc, String statId) {
        if (statId.isEmpty()) return "usage: ach <achievement.id>";
        for (Object o : AchievementList.achievementList) {
            if (!(o instanceof Achievement)) continue;
            Achievement a = (Achievement) o;
            if (statId.equals(a.statId)) {
                mc.thePlayer.addStat(a, 1);
                return "achievement-triggered: " + statId;
            }
        }
        return "not-found: " + statId;
    }

    // ------------------------------------------------------------ 信息

    private static String info(Minecraft mc) {
        EntityPlayer p = mc.thePlayer;
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(p.getCommandSenderName());
        sb.append(" pos=").append(fmt(p.posX)).append(',').append(fmt(p.posY)).append(',').append(fmt(p.posZ));
        sb.append(" dim=").append(p.dimension);
        sb.append(" world=").append(worldName(mc));
        sb.append(" hp=").append(fmt(p.getHealth()));
        sb.append(" food=").append(p.getFoodStats().getFoodLevel());
        sb.append(" mode=").append(p.capabilities.isCreativeMode ? "creative" : "survival");
        return sb.toString();
    }

    private static String inventory(Minecraft mc) {
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < main.length; i++) {
            ItemStack s = main[i];
            if (s == null) continue;
            if (used++ > 0) sb.append('\n');
            sb.append(i).append(": ").append(describe(s));
        }
        return used == 0 ? "empty" : sb.toString();
    }

    private static String hand(Minecraft mc) {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held == null ? "empty" : describe(held);
    }

    private static String players(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(((EntityPlayer) o).getCommandSenderName());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String biome(Minecraft mc) {
        try {
            int x = (int) Math.floor(mc.thePlayer.posX);
            int z = (int) Math.floor(mc.thePlayer.posZ);
            return String.valueOf(mc.theWorld.getBiomeGenForCoords(x, z).biomeName);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 模拟 NEI 的 Ctrl+L：把手持物品按 NEI 的格式发给服务端。
     *
     * <p>反射调用 NEI 的 {@code NEIClientUtils.sendChatItemLink}，未安装 NEI 时返回错误而不崩溃。
     */
    private static String neiShare(Minecraft mc) {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return "empty-hand";
        try {
            // 必须用 NEI 自己的序列化（StackInfo.itemStackToNBT）：服务端按 StackInfo.loadFromNBT
            // 解析，原版 ItemStack.writeToNBT 的格式会解析成 null 并在 getDisplayName() 处抛 NPE。
            Class<?> stackInfo = Class.forName("codechicken.nei.recipe.StackInfo");
            Object item = stackInfo.getMethod("itemStackToNBT", ItemStack.class).invoke(null, held);
            net.minecraft.nbt.NBTTagCompound payload = new net.minecraft.nbt.NBTTagCompound();
            // NEIServerUtils.sendChatItemLink 首先读取 linkType
            payload.setString("linkType", "item");
            payload.setTag("item", (net.minecraft.nbt.NBTBase) item);
            Class<?> utils = Class.forName("codechicken.nei.NEIClientUtils");
            utils.getMethod("sendChatItemLink", net.minecraft.nbt.NBTTagCompound.class)
                .invoke(null, payload);
            return "nei-link-sent: " + held.getDisplayName();
        } catch (Throwable t) {
            return "nei-error=" + t;
        }
    }

    private static String chatLog(String arg, boolean json) {
        int n = 10;
        try {
            if (!arg.isEmpty()) n = Integer.parseInt(arg.trim());
        } catch (Throwable ignored) {}
        List<ClientChatLog.Entry> list = ClientChatLog.recent(n);
        if (list.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            ClientChatLog.Entry e = list.get(i);
            sb.append(json ? e.json : e.text);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 工具

    /** 物品描述：显示名（客户端已本地化）+ 注册名 + meta + 数量 */
    private static String describe(ItemStack s) {
        String name;
        try {
            name = s.getDisplayName();
        } catch (Throwable t) {
            name = "?";
        }
        String id;
        try {
            id = String.valueOf(Item.itemRegistry.getNameForObject(s.getItem()));
        } catch (Throwable t) {
            id = "?";
        }
        return name + " [" + id + ":" + s.getItemDamage() + "] x" + s.stackSize;
    }

    private static String worldName(Minecraft mc) {
        try {
            String provider = mc.theWorld.provider.getDimensionName();
            String folder = mc.theWorld.provider.getSaveFolder();
            return provider + (folder == null || folder.isEmpty() ? "" : "(" + folder + ")");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
