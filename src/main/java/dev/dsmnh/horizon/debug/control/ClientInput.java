package dev.dsmnh.horizon.debug.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端输入层：把脚本的按键与视角意图翻译成原版输入状态。
 *
 * <p>按键走 {@link KeyBinding#setKeyBindState(int, boolean)}，即直接置位原版按键状态，
 * 与真人按住键是同一份状态；视角直接写 {@code rotationYaw/Pitch} 并同步首帧字段，
 * 避免渲染端插值抖动。全部调用必须发生在客户端主线程（客户端 tick）。
 */
public final class ClientInput {

    private ClientInput() {
    }

    /** 键名 → 原版按键绑定，顺序与文档一致 */
    public static Map<String, KeyBinding> binds(GameSettings gs) {
        Map<String, KeyBinding> m = new LinkedHashMap<String, KeyBinding>();
        m.put("forward", gs.keyBindForward);
        m.put("back", gs.keyBindBack);
        m.put("left", gs.keyBindLeft);
        m.put("right", gs.keyBindRight);
        m.put("jump", gs.keyBindJump);
        m.put("sneak", gs.keyBindSneak);
        m.put("sprint", gs.keyBindSprint);
        m.put("attack", gs.keyBindAttack);
        m.put("use", gs.keyBindUseItem);
        m.put("drop", gs.keyBindDrop);
        m.put("inventory", gs.keyBindInventory);
        return m;
    }

    /** 按住（{@code down=true}）或松开一个按键 */
    public static String press(Minecraft mc, String name, boolean down) {
        if (mc == null || mc.gameSettings == null) return "not-in-game: 客户端设置尚未加载";
        if (name == null || name.isEmpty()) return "bad-args: 缺少按键名";
        Map<String, KeyBinding> map = binds(mc.gameSettings);
        KeyBinding kb = map.get(name.toLowerCase(Locale.ROOT));
        if (kb == null) {
            return "bad-key: 未知按键 " + name + "（可用：" + join(map.keySet()) + "，或 hotbar <1-9>）";
        }
        if (kb.getKeyCode() == 0) return "bad-key: 按键 " + name + " 未绑定";
        KeyBinding.setKeyBindState(kb.getKeyCode(), down);
        return "ok: " + name + "=" + down;
    }

    /** 切换快捷栏槽位（0–8），等价于按 1–9 */
    public static String hotbar(Minecraft mc, int slot) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        if (slot < 0 || slot > 8) return "bad-args: 快捷栏槽位必须是 0–8";
        mc.thePlayer.inventory.currentItem = slot;
        return "ok: hotbar=" + slot;
    }

    /** 松开所有由本通道按下的键 */
    public static String releaseAll(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) return "not-in-game";
        for (KeyBinding kb : binds(mc.gameSettings).values()) {
            if (kb != null && kb.getKeyCode() != 0) KeyBinding.setKeyBindState(kb.getKeyCode(), false);
        }
        return "ok: 已松开全部按键";
    }

    /** 绝对视角；pitch 夹到 -90..90 与原版一致 */
    public static String look(Minecraft mc, float yaw, float pitch) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        apply(mc, yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F));
        return "ok: yaw=" + fmt(yaw) + " pitch=" + fmt(pitch);
    }

    /** 相对转动，单位度 */
    public static String turn(Minecraft mc, float dyaw, float dpitch) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        float yaw = mc.thePlayer.rotationYaw + dyaw;
        float pitch = MathHelper.clamp_float(mc.thePlayer.rotationPitch + dpitch, -90.0F, 90.0F);
        apply(mc, yaw, pitch);
        return "ok: yaw=" + fmt(yaw) + " pitch=" + fmt(pitch);
    }

    /** 看向一个世界坐标（取目标中心） */
    public static String lookAt(Minecraft mc, double x, double y, double z) {
        if (mc == null || mc.thePlayer == null) return "not-in-game";
        double dx = x - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight());
        double dz = z - mc.thePlayer.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(dy, dist) * 180.0D / Math.PI));
        apply(mc, yaw, pitch);
        return "ok: yaw=" + fmt(yaw) + " pitch=" + fmt(pitch);
    }

    /** 写视角并让 {@code mc.objectMouseOver} 立刻按新视角重算 */
    private static void apply(Minecraft mc, float yaw, float pitch) {
        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;
        mc.thePlayer.prevRotationYaw = yaw;
        mc.thePlayer.prevRotationPitch = pitch;
        mc.thePlayer.rotationYawHead = yaw;
        mc.thePlayer.prevRotationYawHead = yaw;
        mc.thePlayer.renderYawOffset = yaw;
        mc.thePlayer.prevRenderYawOffset = yaw;
        if (mc.entityRenderer != null) {
            mc.entityRenderer.getMouseOver(1.0F);
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", Float.valueOf(v));
    }

    private static String join(Iterable<String> it) {
        StringBuilder sb = new StringBuilder();
        for (String s : it) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
