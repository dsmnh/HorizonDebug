package dev.dsmnh.horizon.debug.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端收到的聊天记录（只保留最近 {@value #MAX} 条，不落盘；移植自 AEMobile 同名类）。
 *
 * <p>供调试通道的 {@code chatlog} / {@code chatjson} 使用：渲染后的颜色、悬停、点击信息
 * 在组件 JSON 里，纯文本无法反映，因此每条同时保存文本与 JSON 两个视图。
 * 只在客户端加载（{@code ClientProxy} 引用）。
 */
public final class ClientChatLog {

    private static final int MAX = 120;

    /** 一条聊天记录 */
    public static final class Entry {
        public final String text;
        public final String json;
        public final long at;

        Entry(String text, String json, long at) {
            this.text = text;
            this.json = json;
            this.at = at;
        }
    }

    private static final ArrayDeque<Entry> RING = new ArrayDeque<Entry>();

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        IChatComponent c = event.message;
        if (c == null) return;
        Entry e = new Entry(textOf(c), jsonOf(c), System.currentTimeMillis());
        synchronized (RING) {
            RING.addLast(e);
            while (RING.size() > MAX) RING.pollFirst();
        }
    }

    /** 最近 n 条，时间升序；n <= 0 时返回全部 */
    public static List<Entry> recent(int n) {
        List<Entry> all = new ArrayList<Entry>();
        synchronized (RING) {
            all.addAll(RING);
        }
        if (n <= 0 || n >= all.size()) return all;
        return all.subList(all.size() - n, all.size());
    }

    /**
     * 带 {@code §} 颜色码的文本。
     *
     * <p>不调用 {@code getFormattedText()}：该方法在 reobf 后运行时不存在
     * （{@code NoSuchMethodError: func_150254_d}），调用会中断整条链路。
     * 文本一律取 {@code getUnformattedText()}，组件细节由 JSON 视图呈现。
     */
    private static String textOf(IChatComponent c) {
        try {
            String s = c.getUnformattedText();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 组件 JSON，用于核对 hoverEvent / clickEvent */
    private static String jsonOf(IChatComponent c) {
        try {
            return IChatComponent.Serializer.func_150696_a(c);
        } catch (Throwable t) {
            return "";
        }
    }
}
