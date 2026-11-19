package dev.dsmnh.horizon.debug.control;

import dev.dsmnh.horizon.debug.HorizonDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 客户端动作面：把调试脚本要的「挖、放、用、打、开界面、截图、观察」翻译成原版调用。
 *
 * <p>所有动作等价于玩家亲手操作：挖与放走 {@code PlayerControllerMP}，攻击走
 * {@code attackEntity}，界面点击走 {@code GuiScreen.mouseClicked} / {@code actionPerformed}。
 * 界面相关成员在 1.7.10 里是 protected，这里用反射调用（与 AEMobile 一致的做法）。
 * 只在客户端主线程执行。
 */
public final class ClientActions {

    private ClientActions() {
    }

    // ------------------------------------------------------------ 观察

    public static String state(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return "not-in-game";
        EntityPlayer p = mc.thePlayer;
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(p.getCommandSenderName())
          .append(" dim=").append(mc.theWorld.provider.dimensionId)
          .append(" pos=").append(fmt2(p.posX)).append(',').append(fmt2(p.posY)).append(',').append(fmt2(p.posZ))
          .append("\n  yaw=").append(fmt1(p.rotationYaw)).append(" pitch=").append(fmt1(p.rotationPitch))
          .append("\n  hp=").append(fmt1(p.getHealth())).append('/').append(fmt1(p.getMaxHealth()))
          .append(" food=").append(p.getFoodStats().getFoodLevel())
          .append(" exp=").append(p.experienceLevel)
          .append("\n  onGround=").append(p.onGround)
          .append(" sneaking=").append(p.isSneaking())
          .append(" sprinting=").append(p.isSprinting())
          .append(" dead=").append(p.isDead)
          .append("\n  screen=").append(mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName())
          .append("\n  hand=").append(describe(p.getHeldItem()))
          .append("\n  fps=").append(fps());
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null) {
            sb.append("\n  target=none");
        } else if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            sb.append("\n  target=entity ").append(mop.entityHit == null ? "?" : mop.entityHit.getCommandSenderName())
              .append(" id=").append(mop.entityHit == null ? -1 : mop.entityHit.getEntityId());
        } else {
            sb.append("\n  target=block ").append(mop.blockX).append(',').append(mop.blockY).append(',').append(mop.blockZ)
              .append(" side=").append(mop.sideHit);
        }
        return "ok: " + sb;
    }

    /** 扫描周围方块与实体，供脚本判断环境 */
    public static String scan(Minecraft mc, int radius) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return "not-in-game";
        int r = Math.max(1, Math.min(radius, 32));
        EntityPlayer p = mc.thePlayer;
        int bx = (int) Math.floor(p.posX);
        int by = (int) Math.floor(p.posY);
        int bz = (int) Math.floor(p.posZ);
        StringBuilder sb = new StringBuilder();
        sb.append("center=").append(bx).append(',').append(by).append(',').append(bz)
          .append(" radius=").append(r).append("\n  blocks:");
        int shown = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!mc.theWorld.blockExists(x, y, z)) continue;
                    if (mc.theWorld.isAirBlock(x, y, z)) continue;
                    if (shown >= 40) continue;
                    if (shown == 0) sb.append('\n');
                    sb.append("    ").append(x).append(',').append(y).append(',').append(z).append(' ')
                      .append(nameOf(mc, x, y, z)).append('\n');
                    shown++;
                }
            }
        }
        if (shown == 0) sb.append(" 无");
        List<Entity> near = nearby(mc.thePlayer, r);
        sb.append("  entities:").append(near.size() == 0 ? " 无" : "");
        for (Entity e : near) {
            sb.append("\n    ").append(e.getCommandSenderName()).append(" id=").append(e.getEntityId())
              .append(" @").append(fmt1(e.posX)).append(',').append(fmt1(e.posY)).append(',').append(fmt1(e.posZ))
              .append(" dist=").append(fmt1(Math.sqrt(mc.thePlayer.getDistanceSqToEntity(e))));
        }
        return "ok: " + sb;
    }

    /** 截图到 dumps/horizondebug/shots/，返回文件绝对路径 */
    public static String shot(Minecraft mc, String name) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowScreenshot) return "disabled: config [control] allow-screenshot=false";
        try {
            File dir = new File(mc.mcDataDir, "dumps/horizondebug/shots");
            if (!dir.isDirectory() && !dir.mkdirs()) return "io-error: 无法创建 " + dir.getPath();
            String base = (name == null || name.trim().isEmpty()) ? "shot" : sanitize(name.trim());
            // 1.7.10 的 saveScreenshot 总会在传入目录下再建 screenshots 子目录，落盘路径要按此推算
            File shotsDir = new File(dir, "screenshots");
            File target = new File(shotsDir, base + ".png");
            int n = 1;
            while (target.exists()) {
                target = new File(shotsDir, base + "_" + (n++) + ".png");
            }
            // saveScreenshot 不会补扩展名，文件名里必须自带 .png
            ScreenShotHelper.saveScreenshot(dir, base + (n == 1 ? "" : "_" + (n - 1)) + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (!target.isFile()) return "io-error: 截图未生成 " + target.getAbsolutePath();
            return "ok: " + target.getAbsolutePath() + " (" + target.length() + " bytes)";
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    // ------------------------------------------------------------ 交互

    /** 挖掉准星或指定坐标的方块 */
    public static String dig(Minecraft mc, Integer x, Integer y, Integer z) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowClick) return "disabled: config [control] allow-click=false";
        try {
            int bx, by, bz, face;
            if (x != null && y != null && z != null) {
                bx = x.intValue();
                by = y.intValue();
                bz = z.intValue();
                face = 1;
                mc.objectMouseOver = new MovingObjectPosition(bx, by, bz, face,
                        Vec3.createVectorHelper(bx + 0.5D, by + 0.5D, bz + 0.5D));
            } else {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                    return "not-found: 准星没有对准方块";
                }
                bx = mop.blockX;
                by = mop.blockY;
                bz = mop.blockZ;
                face = mop.sideHit;
            }
            mc.playerController.clickBlock(bx, by, bz, face);
            mc.playerController.onPlayerDestroyBlock(bx, by, bz, face);
            return "ok: dig " + bx + "," + by + "," + bz;
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    /** 在目标方块面上放置手中方块 */
    public static String place(Minecraft mc, Integer x, Integer y, Integer z, Integer face) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowClick) return "disabled: config [control] allow-click=false";
        try {
            int bx, by, bz, side;
            if (x != null && y != null && z != null) {
                bx = x.intValue();
                by = y.intValue();
                bz = z.intValue();
                side = face == null ? 1 : face.intValue();
            } else {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                    return "not-found: 准星没有对准方块";
                }
                bx = mop.blockX;
                by = mop.blockY;
                bz = mop.blockZ;
                side = mop.sideHit;
            }
            ItemStack held = mc.thePlayer.getHeldItem();
            boolean ok = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                    bx, by, bz, side, Vec3.createVectorHelper(bx + 0.5D, by + 0.5D, bz + 0.5D));
            if (held != null) mc.thePlayer.swingItem();
            return ok ? "ok: place at " + bx + "," + by + "," + bz + " face=" + side
                    : "failed: 放置被拒绝（位置不可用或手上没有方块）";
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    /** 右键使用手中物品（不指定坐标时对空气使用） */
    public static String use(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowClick) return "disabled: config [control] allow-click=false";
        try {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null) return "not-found: 手上没有物品";
            boolean ok = mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
            return ok ? "ok: use " + describe(held) : "failed: 使用被拒绝";
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    /** 攻击准星目标或指定实体 ID / 最近实体 */
    public static String attack(Minecraft mc, String target) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowClick) return "disabled: config [control] allow-click=false";
        try {
            Entity victim = null;
            String t = target == null ? "crosshair" : target.trim().toLowerCase(Locale.ROOT);
            if ("crosshair".equals(t) || "look".equals(t)) {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                    victim = mop.entityHit;
                }
            } else if ("nearest".equals(t)) {
                List<Entity> near = nearby(mc.thePlayer, 6);
                double best = Double.MAX_VALUE;
                for (Entity e : near) {
                    if (!(e instanceof EntityLivingBase)) continue;
                    double d = mc.thePlayer.getDistanceSqToEntity(e);
                    if (d < best) {
                        best = d;
                        victim = e;
                    }
                }
            } else {
                try {
                    int id = Integer.parseInt(t);
                    victim = mc.theWorld.getEntityByID(id);
                } catch (NumberFormatException e) {
                    victim = null;
                }
            }
            if (victim == null) return "not-found: 没有可攻击目标";
            mc.playerController.attackEntity(mc.thePlayer, victim);
            mc.thePlayer.swingItem();
            return "ok: attack " + victim.getCommandSenderName() + " id=" + victim.getEntityId();
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    // ------------------------------------------------------------ 界面

    /** 当前界面标题与按钮清单 */
    public static String screen(Minecraft mc) {
        if (mc == null) return "not-in-game";
        GuiScreen screen = mc.currentScreen;
        if (screen == null) return "ok: screen=none";
        StringBuilder sb = new StringBuilder();
        sb.append("screen=").append(screen.getClass().getName());
        List<GuiButton> buttons = buttons(screen);
        sb.append(" buttons=").append(buttons.size());
        for (GuiButton b : buttons) {
            sb.append("\n  [").append(b.id).append("] ").append(strip(b.displayString))
              .append(" @").append(b.xPosition).append(',').append(b.yPosition)
              .append(b.enabled ? "" : " (disabled)")
              .append(b.visible ? "" : " (hidden)");
        }
        return "ok: " + sb;
    }

    /** 按按钮 id 点击 */
    public static String clickButton(Minecraft mc, int id) {
        if (mc == null || mc.currentScreen == null) return "not-in-game: 当前没有打开的界面";
        if (!HorizonDebug.controlAllowGui) return "disabled: config [control] allow-gui=false";
        GuiScreen screen = mc.currentScreen;
        for (GuiButton b : buttons(screen)) {
            if (b.id != id) continue;
            if (!b.enabled) return "bad-args: 按钮 " + id + " 处于禁用状态";
            try {
                Method m = findMethod(GuiScreen.class, new Class<?>[]{GuiButton.class},
                        "actionPerformed", "func_146284_a");
                if (m == null) return "error: 找不到 GuiScreen.actionPerformed";
                m.invoke(screen, b);
                return "ok: clicked [" + id + "] " + strip(b.displayString);
            } catch (Throwable t) {
                return "error: " + describe(t);
            }
        }
        return "not-found: 界面里没有按钮 " + id;
    }

    /** 按屏幕坐标点击（等价于鼠标左键在该点按下并抬起） */
    public static String clickAt(Minecraft mc, int x, int y, int button) {
        if (mc == null || mc.currentScreen == null) return "not-in-game: 当前没有打开的界面";
        if (!HorizonDebug.controlAllowGui) return "disabled: config [control] allow-gui=false";
        GuiScreen screen = mc.currentScreen;
        try {
            Method down = findMethod(GuiScreen.class, new Class<?>[]{int.class, int.class, int.class},
                    "mouseClicked", "func_73864_a");
            if (down == null) return "error: 找不到 GuiScreen.mouseClicked";
            down.invoke(screen, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(button));
            Method up = findMethod(GuiScreen.class, new Class<?>[]{int.class, int.class, int.class},
                    "mouseMovedOrUp", "func_146286_b");
            if (up != null) up.invoke(screen, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(button));
            return "ok: click " + x + "," + y + " button=" + button;
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    /** 读取背包：快捷栏、主背包、盔甲三块，空槽跳过 */
    public static String inventory(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        EntityPlayer p = mc.thePlayer;
        StringBuilder sb = new StringBuilder();
        sb.append("hotbar=").append(p.inventory.currentItem).append(" size=").append(p.inventory.getSizeInventory());
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (s == null) continue;
            sb.append("\n  [").append(i).append(i < 9 ? " 快捷栏" : " 背包").append("] ").append(describe(s));
        }
        for (int i = 0; i < p.inventory.armorInventory.length; i++) {
            ItemStack s = p.inventory.armorInventory[i];
            if (s == null) continue;
            sb.append("\n  [armor ").append(i).append("] ").append(describe(s));
        }
        return "ok: " + sb;
    }

    /** 列出当前绑定的按键名与键码，脚本据此挑选可用键 */
    public static String keys(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) return "not-in-game";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, net.minecraft.client.settings.KeyBinding> e
                : ClientInput.binds(mc.gameSettings).entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "?" : e.getValue().getKeyCode());
        }
        return "ok: " + sb;
    }

    /** 主动打开一个原版界面：inventory / chat / pause */
    public static String openScreen(Minecraft mc, String name) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (!HorizonDebug.controlAllowGui) return "disabled: config [control] allow-gui=false";
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        try {
            if ("inventory".equals(n) || "inv".equals(n)) {
                mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.thePlayer));
            } else if ("chat".equals(n)) {
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat());
            } else if ("pause".equals(n) || "menu".equals(n)) {
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
            } else {
                return "bad-args: 未知界面 " + name + "（可用：inventory / chat / pause）";
            }
            String opened = mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName();
            return "ok: screen=" + opened;
        } catch (Throwable t) {
            return "error: " + describe(t);
        }
    }

    /** 关闭当前界面 */
    public static String closeScreen(Minecraft mc) {
        if (mc == null) return "not-in-game";
        if (mc.currentScreen == null) return "ok: 本来就没有界面";
        String name = mc.currentScreen.getClass().getSimpleName();
        mc.displayGuiScreen(null);
        mc.setIngameFocus();
        return "ok: closed " + name;
    }

    // ------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttons(GuiScreen screen) {
        List<GuiButton> out = new ArrayList<GuiButton>();
        try {
            Field f = findField(GuiScreen.class, "buttonList", "field_146292_n");
            if (f == null) return out;
            Object v = f.get(screen);
            if (v instanceof List) {
                for (Object o : (List<Object>) v) {
                    if (o instanceof GuiButton) out.add((GuiButton) o);
                }
            }
        } catch (Throwable ignored) {
            // 反射失败时按空清单处理，调用方会得到 buttons=0
        }
        return out;
    }

    private static List<Entity> nearby(EntityPlayer p, int r) {
        List<Entity> out = new ArrayList<Entity>();
        try {
            List<?> raw = p.worldObj.getEntitiesWithinAABBExcludingEntity(p, p.boundingBox.expand(r, r, r));
            for (Object o : raw) {
                if (o instanceof Entity) out.add((Entity) o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String nameOf(Minecraft mc, int x, int y, int z) {
        try {
            return String.valueOf(mc.theWorld.getBlock(x, y, z).getUnlocalizedName());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describe(ItemStack s) {
        if (s == null) return "empty";
        try {
            return s.getDisplayName() + " x" + s.stackSize;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 去掉原版按钮文字里的颜色/翻译残留 */
    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\u00a7.", "").trim();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", Double.valueOf(v));
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    /** 原版 FPS 字段是 private static，这里反射读取，失败时返回问号 */
    private static String fps() {
        try {
            Field f = findField(Minecraft.class, "debugFPS", "field_71470_ab");
            if (f == null) return "?";
            return String.valueOf(f.getInt(null));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 诊断：打印反射目标的运行时字段/方法名，用于确认 MCP 名在运行期是否可用 */
    public static String dumpNames() {
        StringBuilder sb = new StringBuilder();
        sb.append("GuiScreen fields:");
        for (Field f : GuiScreen.class.getDeclaredFields()) {
            sb.append("\n  ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
        }
        sb.append("\nGuiScreen methods:");
        for (Method m : GuiScreen.class.getDeclaredMethods()) {
            if (m.getParameterTypes().length > 3) continue;
            sb.append("\n  ").append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getSimpleName());
            }
            sb.append(')');
        }
        sb.append("\nMinecraft static int fields:");
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (f.getType() != int.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            sb.append("\n  ").append(f.getName());
        }
        sb.append("\nGuiButton fields:");
        for (Field f : GuiButton.class.getDeclaredFields()) {
            sb.append("\n  ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
        }
        return "ok: " + sb;
    }

    /**
     * 按「MCP 名 + 混淆名」两种候选查找字段。
     *
     * <p>GTNH 生产环境开了 {@code FMLDeobfuscatingRemapper} 的字段重映射但反射仍以运行时名为准，
     * 因此直接按 MCP 名 {@code getDeclaredField} 会失败，必须再试 {@code field_xxxxxx}。
     */
    private static Field findField(Class<?> owner, String... names) {
        for (String n : names) {
            try {
                Field f = owner.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    /** 按「MCP 名 + 混淆名」两种候选查找方法，参数类型需完全匹配 */
    private static Method findMethod(Class<?> owner, Class<?>[] params, String... names) {
        for (String n : names) {
            try {
                Method m = owner.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) sb.append(" @ ").append(st[0]);
        return sb.toString();
    }
}
