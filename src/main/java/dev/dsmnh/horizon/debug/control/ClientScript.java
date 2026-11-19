package dev.dsmnh.horizon.debug.control;

import dev.dsmnh.horizon.debug.HorizonDebug;
import dev.dsmnh.horizon.debug.api.Json;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 时间轴动作脚本：一次下发多步，按 {@code at}（相对毫秒）在客户端 tick 里依次执行。
 *
 * <p>步骤数组必须是 {@code {"at":0,"key":"forward","down":true}} 这种形状：每步只允许
 * 一个动作键（{@code key/press/release/hotbar/look/turn/lookAt/dig/place/use/attack/
 * chat/cmd/shot/click/clickat/close}），{@code at} 可省略表示与上一步同时。执行与真人
 * 操作等价：按键调用 {@link ClientInput}，交互调用 {@link ClientActions}。脚本在客户端
 * 主线程推进，不另起线程。
 */
public final class ClientScript {

    private static final class Step {
        final long at;
        final String op;
        final Object value;

        Step(long at, String op, Object value) {
            this.at = at;
            this.op = op;
            this.value = value;
        }
    }

    private static final List<Step> STEPS = new ArrayList<Step>();
    private static final List<String> LOG = new ArrayList<String>();
    private static final int LOG_MAX = 80;

    private static long startedAt;
    private static int cursor;
    private static boolean running;
    private static String lastResult = "";

    private ClientScript() {
    }

    /** 启动脚本；返回 {@code ok: ...} 或失败原因 */
    public static String start(Minecraft mc, List<Object> raw) {
        if (mc == null) return "not-in-game: 客户端尚未启动";
        if (!HorizonDebug.controlEnabled) return "disabled: config [control] enabled=false";
        if (raw == null || raw.isEmpty()) return "bad-args: steps 不能为空";
        if (raw.size() > HorizonDebug.controlScriptMaxSteps) {
            return "bad-args: 步数 " + raw.size() + " 超过上限 " + HorizonDebug.controlScriptMaxSteps;
        }
        List<Step> steps = new ArrayList<Step>();
        long prev = 0;
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> m = Json.asMap(raw.get(i));
            if (m == null) return "bad-args: 第 " + i + " 步不是对象";
            Double atRaw = Json.num(m, "at");
            long at = atRaw == null ? prev : atRaw.longValue();
            if (at < 0) return "bad-args: 第 " + i + " 步 at 不能为负";
            if (at < prev) return "bad-args: 第 " + i + " 步 at=" + at + " 早于上一步 " + prev;
            String op = null;
            Object value = null;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if ("at".equals(e.getKey())) continue;
                if (op != null) return "bad-args: 第 " + i + " 步只能有一个动作键，发现 " + op + " 与 " + e.getKey();
                op = e.getKey();
                value = e.getValue();
            }
            if (op == null) return "bad-args: 第 " + i + " 步没有动作键";
            if ("stop".equals(op)) return "bad-args: 第 " + i + " 步不能是 stop";
            steps.add(new Step(at, op, value));
            prev = at;
        }
        synchronized (ClientScript.class) {
            STEPS.clear();
            STEPS.addAll(steps);
            LOG.clear();
            startedAt = System.currentTimeMillis();
            cursor = 0;
            running = true;
            lastResult = "";
        }
        return "ok: 脚本已启动，共 " + steps.size() + " 步，时长 " + prev + "ms";
    }

    /** 客户端 tick 调用：推进到期的步骤 */
    public static void tick(Minecraft mc) {
        List<Step> due;
        synchronized (ClientScript.class) {
            if (!running) return;
            long now = System.currentTimeMillis() - startedAt;
            int from = cursor;
            while (cursor < STEPS.size() && STEPS.get(cursor).at <= now) cursor++;
            if (cursor == from) return;
            due = new ArrayList<Step>(STEPS.subList(from, cursor));
            if (cursor >= STEPS.size()) running = false;
        }
        for (Step s : due) {
            String r;
            try {
                r = apply(mc, s);
            } catch (Throwable t) {
                r = "error: " + t;
            }
            lastResult = s.op + " -> " + r;
            record(lastResult);
        }
        if (!isRunning()) ClientInput.releaseAll(mc);
    }

    /** 状态摘要 */
    public static String status() {
        synchronized (ClientScript.class) {
            if (STEPS.isEmpty()) return "ok: 没有脚本";
            long total = STEPS.size();
            long elapsed = running ? System.currentTimeMillis() - startedAt : -1;
            long end = STEPS.get(STEPS.size() - 1).at;
            StringBuilder sb = new StringBuilder();
            sb.append("ok: running=").append(running)
              .append(" cursor=").append(cursor).append('/').append(total)
              .append(" endAt=").append(end).append("ms");
            if (running) sb.append(" elapsed=").append(elapsed).append("ms");
            if (!lastResult.isEmpty()) sb.append(" last=").append(lastResult);
            return sb.toString();
        }
    }

    /** 最近若干条执行记录 */
    public static List<String> recent(int n) {
        List<String> out = new ArrayList<String>();
        synchronized (ClientScript.class) {
            int size = LOG.size();
            int from = n <= 0 || n >= size ? 0 : size - n;
            for (int i = from; i < size; i++) out.add(LOG.get(i));
        }
        return out;
    }

    public static boolean isRunning() {
        synchronized (ClientScript.class) {
            return running;
        }
    }

    /** 中止脚本并松开所有按键 */
    public static String stop(Minecraft mc) {
        boolean was;
        synchronized (ClientScript.class) {
            was = running;
            running = false;
            STEPS.clear();
            cursor = 0;
        }
        if (mc != null) ClientInput.releaseAll(mc);
        return was ? "ok: 脚本已中止" : "ok: 当前没有运行中的脚本";
    }

    public static String clear(Minecraft mc) {
        synchronized (ClientScript.class) {
            running = false;
            STEPS.clear();
            LOG.clear();
            cursor = 0;
            lastResult = "";
        }
        if (mc != null) ClientInput.releaseAll(mc);
        return "ok: 已清空脚本与记录";
    }

    private static void record(String line) {
        synchronized (ClientScript.class) {
            LOG.add(line);
            while (LOG.size() > LOG_MAX) LOG.remove(0);
        }
    }

    // ------------------------------------------------------------ 执行

    private static String apply(Minecraft mc, Step s) {
        String op = s.op.toLowerCase(Locale.ROOT);
        Object v = s.value;
        if ("key".equals(op)) {
            Map<String, Object> m = Json.asMap(v);
            if (m != null) {
                String name = Json.str(m, "name", Json.str(m, "key", ""));
                boolean down = Json.bool(m, "down", true);
                return ClientInput.press(mc, name, down);
            }
            return ClientInput.press(mc, String.valueOf(v), true);
        }
        if ("press".equals(op)) return ClientInput.press(mc, String.valueOf(v), true);
        if ("release".equals(op)) return ClientInput.press(mc, String.valueOf(v), false);
        if ("hotbar".equals(op)) return ClientInput.hotbar(mc, num(v, 0).intValue());
        if ("look".equals(op)) {
            Map<String, Object> m = Json.asMap(v);
            if (m != null && (m.containsKey("x") || m.containsKey("y") || m.containsKey("z"))) {
                return ClientInput.lookAt(mc, num(m.get("x")).doubleValue(),
                        num(m.get("y")).doubleValue(), num(m.get("z")).doubleValue());
            }
            if (m != null) {
                return ClientInput.look(mc, num(m.get("yaw")).floatValue(), num(m.get("pitch")).floatValue());
            }
            List<Object> l = list(v);
            if (l != null && l.size() >= 2) {
                return ClientInput.look(mc, num(l.get(0)).floatValue(), num(l.get(1)).floatValue());
            }
            return "bad-args: look 需要 yaw/pitch 或 x/y/z";
        }
        if ("turn".equals(op)) {
            Map<String, Object> m = Json.asMap(v);
            if (m != null) {
                return ClientInput.turn(mc, num(m.get("dyaw")).floatValue(), num(m.get("dpitch")).floatValue());
            }
            List<Object> l = list(v);
            if (l != null) return ClientInput.turn(mc, num(l.get(0)).floatValue(), num(l.get(1)).floatValue());
            return "bad-args: turn 需要 dyaw/dpitch";
        }
        if ("lookat".equals(op) || "at".equals(op)) {
            double[] p = xyz(v);
            return p == null ? "bad-args: lookAt 需要 x/y/z" : ClientInput.lookAt(mc, p[0], p[1], p[2]);
        }
        if ("dig".equals(op)) {
            double[] p = xyz(v);
            if (p == null) return ClientActions.dig(mc, null, null, null);
            return ClientActions.dig(mc, Integer.valueOf((int) p[0]), Integer.valueOf((int) p[1]),
                    Integer.valueOf((int) p[2]));
        }
        if ("place".equals(op)) {
            Map<String, Object> m = Json.asMap(v);
            Integer face = m == null ? null : Json.integer(m, "face");
            double[] p = xyz(v);
            if (p == null) return ClientActions.place(mc, null, null, null, face);
            return ClientActions.place(mc, Integer.valueOf((int) p[0]), Integer.valueOf((int) p[1]),
                    Integer.valueOf((int) p[2]), face);
        }
        if ("use".equals(op)) return ClientActions.use(mc);
        if ("attack".equals(op)) return ClientActions.attack(mc, v == null ? "crosshair" : String.valueOf(v));
        if ("chat".equals(op)) {
            String text = String.valueOf(v == null ? "" : v);
            if (text.trim().isEmpty()) return "empty-text";
            mc.thePlayer.sendChatMessage(text);
            return "ok: chat " + text;
        }
        if ("cmd".equals(op)) {
            String line = String.valueOf(v == null ? "" : v);
            if (line.trim().isEmpty()) return "empty-command";
            if (line.startsWith("/")) line = line.substring(1);
            int r = net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, line);
            if (r == 0) {
                mc.thePlayer.sendChatMessage("/" + line);
                return "ok: sent-to-server /" + line;
            }
            return "ok: client-result " + r;
        }
        if ("shot".equals(op)) return ClientActions.shot(mc, v == null ? null : String.valueOf(v));
        if ("click".equals(op)) return ClientActions.clickButton(mc, num(v, 0).intValue());
        if ("clickat".equals(op)) {
            Map<String, Object> m = Json.asMap(v);
            if (m == null) return "bad-args: clickat 需要 {x,y}";
            return ClientActions.clickAt(mc, num(m.get("x")).intValue(), num(m.get("y")).intValue(),
                    m.containsKey("button") ? num(m.get("button")).intValue() : 0);
        }
        if ("close".equals(op)) return ClientActions.closeScreen(mc);
        return "unknown-action: " + s.op;
    }

    // ------------------------------------------------------------ 取值

    private static List<Object> list(Object v) {
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return l;
        }
        return null;
    }

    private static double[] xyz(Object v) {
        Map<String, Object> m = Json.asMap(v);
        if (m != null && m.containsKey("x") && m.containsKey("y") && m.containsKey("z")) {
            return new double[]{num(m.get("x")).doubleValue(), num(m.get("y")).doubleValue(),
                    num(m.get("z")).doubleValue()};
        }
        List<Object> l = list(v);
        if (l != null && l.size() >= 3) {
            return new double[]{num(l.get(0)).doubleValue(), num(l.get(1)).doubleValue(),
                    num(l.get(2)).doubleValue()};
        }
        return null;
    }

    private static Number num(Object v, int def) {
        Number n = num(v);
        return n == null ? Integer.valueOf(def) : n;
    }

    private static Number num(Object v) {
        if (v instanceof Number) return (Number) v;
        if (v instanceof List) {
            List<Object> l = list(v);
            if (l != null && !l.isEmpty()) return num(l.get(0));
        }
        if (v == null) return null;
        try {
            return Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
