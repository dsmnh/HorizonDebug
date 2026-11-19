package dev.dsmnh.horizon.debug.control;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.dsmnh.horizon.debug.HorizonDebug;
import net.minecraft.client.Minecraft;

/**
 * 客户端控制层的每帧驱动：推进动作脚本、记录事件、执行 JSONL 请求。
 *
 * <p>统一在 {@code ClientTickEvent.END} 执行，理由是原版按键状态在 tick 开始时已被
 * {@code MovementInputFromOptions.updatePlayerMoveState} 读取，END 阶段写入的值会在下一个
 * tick 生效，与真人按键的时序一致。只在客户端注册（{@code ClientProxy}）。
 */
public final class ClientControlTick {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        if (!HorizonDebug.controlEnabled) return;
        try {
            ClientApi.drain(mc);
        } catch (Throwable t) {
            HorizonDebug.warn("客户端 API 执行异常：" + t);
        }
        try {
            ClientEvents.tick(mc);
        } catch (Throwable t) {
            HorizonDebug.debug("客户端事件记录异常：" + t);
        }
        try {
            if (ClientScript.isRunning()) ClientScript.tick(mc);
        } catch (Throwable t) {
            HorizonDebug.warn("动作脚本执行异常：" + t);
            ClientScript.stop(mc);
        }
    }
}
