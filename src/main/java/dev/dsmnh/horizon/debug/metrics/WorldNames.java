package dev.dsmnh.horizon.debug.metrics;

import dev.dsmnh.horizon.debug.HorizonDebug;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 世界（维度）显示名解析（移植自 AEMobile 同名类）。
 *
 * <p>显示名按顺序取第一个命中的：Multiverse 的 alias（{@code plugins/Multiverse-Core/worlds.yml}，
 * alias 与世界名相同视为未设置）→ config 的 {@code [tps] names} 覆盖 → 内置按 dimid 的中文表
 * → 内置按 provider 英文名的中文表 → 家园世界缺省名 → provider 英文名 → 世界文件夹名。
 * 世界清单来自运行时 {@code DimensionManager.getIDs()}（星系生成的世界常缺录于 worlds.yml），
 * Multiverse 只用于补名字。{@link #reloadIfChanged()} 只在主线程调用，每 5 秒 stat 一次 mtime。
 */
public final class WorldNames {

    private static final String MV_RELATIVE = "plugins/Multiverse-Core/worlds.yml";
    /** PERSONAL_DIM_<n>：GTNH personalspace 的个人花园世界 */
    private static final String PERSONAL_PREFIX = "PERSONAL_DIM";

    /** 世界文件夹名 → Multiverse alias */
    private static final Map<String, String> ALIAS = new HashMap<String, String>();
    /** 世界文件夹名 / dimid 字符串 → config 里配置的名字 */
    private static volatile Map<String, String> OVERRIDES = Collections.emptyMap();

    private static File aliasFile;
    private static long aliasMtime = -1L;

    /** 按 dimid 的中文名 */
    private static final Map<Integer, String> BY_DIM = new HashMap<Integer, String>();
    /** 按 WorldProvider 英文名的中文名（小写） */
    private static final Map<String, String> BY_EN = new HashMap<String, String>();

    static {
        BY_DIM.put(-1, "下界");
        BY_DIM.put(0, "主世界");
        BY_DIM.put(1, "末地");
        BY_DIM.put(7, "暮色森林");

        BY_EN.put("overworld", "主世界");
        BY_EN.put("nether", "下界");
        BY_EN.put("the end", "末地");
        BY_EN.put("twilight forest", "暮色森林");
        BY_EN.put("deep dark", "深渊");
        BY_EN.put("underdark", "幽暗地域");
        BY_EN.put("the outer lands", "外域");
        BY_EN.put("outer lands", "外域");
        BY_EN.put("spirit world", "灵魂世界");
        BY_EN.put("spectreworld", "幽灵世界");
        BY_EN.put("torment", "折磨之地");
        BY_EN.put("the last millenium", "终末千年");
        BY_EN.put("dimensiondarkworld", "暗黑世界");
        BY_EN.put("pocket plane", "口袋位面");
        BY_EN.put("mirror", "镜像世界");
        BY_EN.put("bedrock", "基岩世界");
        BY_EN.put("prosperity ruins", "繁荣遗迹");
        BY_EN.put("shattered lands", "破碎之地");
        BY_EN.put("storage cell", "存储空间");
        BY_EN.put("asteroids", "小行星带");
        BY_EN.put("moon", "月球");
        BY_EN.put("mars", "火星");
        BY_EN.put("venus", "金星");
        BY_EN.put("mercury", "水星");
        BY_EN.put("jupiter", "木星");
        BY_EN.put("saturn", "土星");
        BY_EN.put("uranus", "天王星");
        BY_EN.put("neptune", "海王星");
        BY_EN.put("pluto", "冥王星");
        BY_EN.put("ceres", "谷神星");
        BY_EN.put("haumea", "妊神星");
        BY_EN.put("makemake", "鸟神星");
        BY_EN.put("eris", "阋神星");
        BY_EN.put("kuiper belt", "柯伊伯带");
        BY_EN.put("asteroids belt", "小行星带");
        BY_EN.put("mehen belt", "迈亨小行星带");
        BY_EN.put("proxima b", "比邻星B");
        BY_EN.put("α centauri bb", "半人马Bb");
        BY_EN.put("barnarda c", "巴纳德C");
        BY_EN.put("barnarda e", "巴纳德E");
        BY_EN.put("barnarda f", "巴纳德F");
        BY_EN.put("vega b", "织女一B");
        BY_EN.put("t ceti e", "鲸鱼座T星E");
        BY_EN.put("ross128b", "罗斯128b");
        BY_EN.put("ross128ba", "罗斯128ba");
        // GalaxySpace 的埃及神系行星
        BY_EN.put("neper", "涅佩尔");
        BY_EN.put("maahes", "玛赫斯");
        BY_EN.put("anubis", "阿努比斯");
        BY_EN.put("horus", "荷鲁斯");
        BY_EN.put("seth", "赛特");
        BY_EN.put("titan", "土卫六");
        BY_EN.put("enceladus", "土卫二");
        BY_EN.put("io", "木卫一");
        BY_EN.put("europa", "木卫二");
        BY_EN.put("ganymede", "木卫三");
        BY_EN.put("callisto", "木卫四");
        BY_EN.put("phobos", "火卫一");
        BY_EN.put("deimos", "火卫二");
        BY_EN.put("triton", "海卫一");
        BY_EN.put("proteus", "海卫八");
        BY_EN.put("charon", "冥卫一");
        BY_EN.put("miranda", "天卫五");
        BY_EN.put("ariel", "天卫一");
        BY_EN.put("umbriel", "天卫二");
        BY_EN.put("titania", "天卫三");
        BY_EN.put("oberon", "天卫四");
        BY_EN.put("mimas", "土卫一");
        BY_EN.put("tethys", "土卫三");
        BY_EN.put("dione", "土卫四");
        BY_EN.put("rhea", "土卫五");
        BY_EN.put("iapetus", "土卫八");
    }

    private WorldNames() {
    }

    /** 由 CommonProxy 在服务端启动时调用，定位 Multiverse 配置文件 */
    public static void init(MinecraftServer server) {
        try {
            if (server != null) aliasFile = server.getFile(MV_RELATIVE);
        } catch (Throwable t) {
            aliasFile = null;
        }
        aliasMtime = -1L;
    }

    /** config 里的 {@code DIM70=暮色森林} 列表 */
    public static void setOverrides(String[] lines) {
        Map<String, String> map = new HashMap<String, String>();
        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) continue;
                int eq = raw.indexOf('=');
                if (eq <= 0) continue;
                String k = raw.substring(0, eq).trim();
                String v = raw.substring(eq + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) map.put(k, v);
            }
        }
        OVERRIDES = map.isEmpty() ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(map);
    }

    public static boolean isPersonal(String worldName) {
        return worldName != null && worldName.startsWith(PERSONAL_PREFIX);
    }

    /** 解析显示名；任何一层命中即返回，不会返回 null */
    public static String display(String worldName, int dim, String provider) {
        String raw = worldName == null ? "" : worldName;

        String alias = ALIAS.get(raw);
        if (alias != null && !alias.isEmpty() && !alias.equals(raw)) return alias;

        Map<String, String> ov = OVERRIDES;
        if (!ov.isEmpty()) {
            String v = ov.get(raw);
            if (v == null) v = ov.get(String.valueOf(dim));
            if (v != null && !v.isEmpty()) return v;
        }

        String byDim = BY_DIM.get(dim);
        if (byDim != null) return byDim;

        if (provider != null && !provider.isEmpty()) {
            String en = provider.toLowerCase(Locale.ROOT);
            String byEn = BY_EN.get(en);
            if (byEn != null) return byEn;
            String byPrefix = byPrefix(en);
            if (byPrefix != null) return byPrefix;
        }

        if (isPersonal(raw)) {
            int cut = raw.lastIndexOf('_');
            String num = cut >= 0 && cut + 1 < raw.length() ? raw.substring(cut + 1) : "";
            return num.isEmpty() ? "家园世界" : "家园 #" + num;
        }

        if (provider != null && !provider.isEmpty() && !provider.equals(raw)) return provider;
        return raw.isEmpty() ? ("DIM" + dim) : raw;
    }

    /** 带编号的系列名（Multiverse 多世界、空间站等），前缀命中即翻译，编号原样保留 */
    private static String byPrefix(String en) {
        if (en.startsWith("space station")) {
            String num = en.substring("space station".length()).trim();
            return num.isEmpty() ? "空间站" : "空间站 " + num;
        }
        return null;
    }

    /** 文件变化后才重新解析（每 5 秒 stat 一次 mtime，成本可忽略） */
    public static void reloadIfChanged() {
        File f = aliasFile;
        if (f == null || !f.isFile()) return;
        long mtime = f.lastModified();
        if (mtime == aliasMtime) return;
        aliasMtime = mtime;
        try {
            Map<String, String> parsed = parse(f);
            synchronized (ALIAS) {
                ALIAS.clear();
                ALIAS.putAll(parsed);
            }
        } catch (Throwable t) {
            HorizonDebug.warn("[HorizonDebug] 读取 Multiverse 世界别名失败: " + t);
        }
    }

    /**
     * 极简 YAML 解析：只认 {@code worlds:} 下 2 空格缩进的 {@code <世界名>:} 与
     * 4 空格缩进的 {@code alias:}。Multiverse 写出的格式固定，不引入通用 YAML 解析器。
     */
    private static Map<String, String> parse(File file) throws Exception {
        Map<String, String> out = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            boolean inWorlds = false;
            String current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (!inWorlds) {
                    if (line.trim().equals("worlds:")) inWorlds = true;
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                String body = line.trim();
                if (indent == 0) {
                    inWorlds = false;
                    current = null;
                    continue;
                }
                if (indent == 2 && body.endsWith(":")) {
                    current = body.substring(0, body.length() - 1).trim();
                    continue;
                }
                if (current != null && indent == 4 && body.startsWith("alias:")) {
                    String value = unquote(body.substring(6).trim());
                    if (!value.isEmpty()) out.put(current, value);
                }
            }
        } finally {
            reader.close();
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) s = s.substring(1, s.length() - 1);
        }
        return unescape(s);
    }

    /**
     * 还原 YAML 双引号内的转义。
     *
     * <p>Multiverse（SnakeYAML）把中文默认写成 {@code "\u534E\u6EAA\u5C9B"} 的转义形式，
     * 只去引号会得到字面量 {@code \u534E}，别名因此永远匹配不上真实世界名。
     */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(i + 1);
            if (n == 'u' && i + 5 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 非合法转义，原样保留
                }
            } else if (n == '\\' || n == '"' || n == '\'') {
                sb.append(n);
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 供 {@code /horizondebug dims} 使用：当前解析到的 alias 条数 */
    public static int aliasCount() {
        return ALIAS.size();
    }
}
