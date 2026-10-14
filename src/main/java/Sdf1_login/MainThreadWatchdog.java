package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * 主线程卡顿看门狗（第十五批，2026-10-14）。
 *
 * <p>背景：生产服 2026-10-14 14:52:08~14:52:29 主线程静默 21 秒
 * （玩家在「我的信息」GUI 做自定义菜单图标交换期间），全程无 ERROR、
 * 只有 SSL 断路器 WARN —— 日志里没有任何栈信息能定位卡在哪一帧。
 * 本看门狗用于下次复现时直接抓出现场。
 *
 * <p>机制：
 * <ol>
 *   <li>主线程每 tick 心跳（同步定时任务，主线程一卡心跳就停走）；</li>
 *   <li>独立 daemon 线程每 1 秒检查心跳年龄，≥5 秒 → 立刻把主线程栈
 *       （深度 120 帧）打进日志，标签 {@code [主线程看门狗]}；</li>
 *   <li>持续卡顿每 10 秒补一次栈（观察卡点是否在变）；</li>
 *   <li>恢复后补一条「恢复」日志，带总卡顿时长；</li>
 *   <li>若看门狗自己 sleep 都睡了 3 倍以上时长 → JVM/主机级停顿，
 *       单独记一条（此时单线程栈已无意义）。</li>
 * </ol>
 *
 * <p>生命周期：daemon 线程 + {@code while (plugin.isEnabled())} 自退出，
 * onDisable 后心跳任务被调度器取消 → 1 秒内自然结束，无需手动 stop。
 */
public final class MainThreadWatchdog implements Runnable {

    /** 心跳超过这个年龄视为主线程卡顿 */
    private static final long STALL_MS = 5000L;
    /** 检查间隔 */
    private static final long CHECK_MS = 1000L;
    /** 持续卡顿时补栈间隔 */
    private static final long REDUMP_MS = 10000L;
    /** 抓栈深度 */
    private static final int DEPTH = 120;

    private final JavaPlugin plugin;
    private volatile long heartbeat;
    private volatile boolean stalled;
    private volatile long stallStartAt;
    private volatile long lastDumpAt;

    public MainThreadWatchdog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 在 onEnable 里调用一次即可。 */
    public void start() {
        heartbeat = System.currentTimeMillis();
        // 主线程心跳：每 tick 刷一次；主线程被卡住时它自然停走
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                heartbeat = System.currentTimeMillis();
            }
        }, 1L, 1L);
        Thread t = new Thread(this, "sdf1-main-watchdog");
        t.setDaemon(true);
        t.start();
        plugin.getLogger().info("[主线程看门狗] 已启动（主线程卡顿≥"
                + (STALL_MS / 1000) + " 秒自动抓栈打日志）");
    }

    @Override
    public void run() {
        while (plugin.isEnabled()) {
            long sleepBegin = System.nanoTime();
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException ie) {
                return;
            }
            long sleptMs = (System.nanoTime() - sleepBegin) / 1000000L;
            if (sleptMs > CHECK_MS * 3) {
                // 连看门狗自己都被冻住 → JVM/GC/主机级停顿，栈已无意义
                plugin.getLogger().warning("[主线程看门狗] ★ 全线程停顿约 "
                        + sleptMs + "ms（JVM/主机级，非单点死锁）");
            }
            if (!plugin.isEnabled()) return;
            long now = System.currentTimeMillis();
            long age = now - heartbeat;
            if (age >= STALL_MS) {
                if (!stalled) {
                    stalled = true;
                    stallStartAt = now;
                    lastDumpAt = now;
                    dumpMainThread(age, true);
                } else if (now - lastDumpAt >= REDUMP_MS) {
                    lastDumpAt = now;
                    dumpMainThread(age, false);
                }
            } else if (stalled) {
                stalled = false;
                plugin.getLogger().warning("[主线程看门狗] ✓ 主线程已恢复，本次卡顿共 "
                        + (now - stallStartAt) + "ms");
            }
        }
    }

    private void dumpMainThread(long ageMs, boolean first) {
        plugin.getLogger().warning("[主线程看门狗] ★ 主线程已 " + ageMs
                + "ms 未推进！"
                + (first ? "首次抓栈（卡点看最上面几帧）：" : "补栈："));
        try {
            Thread main = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("Server thread".equals(t.getName())) {
                    main = t;
                    break;
                }
            }
            if (main == null) {
                plugin.getLogger().warning("    (没找到 Server thread)");
                return;
            }
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            ThreadInfo ti = mx.getThreadInfo(main.getId(), DEPTH);
            if (ti == null) {
                plugin.getLogger().warning("    (拿不到主线程 ThreadInfo)");
                return;
            }
            plugin.getLogger().warning("    主线程状态: " + ti.getThreadState());
            for (StackTraceElement el : ti.getStackTrace()) {
                plugin.getLogger().warning("    at " + el);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("    抓栈失败: " + t);
        }
    }
}
