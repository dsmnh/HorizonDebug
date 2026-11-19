package dev.dsmnh.horizon.debug.bot;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 假玩家的动作实现：移动、视角、交互、状态查询与命令投递。
 *
 * <p>移动不走输入层：1.7.10 的 {@code EntityLivingBase.onLivingUpdate} 会用自身输入字段
 * （{@code moveForward} / {@code moveStrafing}）覆盖外部写入的值，服务端假玩家没有客户端输入，
 * 每 tick 被写回 0。因此这里直接按 tick 调用 {@link Entity#moveEntity} 推进位移，
 * 由 {@link #tick()} 在服务端 tick 末尾驱动，行为等价且可控。
 *
 * <p>所有方法都必须在服务端主线程调用。
 */
public final class FakePlayerActions {

    /** 每个 tick 的水平位移量（格），接近原版步行速度 */
    private static final double WALK_PER_TICK = 0.21D;

    /** bot cmd 带回的最大回显行数，避免一条命令刷爆响应 */
    private static final int MAX_CMD_LINES = 40;

    /** 进行中的行走任务：名称（小写）到任务 */
    private static final Map<String, Walk> WALKS = new LinkedHashMap<String, Walk>();

    private FakePlayerActions() {
    }

    /** 一个进行中的行走任务 */
    private static final class Walk {
        final String name;
        double dx;
        double dz;
        int ticks;

        Walk(String name, double dx, double dz, int ticks) {
            this.name = name;
            this.dx = dx;
            this.dz = dz;
            this.ticks = ticks;
        }
    }

    /**
     * 分派一个动作。
     *
     * @param action 动作名（小写）
     * @param args   args[0] 为假玩家名（含前缀），其后为参数
     * @return {@code ok: ...} 或 {@code <err>: ...}
     */
    public static String handle(String action, String[] args) {
        if (args.length < 1) return "usage: 缺少假玩家名";
        String name = args[0];
        FakePlayerFactory.Bot bot = FakePlayerFactory.find(name);
        if (bot == null) return "not-found: " + name;
        EntityPlayerMP p = bot.player;
        try {
            if ("state".equals(action)) return state(bot);
            if ("inv".equals(action)) return inventory(bot);
            if ("hand".equals(action)) return hand(bot);
            if ("move".equals(action)) return move(bot, args);
            if ("stop".equals(action)) return stop(bot);
            if ("tp".equals(action)) return teleport(bot, args);
            if ("jump".equals(action)) return jump(bot);
            if ("sneak".equals(action)) return toggle(bot, args, true);
            if ("sprint".equals(action)) return toggle(bot, args, false);
            if ("look".equals(action)) return look(bot, args);
            if ("dig".equals(action)) return dig(bot, args);
            if ("place".equals(action)) return place(bot, args);
            if ("use".equals(action)) return use(bot, args);
            if ("attack".equals(action)) return attack(bot, args);
            if ("give".equals(action)) return give(bot, args);
            if ("cmd".equals(action)) return command(bot, args);
        } catch (Throwable t) {
            return "internal: " + describe(t);
        }
        return "unknown-action: " + action;
    }

    /** 每个服务端 tick 末尾推进所有行走任务 */
    static void tick() {
        if (WALKS.isEmpty()) return;
        Iterator<Map.Entry<String, Walk>> it = WALKS.entrySet().iterator();
        while (it.hasNext()) {
            Walk walk = it.next().getValue();
            FakePlayerFactory.Bot bot = FakePlayerFactory.find(walk.name);
            if (bot == null) {
                it.remove();
                continue;
            }
            try {
                bot.player.moveEntity(walk.dx * WALK_PER_TICK, 0.0D, walk.dz * WALK_PER_TICK);
            } catch (Throwable ignored) {
                it.remove();
                continue;
            }
            walk.ticks--;
            if (walk.ticks <= 0) it.remove();
        }
    }

    /** 服务端停止时清空 */
    static void reset() {
        WALKS.clear();
    }

    // ------------------------------------------------------------ 状态

    private static String state(FakePlayerFactory.Bot bot) {
        EntityPlayerMP p = bot.player;
        StringBuilder sb = new StringBuilder();
        sb.append("ok: name=").append(bot.name)
                .append(" dim=").append(p.dimension)
                .append(" pos=").append(round(p.posX)).append(',').append(round(p.posY)).append(',').append(round(p.posZ))
                .append(" yaw=").append(round(p.rotationYaw)).append(" pitch=").append(round(p.rotationPitch));
        sb.append("\n  hp=").append(round(p.getHealth())).append('/').append(round(p.getMaxHealth()))
                .append(" food=").append(p.getFoodStats().getFoodLevel())
                .append(" exp=").append(p.experienceLevel).append('(').append(p.experienceTotal).append(')');
        GameType type = p.theItemInWorldManager.getGameType();
        sb.append("\n  gameType=").append(type == null ? "?" : type.getName())
                .append(" onGround=").append(p.onGround)
                .append(" sneaking=").append(p.isSneaking())
                .append(" sprinting=").append(p.isSprinting())
                .append(" dead=").append(p.isDead || p.getHealth() <= 0.0F);
        sb.append("\n  hand=").append(itemText(p.getHeldItem()));
        Walk walk = WALKS.get(bot.name.toLowerCase(Locale.ROOT));
        sb.append("\n  walking=").append(walk == null ? "no" : (walk.ticks + " ticks"));
        return sb.toString();
    }

    private static String inventory(FakePlayerFactory.Bot bot) {
        EntityPlayerMP p = bot.player;
        ItemStack[] main = p.inventory.mainInventory;
        StringBuilder sb = new StringBuilder();
        sb.append("ok: name=").append(bot.name)
                .append(" hotbar=").append(p.inventory.currentItem)
                .append(" size=").append(main == null ? 0 : main.length);
        int used = 0;
        if (main != null) {
            for (int i = 0; i < main.length; i++) {
                if (main[i] != null) {
                    used++;
                    sb.append("\n  [").append(i).append("] ").append(itemText(main[i]));
                }
            }
        }
        ItemStack[] armor = p.inventory.armorInventory;
        if (armor != null) {
            for (int i = 0; i < armor.length; i++) {
                if (armor[i] != null) {
                    sb.append("\n  armor[").append(i).append("] ").append(itemText(armor[i]));
                }
            }
        }
        if (used == 0) sb.append("\n  （背包为空）");
        return sb.toString();
    }

    private static String hand(FakePlayerFactory.Bot bot) {
        ItemStack held = bot.player.getHeldItem();
        return "ok: name=" + bot.name + " slot=" + bot.player.inventory.currentItem
                + " item=" + itemText(held);
    }

    // ------------------------------------------------------------ 移动

    private static String move(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 2) return "usage: bot move <name> <forward> [strafe] [ticks]";
        double forward = parseDouble(args[1]);
        double strafe = args.length >= 3 ? parseDouble(args[2]) : 0.0D;
        int ticks = args.length >= 4 ? (int) parseDouble(args[3]) : (int) Math.max(20.0D, Math.abs(forward) * 20.0D);
        if (ticks <= 0) ticks = 1;
        if (ticks > 1200) ticks = 1200;

        double yaw = Math.toRadians(bot.player.rotationYaw);
        // 前向：yaw=0 朝 +Z（南）；strafe 为正朝左
        double dx = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double dz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) return "bad-args: 方向为零";
        dx /= len;
        dz /= len;

        String key = bot.name.toLowerCase(Locale.ROOT);
        WALKS.put(key, new Walk(key, dx, dz, ticks));
        return "ok: " + bot.name + " 开始移动 " + ticks + " ticks";
    }

    private static String stop(FakePlayerFactory.Bot bot) {
        Walk removed = WALKS.remove(bot.name.toLowerCase(Locale.ROOT));
        return "ok: " + bot.name + (removed == null ? " 无移动任务" : " 已停止移动");
    }

    private static String teleport(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 4) return "usage: bot tp <name> <x> <y> <z> [dim]";
        double x = parseDouble(args[1]);
        double y = parseDouble(args[2]);
        double z = parseDouble(args[3]);
        EntityPlayerMP p = bot.player;
        if (args.length >= 5) {
            int dim = (int) parseDouble(args[4]);
            if (dim != p.dimension) {
                MinecraftServer server = MinecraftServer.getServer();
                if (server.worldServerForDimension(dim) == null) return "bad-dim: " + dim;
                server.getConfigurationManager().transferPlayerToDimension(p, dim);
            }
        }
        p.setPositionAndUpdate(x, y, z);
        return "ok: " + bot.name + " dim=" + p.dimension
                + " pos=" + round(p.posX) + "," + round(p.posY) + "," + round(p.posZ);
    }

    private static String jump(FakePlayerFactory.Bot bot) {
        EntityPlayerMP p = bot.player;
        if (!p.onGround) return "not-on-ground: " + bot.name;
        p.motionY = 0.42D;
        p.isAirBorne = true;
        return "ok: " + bot.name + " 起跳";
    }

    private static String toggle(FakePlayerFactory.Bot bot, String[] args, boolean sneak) {
        boolean on = args.length < 2 || isOn(args[1]);
        if (sneak) {
            bot.player.setSneaking(on);
        } else {
            bot.player.setSprinting(on);
        }
        return "ok: " + bot.name + " " + (sneak ? "sneak" : "sprint") + "=" + on;
    }

    private static String look(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 3) return "usage: bot look <name> <yaw> <pitch>";
        float yaw = (float) parseDouble(args[1]);
        float pitch = (float) parseDouble(args[2]);
        EntityPlayerMP p = bot.player;
        p.rotationYaw = yaw;
        p.rotationPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        // 直接写字段：reobf 后 setRotationYawHead 的 SRG 名在运行时缺失（NoSuchMethodError）
        p.rotationYawHead = yaw;
        p.renderYawOffset = yaw;
        return "ok: " + bot.name + " yaw=" + round(p.rotationYaw) + " pitch=" + round(p.rotationPitch);
    }

    // ------------------------------------------------------------ 交互

    private static String dig(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 4) return "usage: bot dig <name> <x> <y> <z> [force]";
        int x = (int) parseDouble(args[1]);
        int y = (int) parseDouble(args[2]);
        int z = (int) parseDouble(args[3]);
        boolean force = args.length >= 5 && isOn(args[4]);
        EntityPlayerMP p = bot.player;
        World world = p.worldObj;
        String block = blockText(world, x, y, z);
        if (force) {
            boolean ok = world.func_147480_a(x, y, z, true);
            return "ok: " + bot.name + " 强制破坏 " + x + "," + y + "," + z + " (" + block + ") -> " + (ok ? "已破坏" : "无变化");
        }
        boolean ok = p.theItemInWorldManager.tryHarvestBlock(x, y, z);
        return "ok: " + bot.name + " 挖掘 " + x + "," + y + "," + z + " (" + block + ") -> " + (ok ? "已破坏" : "未破坏（距离/工具/硬度不满足）");
    }

    private static String place(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 4) return "usage: bot place <name> <x> <y> <z> [face]";
        return useBlock(bot, args, "放置");
    }

    private static String use(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 4) return "usage: bot use <name> <x> <y> <z> [face]";
        return useBlock(bot, args, "使用");
    }

    private static String useBlock(FakePlayerFactory.Bot bot, String[] args, String verb) {
        int x = (int) parseDouble(args[1]);
        int y = (int) parseDouble(args[2]);
        int z = (int) parseDouble(args[3]);
        int face = args.length >= 5 ? (int) parseDouble(args[4]) : 1;
        EntityPlayerMP p = bot.player;
        ItemStack held = p.getHeldItem();
        boolean ok = p.theItemInWorldManager.activateBlockOrUseItem(
                p, p.worldObj, held, x, y, z, face, 0.5F, 0.5F, 0.5F);
        return "ok: " + bot.name + " " + verb + " " + x + "," + y + "," + z + " face=" + face
                + " hand=" + itemText(held) + " -> " + (ok ? "成功" : "无效果");
    }

    private static String attack(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 2) return "usage: bot attack <name> <目标名|实体ID|nearest>";
        EntityPlayerMP p = bot.player;
        Entity target = resolveTarget(p, args[1]);
        if (target == null) {
            return "not-found: " + ("nearest".equalsIgnoreCase(args[1])
                    ? "周围 6 格内没有可攻击实体"
                    : "目标 " + args[1]);
        }
        p.attackTargetEntityWithCurrentItem(target);
        return "ok: " + bot.name + " 攻击 " + target.getCommandSenderName()
                + " hp=" + round(target instanceof EntityPlayer
                ? ((EntityPlayer) target).getHealth() : 0.0D);
    }

    private static String give(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 2) return "usage: bot give <name> <物品id或名称> [数量] [meta]";
        Object obj = Item.itemRegistry.getObject(args[1]);
        if (!(obj instanceof Item)) return "not-found: 物品 " + args[1];
        int count = args.length >= 3 ? (int) parseDouble(args[2]) : 1;
        int meta = args.length >= 4 ? (int) parseDouble(args[3]) : 0;
        ItemStack stack = new ItemStack((Item) obj, Math.max(1, count), meta);
        // 先取描述：addItemStackToInventory 会把 stackSize 递减到剩余量
        String label = itemText(stack);
        boolean ok = bot.player.inventory.addItemStackToInventory(stack);
        return "ok: " + bot.name + " give " + label + " -> " + (ok ? "已放入背包" : "背包已满");
    }

    private static String command(FakePlayerFactory.Bot bot, String[] args) {
        if (args.length < 2) return "usage: bot cmd <name> <命令...>";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(' ');
            sb.append(args[i]);
        }
        String line = sb.toString();
        if (line.startsWith("/")) line = line.substring(1);
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return "not-ready: 服务器未就绪";
        // 命令回显只写进发送者的聊天栏，executeCommand 的返回值是命令自身的结果码，
        // 所以要包一层捕获器，把输出行原样带回来
        CaptureSender capture = new CaptureSender(bot.player);
        int r = server.getCommandManager().executeCommand(capture, line);
        StringBuilder out = new StringBuilder();
        out.append("ok: ").append(bot.name).append(" 执行 /").append(line).append(" -> 返回值 ").append(r);
        List<String> lines = capture.lines();
        int shown = Math.min(lines.size(), MAX_CMD_LINES);
        for (int i = 0; i < shown; i++) {
            out.append('\n').append("  ").append(lines.get(i));
        }
        if (lines.size() > shown) {
            out.append('\n').append("  … 其余 ").append(lines.size() - shown).append(" 行省略");
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 工具

    private static Entity resolveTarget(EntityPlayerMP p, String raw) {
        if ("nearest".equalsIgnoreCase(raw)) {
            List<?> list = p.worldObj.getEntitiesWithinAABBExcludingEntity(p, p.boundingBox.expand(6.0D, 3.0D, 6.0D));
            Entity best = null;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                Entity e = (Entity) list.get(i);
                if (!(e instanceof net.minecraft.entity.EntityLivingBase)) continue;
                double d = p.getDistanceSqToEntity(e);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            return best;
        }
        try {
            int id = Integer.parseInt(raw);
            return p.worldObj.getEntityByID(id);
        } catch (NumberFormatException ignored) {
            // 不是数字，按假玩家名匹配
        }
        FakePlayerFactory.Bot target = FakePlayerFactory.find(raw);
        return target == null ? null : target.player;
    }

    private static String blockText(World world, int x, int y, int z) {
        net.minecraft.block.Block block = world.getBlock(x, y, z);
        if (block == null) return "?";
        return block.getUnlocalizedName() + "@" + world.getBlockMetadata(x, y, z);
    }

    private static String itemText(ItemStack stack) {
        if (stack == null) return "空手";
        return stack.getDisplayName() + " x" + stack.stackSize
                + " (" + stack.getItem().getUnlocalizedName() + "@" + stack.getItemDamage() + ")";
    }

    private static boolean isOn(String raw) {
        return "on".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    private static double parseDouble(String raw) {
        return Double.parseDouble(raw.trim());
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) sb.append(" @ ").append(st[0]);
        return sb.toString();
    }
}
