package dev.dsmnh.horizon.debug.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 客户端事件记录：受伤、死亡、换维度、开/关界面、掉线进服各记一行，供脚本判断时机。
 *
 * <p>用客户端 tick 的前后状态对比实现，不挂 Forge 事件：这样不依赖具体事件类是否
 * 存在，兼容性最好。只保留最近 {@value #MAX} 条，不落盘。
 */
public final class ClientEvents {

    private static final int MAX = 200;
    private static final ArrayDeque<String> RING = new ArrayDeque<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private static float lastHealth = -1.0F;
    private static boolean lastDead;
    private static int lastDim = Integer.MIN_VALUE;
    private static String lastScreen = "";
    private static boolean lastInGame;

    private ClientEvents() {
    }

    /** 客户端 tick 调用，比较前后状态 */
    public static void tick(Minecraft mc) {
        if (mc == null) return;
        boolean inGame = mc.thePlayer != null && mc.theWorld != null;
        if (inGame != lastInGame) {
            add(inGame ? "join-world" : "leave-world");
            lastInGame = inGame;
        }
        if (!inGame) {
            lastHealth = -1.0F;
            lastDead = false;
            return;
        }
        float hp = mc.thePlayer.getHealth();
        if (lastHealth >= 0.0F && hp < lastHealth - 0.05F) {
            add("damage " + fmt(lastHealth) + " -> " + fmt(hp));
        }
        lastHealth = hp;
        boolean dead = mc.thePlayer.isDead || hp <= 0.0F;
        if (dead && !lastDead) add("death");
        lastDead = dead;
        int dim = mc.theWorld.provider.dimensionId;
        if (lastDim != Integer.MIN_VALUE && dim != lastDim) add("dimension " + lastDim + " -> " + dim);
        lastDim = dim;
        GuiScreen screen = mc.currentScreen;
        String name = screen == null ? "" : screen.getClass().getSimpleName();
        if (!name.equals(lastScreen)) {
            add(name.isEmpty() ? "screen-close " + lastScreen : "screen-open " + name);
            lastScreen = name;
        }
    }

    public static List<String> recent(int n) {
        List<String> out = new ArrayList<String>();
        synchronized (RING) {
            int size = RING.size();
            int from = n <= 0 || n >= size ? 0 : size - n;
            int i = 0;
            for (String s : RING) {
                if (i++ >= from) out.add(s);
            }
        }
        return out;
    }

    public static void clear() {
        synchronized (RING) {
            RING.clear();
        }
    }

    private static void add(String what) {
        String line = TIME.format(new Date()) + " " + what;
        synchronized (RING) {
            RING.addLast(line);
            while (RING.size() > MAX) RING.pollFirst();
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", Float.valueOf(v));
    }
}
