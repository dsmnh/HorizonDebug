package dev.dsmnh.horizon.debug.metrics;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.dsmnh.horizon.debug.HorizonDebug;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 服务端 TPS / MSPT 采集（移植自 AEMobile 同名类）：总体一份、每个已加载维度各一份。
 *
 * <p>数据来源全部是 Forge 原生事件：总体取 {@link TickEvent.ServerTickEvent} 的 START → END，
 * 单维度取 {@link TickEvent.WorldTickEvent} 的 START → END（Thermos/Forge 在维度 tick 前后
 * 分别 post，事件覆盖整个维度 tick）。开销为每维度每 tick 两次 {@code System.nanoTime()}
 * 加三次 long 自增。只保留最近 5 秒的滚动窗口（6 个 1 秒桶），每秒发布一次不可变快照；
 * {@link #onServerTick()} / {@link #onWorldTick()} 在主线程，{@link #snapshot()} 可在任意线程调用。
 */
public final class TickStats {

    /** 环形桶个数：报告最近 5 个完整秒，多留 1 格给正在累计的这一秒 */
    private static final int BUCKETS = 6;
    /** 玩家/实体/区块这类慢变量的采样间隔（tick） */
    private static final int SAMPLE_EVERY_TICKS = 20;
    /** 世界清单（名字、维度名、alias）的刷新间隔（tick） */
    private static final int WORLDS_EVERY_TICKS = 100;

    private static final TickStats INSTANCE = new TickStats();

    public static TickStats get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------ 状态

    /** 维度 → 累计器。只在主线程读写 */
    private final Map<Integer, Acc> accs = new HashMap<Integer, Acc>();

    private final long[] srvNanos = new long[BUCKETS];
    private final long[] srvTicks = new long[BUCKETS];
    private final long[] srvMax = new long[BUCKETS];
    private long srvStart;

    /** 当前正在累计的桶下标 */
    private int cur;
    /** 当前桶对应的 epoch 秒 */
    private long bucketSec;
    private int tickCounter;

    /** 最近一次发布的快照；读取方只访问这个不可变对象 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private TickStats() {
    }

    /** 停止采集时清空，下次启动从零开始（避免把上一段的桶当成最近 5 秒） */
    public void reset() {
        accs.clear();
        for (int i = 0; i < BUCKETS; i++) {
            srvNanos[i] = 0;
            srvTicks[i] = 0;
            srvMax[i] = 0;
        }
        srvStart = 0;
        cur = 0;
        bucketSec = 0;
        tickCounter = 0;
        snapshot = Snapshot.EMPTY;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    // ------------------------------------------------------------ 采集

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            srvStart = now;
            return;
        }

        long sec = System.currentTimeMillis() / 1000L;
        if (sec != bucketSec) {
            roll(sec);
            // 每秒发布一次：读取方任何时候拿到的都是最近 5 个完整秒，不会读到累计中的数组
            publish();
        }

        if (srvStart != 0) {
            long d = now - srvStart;
            srvNanos[cur] += d;
            srvTicks[cur]++;
            if (d > srvMax[cur]) srvMax[cur] = d;
        }

        tickCounter++;
        if (tickCounter % SAMPLE_EVERY_TICKS == 0) sampleCounts();
        if (tickCounter % WORLDS_EVERY_TICKS == 0) refreshWorlds();
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        World world = event.world;
        if (world == null || world.isRemote) return;

        int dim = world.provider.dimensionId;
        Acc acc = accs.get(dim);
        if (acc == null) {
            // 新维度：先建累计器，名字等下一次 refreshWorlds 补上
            acc = new Acc(dim);
            accs.put(dim, acc);
        }

        if (event.phase == TickEvent.Phase.START) {
            acc.start = System.nanoTime();
            return;
        }
        if (acc.start == 0) return;
        long d = System.nanoTime() - acc.start;
        acc.nanos[cur] += d;
        acc.ticks[cur]++;
        if (d > acc.max[cur]) acc.max[cur] = d;
    }

    /** 换桶：清空即将写入的那格；间隔超过桶数（服务器停顿过久）时整体清空，避免读到陈旧数据 */
    private void roll(long sec) {
        if (bucketSec != 0 && sec - bucketSec > BUCKETS) {
            for (int i = 0; i < BUCKETS; i++) {
                srvNanos[i] = 0;
                srvTicks[i] = 0;
                srvMax[i] = 0;
            }
            for (Acc a : accs.values()) a.clearBuckets();
        }
        bucketSec = sec;
        cur = (int) (sec % BUCKETS);
        srvNanos[cur] = 0;
        srvTicks[cur] = 0;
        srvMax[cur] = 0;
        for (Acc a : accs.values()) a.clearBucket(cur);
    }

    /** 玩家数 / 实体数 / 区块数：每秒读一次 int，成本可忽略 */
    private void sampleCounts() {
        for (Acc acc : accs.values()) {
            World world = DimensionManager.getWorld(acc.dim);
            if (world == null) continue;
            acc.players = world.playerEntities.size();
            acc.entities = world.loadedEntityList.size();
            acc.chunks = world.getChunkProvider().getLoadedChunkCount();
        }
    }

    /** 每 5 秒重建一次世界清单：维度以运行时注册表为准（Multiverse 的 worlds.yml 收录不全） */
    private void refreshWorlds() {
        try {
            WorldNames.reloadIfChanged();
            // 1.7.10 的 DimensionManager.getIDs() 返回 Integer[]，不是 Set
            Integer[] ids = DimensionManager.getIDs();
            if (ids == null) return;
            accs.keySet().retainAll(new HashSet<Integer>(Arrays.asList(ids)));
            for (Integer id : ids) {
                if (id == null) continue;
                Acc acc = accs.get(id);
                if (acc == null) {
                    acc = new Acc(id);
                    accs.put(id, acc);
                }
                World world = DimensionManager.getWorld(id);
                if (world == null) {
                    acc.loaded = false;
                    continue;
                }
                acc.loaded = true;
                acc.rawName = safe(world.getWorldInfo() == null ? null : world.getWorldInfo().getWorldName());
                acc.provider = safe(world.provider == null ? null : world.provider.getDimensionName());
                acc.personal = WorldNames.isPersonal(acc.rawName);
                acc.display = WorldNames.display(acc.rawName, id, acc.provider);
            }
        } catch (Throwable t) {
            // 采集不得影响 tick：记录日志后继续
            HorizonDebug.warn("[HorizonDebug] 刷新世界清单失败: " + t);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ 发布

    private void publish() {
        long at = System.currentTimeMillis();

        long n = 0, t = 0, m = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (i == cur) continue;
            n += srvNanos[i];
            t += srvTicks[i];
            if (srvMax[i] > m) m = srvMax[i];
        }
        double srvMspt = t == 0 ? 0 : n / 1e6 / t;
        double srvMaxMs = m / 1e6;

        List<WorldSnap> list = new ArrayList<WorldSnap>(accs.size());
        int totalPlayers = 0;
        for (Acc acc : accs.values()) {
            long wn = 0, wt = 0, wm = 0;
            for (int i = 0; i < BUCKETS; i++) {
                if (i == cur) continue;
                wn += acc.nanos[i];
                wt += acc.ticks[i];
                if (acc.max[i] > wm) wm = acc.max[i];
            }
            double mspt = wt == 0 ? 0 : wn / 1e6 / wt;
            totalPlayers += acc.players;
            list.add(new WorldSnap(acc.dim, acc.display, acc.rawName, acc.provider, acc.personal,
                    acc.loaded, acc.players, acc.entities, acc.chunks, mspt, wm / 1e6, tpsOf(mspt), (int) wt));
        }

        snapshot = new Snapshot(at, srvMspt, srvMaxMs, tpsOf(srvMspt), (int) t, totalPlayers,
                list.toArray(new WorldSnap[list.size()]));
    }

    /**
     * 由单 tick 耗时折算 TPS：50ms 以内都是满速 20，超出按 1000/mspt 计算。
     * 单维度的 TPS 是「只剩这个维度」的等效值，与 {@code /cofh tps} 的口径一致。
     */
    private static double tpsOf(double mspt) {
        if (mspt <= 0) return 0;
        return mspt <= 50.0 ? 20.0 : 1000.0 / mspt;
    }

    // ------------------------------------------------------------ 数据结构

    /** 单维度累计器（主线程独占） */
    private static final class Acc {
        final int dim;
        long start;
        final long[] nanos = new long[BUCKETS];
        final long[] ticks = new long[BUCKETS];
        final long[] max = new long[BUCKETS];
        int players;
        int entities;
        int chunks;
        boolean loaded;
        boolean personal;
        String rawName = "";
        String provider = "";
        String display = "";

        Acc(int dim) {
            this.dim = dim;
        }

        void clearBucket(int i) {
            nanos[i] = 0;
            ticks[i] = 0;
            max[i] = 0;
        }

        void clearBuckets() {
            for (int i = 0; i < BUCKETS; i++) clearBucket(i);
        }
    }

    /** 一次发布的完整快照，不可变 */
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, new WorldSnap[0]);

        /** 生成时间（epoch 毫秒） */
        public final long at;
        public final double srvMspt;
        public final double srvMax;
        public final double srvTps;
        public final int srvTicks;
        public final int players;
        public final WorldSnap[] worlds;

        Snapshot(long at, double srvMspt, double srvMax, double srvTps, int srvTicks, int players, WorldSnap[] worlds) {
            this.at = at;
            this.srvMspt = srvMspt;
            this.srvMax = srvMax;
            this.srvTps = srvTps;
            this.srvTicks = srvTicks;
            this.players = players;
            this.worlds = worlds;
        }
    }

    /** 单个维度的快照 */
    public static final class WorldSnap {
        public final int dim;
        /** 显示名（Multiverse alias > config 覆盖 > 内置表 > provider 英文名 > 世界名） */
        public final String name;
        /** 世界文件夹名，例如 {@code PERSONAL_DIM_180} */
        public final String rawName;
        /** {@code WorldProvider.getDimensionName()}，英文，供排查使用 */
        public final String provider;
        public final boolean personal;
        public final boolean loaded;
        public final int players;
        public final int entities;
        public final int chunks;
        public final double mspt;
        public final double max;
        public final double tps;
        /** 窗口内的 tick 数；0 表示这段时间该维度没有 tick（未加载或刚启动） */
        public final int ticks;

        WorldSnap(int dim, String name, String rawName, String provider, boolean personal, boolean loaded,
                  int players, int entities, int chunks, double mspt, double max, double tps, int ticks) {
            this.dim = dim;
            this.name = name;
            this.rawName = rawName;
            this.provider = provider;
            this.personal = personal;
            this.loaded = loaded;
            this.players = players;
            this.entities = entities;
            this.chunks = chunks;
            this.mspt = mspt;
            this.max = max;
            this.tps = tps;
            this.ticks = ticks;
        }
    }
}
