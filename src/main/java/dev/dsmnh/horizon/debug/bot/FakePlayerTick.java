package dev.dsmnh.horizon.debug.bot;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.dsmnh.horizon.debug.api.ApiServer;
import cpw.mods.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 假玩家 tick 处理：每 tick 排空出站包队列，并记录聊天投递结果。
 *
 * <p>假玩家没有真实连接，原版发来的区块与实体更新包会一直堆在 {@code EmbeddedChannel} 里；
 * 不排空会持续占用内存。聊天结果在主线程之外产生，集中到这里写日志，避免跨线程刷日志。
 * 仅在 {@code [bot] enabled=true} 时注册。
 */
public final class FakePlayerTick {

    private static final Logger LOG = LogManager.getLogger("horizondebug");

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        FakePlayerFactory.drainAll();
        ApiServer.drain(net.minecraft.server.MinecraftServer.getServer());
        List<String> results = FakePlayerFactory.takeChatResults();
        for (String line : results) {
            LOG.info("[HorizonDebug] bot chat {}", line);
        }
    }
}
