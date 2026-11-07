package dev.dsmnh.horizon.debug;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.*;
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
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
