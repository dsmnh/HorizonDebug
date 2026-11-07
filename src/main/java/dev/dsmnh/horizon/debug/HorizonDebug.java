package dev.dsmnh.horizon.debug;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.*;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Horizon 调试模组：独立于业务模组，只提供测试与运维入口。
 *
 * <p>包含两条文件命令桥（服务端 {@code dumps/horizondebug/cmd.txt}、客户端
 * {@code cmd-client.txt}）与 {@code /horizondebug} 诊断命令（tps 对照、维度清单、广播测试）。
 * 生产服保持 {@code [debug] enabled=false}（默认值），监听线程完全不会创建。
 */
@Mod(modid = Tags.MODID, version = Tags.VERSION, name = Tags.MODNAME, acceptedMinecraftVersions = "[1.7.10]")
public class HorizonDebug {

    private static final Logger LOG = LogManager.getLogger(Tags.MODID);

    /** 文件命令桥开关（config 的 {@code [debug] enabled}）。开启后监听 dumps/horizondebug 下的命令文件，线上保持 false。 */
    public static boolean debugEnabled;

    /**
     * 世界显示名覆盖表（config 的 {@code [tps] names}），格式 {@code DIM70=暮色森林} 或
     * {@code 70=暮色森林}。优先级仅次于 Multiverse 的 alias，用于补内置表没有的维度。
     */
    public static String[] tpsNames = new String[0];

    @SidedProxy(clientSide = Tags.GROUPNAME + ".ClientProxy", serverSide = Tags.GROUPNAME + ".CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        debugEnabled = config.getBoolean("enabled", "debug", false,
                "Enable the file-driven command bridges (dumps/horizondebug/cmd.txt and cmd-client.txt)."
                        + " Keep it false on public servers.");
        tpsNames = config.getStringList("names", "tps", new String[0],
                "Override the display name of a world, one per line: DIM70=暮色森林 (or 70=暮色森林).\n"
                        + "Priority: Multiverse alias > this list > built-in table > provider name > world folder name.");
        config.save();
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        proxy.serverAboutToStart(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }

    public static void debug(String message) {
        LOG.debug(message);
    }

    public static void info(String message) {
        LOG.info(message);
    }

    public static void warn(String message) {
        LOG.warn(message);
    }

    public static void error(String message) {
        LOG.error(message);
    }
}
