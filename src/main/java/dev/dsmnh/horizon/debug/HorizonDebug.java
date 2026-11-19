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

    /** 假玩家开关（config 的 {@code [bot] enabled}）。开启后 {@code /horizondebug bot} 系列子命令可用。 */
    public static boolean botEnabled;

    /** 假玩家同时在线上限（config 的 {@code [bot] max}）。 */
    public static int botMax = 10;

    /** 假玩家名称前缀（config 的 {@code [bot] prefix}），用于识别与批量清理。 */
    public static String botPrefix = "Bot_";

    /** 假玩家是否免伤（config 的 {@code [bot] invulnerable}），避免饿死摔死污染测试数据。 */
    public static boolean botInvulnerable = true;

    /**
     * 假玩家登录时是否自动授予 OP（config 的 {@code [bot] auto-op}，默认开启）。
     *
     * <p>业务命令（{@code /aemobile}、{@code /cofh} 等）要求权限 2，假玩家没有 OP 就什么都验证不了。
     * 授权走 {@code ServerConfigurationManager.addOp}，登出时自动 {@code removeOp}，
     * 因此 {@code ops.json} 不会残留条目——除非进程被强杀。默认开启，公开服请连同
     * {@code [bot] enabled} 一起关闭。
     */
    public static boolean botAutoOp = true;

    /**
     * JSONL 调试 API 开关（config 的 {@code [debug] jsonApi}）。开启后监听
     * {@code dumps/horizondebug/api.jsonl}，把每行请求转成 {@code api-out.jsonl} 的响应；
     * 依赖 {@code [debug] enabled}，两者都开才生效。
     */
    public static boolean jsonApi = true;

    /**
     * 客户端操控总开关（config 的 {@code [control] enabled}）。开启后客户端会响应
     * {@code cmd-client.txt} 的动作命令与 {@code dumps/horizondebug/api.jsonl} 的 {@code client.*} 请求，
     * 等价于替真人按键、转视角、点界面、挖放方块。默认关闭。
     */
    public static boolean controlEnabled;

    /** 是否允许调试通道操作界面（点按钮、按坐标点击）。关掉后 {@code gui} 系列只读不可点。 */
    public static boolean controlAllowGui = true;

    /** 是否允许调试通道挖掘/放置/攻击/使用物品。 */
    public static boolean controlAllowClick = true;

    /** 是否允许调试通道截图（会写文件到 dumps/horizondebug/shots）。 */
    public static boolean controlAllowScreenshot = true;

    /** 单个动作脚本允许的最大步数（config 的 {@code [control] script-max-steps}）。 */
    public static int controlScriptMaxSteps = 200;

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
        botEnabled = config.getBoolean("enabled", "bot", false,
                "Enable in-process fake players (/horizondebug bot spawn). Keep it false on public servers.");
        botMax = config.getInt("max", "bot", 10, 1, 200,
                "Maximum number of fake players online at the same time.");
        botPrefix = config.getString("prefix", "bot", "Bot_",
                "Name prefix for fake players, used to recognise and clean them up.");
        botInvulnerable = config.getBoolean("invulnerable", "bot", true,
                "Keep fake players from starving or dying to fall damage during tests.");
        botAutoOp = config.getBoolean("auto-op", "bot", true,
                "Give fake players OP on login so business commands (/aemobile, /cofh) can be tested."
                        + " OP is removed again on logout, so ops.json stays clean unless the process is killed.");
        jsonApi = config.getBoolean("jsonApi", "debug", true,
                "Enable the JSONL debug API (dumps/horizondebug/api.jsonl -> api-out.jsonl)."
                        + " Requires [debug] enabled=true.");
        controlEnabled = config.getBoolean("enabled", "control", false,
                "Enable client-side control over the file bridge and JSONL API (keys, look, GUI, dig/place)."
                        + " Equivalent to letting a script play the game. Keep it false unless testing.");
        controlAllowGui = config.getBoolean("allow-gui", "control", true,
                "Allow the control channel to click GUI buttons and send synthetic clicks.");
        controlAllowClick = config.getBoolean("allow-click", "control", true,
                "Allow the control channel to dig, place, use items and attack.");
        controlAllowScreenshot = config.getBoolean("allow-screenshot", "control", true,
                "Allow the control channel to write screenshots under dumps/horizondebug/shots.");
        controlScriptMaxSteps = config.getInt("script-max-steps", "control", 200, 1, 5000,
                "Maximum number of steps in one action script.");
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
