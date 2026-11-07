package dev.dsmnh.horizon.debug;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.*;
import dev.dsmnh.horizon.debug.command.CommandHorizonDebug;
import dev.dsmnh.horizon.debug.debug.DebugBridge;
import dev.dsmnh.horizon.debug.metrics.TickStats;
import dev.dsmnh.horizon.debug.metrics.WorldNames;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        WorldNames.setOverrides(HorizonDebug.tpsNames);
    }

    public void init(FMLInitializationEvent event) {
    }

    public void postInit(FMLPostInitializationEvent event) {
    }

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
    }

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandHorizonDebug());
    }

    /** 事件处理器只注册一次：单机退出到主菜单再进另一个世界时 serverStarted 会再次执行 */
    private static boolean eventsRegistered = false;

    public void serverStarted(FMLServerStartedEvent event) {
        // 只有专用服务器启动采集与命令桥；单机集成服务器不占目录、不注册 tick 处理。
        // FMLServerStartedEvent 没有 getServer()，需从 MinecraftServer 获取。
        net.minecraft.server.MinecraftServer mcServer = net.minecraft.server.MinecraftServer.getServer();
        if (mcServer == null || !mcServer.isDedicatedServer()) return;
        WorldNames.init(mcServer);
        TickStats.get().reset();
        if (!eventsRegistered) {
            FMLCommonHandler.instance().bus().register(TickStats.get());
            // debug 命令桥（默认关闭）：命令必须回主线程执行，只能在 tick 里排空队列
            FMLCommonHandler.instance().bus().register(new DebugBridge.ServerTickHandler());
            eventsRegistered = true;
        }
        DebugBridge.startServer();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        DebugBridge.stop();
        TickStats.get().reset();
    }

    public void serverStopped(FMLServerStoppedEvent event) {
    }
}
