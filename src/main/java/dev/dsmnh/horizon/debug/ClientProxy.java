package dev.dsmnh.horizon.debug;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.*;
import dev.dsmnh.horizon.debug.control.ClientApi;
import dev.dsmnh.horizon.debug.control.ClientControlTick;
import dev.dsmnh.horizon.debug.debug.ClientChatLog;
import dev.dsmnh.horizon.debug.debug.ClientDebugBridge;
import dev.dsmnh.horizon.debug.debug.DebugBridge;
import net.minecraft.client.Minecraft;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // debug 命令桥的客户端通道：执行 cmd-client.txt 里的客户端命令（默认关闭）
        DebugBridge.startClient(Minecraft.getMinecraft().mcDataDir);
        FMLCommonHandler.instance().bus().register(new ClientDebugBridge());
        // 客户端收到的聊天记录：供调试通道的 chatlog / chatjson 使用（Forge 总线）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ClientChatLog());
        // 客户端操控层：[control] enabled=true 时才注册 tick 驱动与 JSONL 通道
        if (HorizonDebug.controlEnabled) {
            // 自动化时窗口经常不在前台，原版的「失焦自动暂停」会让界面反复弹出、按键失效
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings != null) mc.gameSettings.pauseOnLostFocus = false;
            FMLCommonHandler.instance().bus().register(new ClientControlTick());
            ClientApi.start(mc.mcDataDir);
        }
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
