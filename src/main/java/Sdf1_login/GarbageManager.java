package Sdf1_login;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.*;

public class GarbageManager implements Listener {

    private final Main plugin;
    private Connection db;
    private int cleanupInterval = 1800;
    private int maxRounds = 1;
    private boolean enabled = true;
    private int currentRound = 0;
    private final Map<String, Integer>
            recyclePages = new HashMap<>();

    /**
     * ★ 第31批：本轮开始的墙钟时间戳（毫秒）。
     * 血条色段按真实时间判定，与 tick 计数解耦，
     * 避免“第3分30秒还是绿色”的漂移。0 = 本轮未开始。
     */
    private long roundStartMs = 0L;

    /**
     * ★ 第34批：已广播的倒计时节点位图。
     * 墙钟口径下 remain 可能跨值（如 301→299），原来的 `remain == 300`
     * 式判断会漏播；改用「<= 命中一次 + 位图去重」，卡顿也不漏。
     * bit0=5分钟 bit1=3分钟 bit2=1分钟 bit3=30秒 bit4=10秒
     */
    private int announcedMask = 0;

    /**
     * ★ 第30批：顶部血条（玩家 UUID → BossBar）
     */
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    /**
     * ★ 第30批：双击 Q 确认丢弃的首次按键时间（玩家 UUID → 毫秒）
     */
    private final Map<UUID, Long> pendingDrops = new HashMap<>();


    public GarbageManager(Main plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    new File(plugin.getDataFolder(), "垃圾箱"),
                    "garbage.db");
            dbFile.getParentFile().mkdirs();
            db = DriverManager.getConnection(
                    "jdbc:sqlite:"
                            + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "items ("
                    + "id INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT,"
                    + "item_data TEXT NOT NULL,"
                    + "amount INTEGER DEFAULT 1,"
                    + "round INTEGER DEFAULT 0,"
                    + "create_time INTEGER NOT NULL"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "config ("
                    + "key TEXT PRIMARY KEY,"
                    + "value TEXT NOT NULL"
                    + ")");
            st.close();
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Garbage] DB失败: "
                            + e.getMessage());
        }
    }

    public void loadConfig() {
        // 从 ConfigManager 读取
        cleanupInterval =
                plugin.getConfig2()
                        .garbageInterval;
        enabled =
                plugin.getConfig2()
                        .garbageEnabled;
        maxRounds =
                plugin.getConfig2()
                        .garbageMaxRounds;

        // 从数据库读取当前轮次
        try {
            String val = getConfigValue("round");
            if (val != null)
                currentRound =
                        Integer.parseInt(val);
        } catch (Exception ignored) {
        }

        plugin.getLogger().info(
                "[Garbage] 间隔: "
                        + cleanupInterval + "秒, "
                        + "轮次保留: "
                        + maxRounds + "轮, "
                        + "启用: " + enabled);
    }


    private String getConfigValue(String key) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT value FROM config "
                            + "WHERE key=?");
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            String val = rs.next()
                    ? rs.getString("value") : null;
            rs.close();
            ps.close();
            return val;
        } catch (Exception e) {
            return null;
        }
    }

    private void setConfigValue(String key,
                                String value) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO config "
                            + "(key, value) "
                            + "VALUES (?, ?)");
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startAutoCleanup() {
        if (!enabled) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                // 在线人数≤0时暂停
                if (Bukkit.getOnlinePlayers()
                        .isEmpty()) {
                    // ★ 第34批：墙钟口径 —— 全员离线则本轮暂停，
                    //   玩家回来时重新记录起点（重新计时）
                    roundStartMs = 0L;
                    announcedMask = 0;
                    return;
                }

                // ★ 第31批：本轮墙钟起点（0=新轮开始，色段按真实时间判定）
                if (roundStartMs == 0L)
                    roundStartMs = System.currentTimeMillis();

                // 重新读取配置（支持热更）
                cleanupInterval =
                        plugin.getConfig2()
                                .garbageInterval;

                // ★ 第34批：唯一时间源（墙钟）—— 血条 / 广播 / 抢救打印 /
                //   丢弃拦截 全部读 remainSeconds()，误差 ±0 秒。
                int remain = remainSeconds();

                // ★ 第34批：节点广播改「<= 命中一次 + 位图去重」
                //   （墙钟口径 remain 可能跨值，== 判断会漏播）
                boolean nodeHit = false;
                if (cleanupInterval >= 300
                        && remain <= 300
                        && (announcedMask & 1) == 0) {
                    announcedMask |= 1;
                    nodeHit = true;
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e5分钟");
                }
                if (cleanupInterval >= 180
                        && remain <= 180
                        && (announcedMask & 2) == 0) {
                    announcedMask |= 2;
                    nodeHit = true;
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e3分钟");
                }
                if (cleanupInterval >= 60
                        && remain <= 60
                        && (announcedMask & 4) == 0) {
                    announcedMask |= 4;
                    nodeHit = true;
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e1分钟");
                }
                if (cleanupInterval >= 30
                        && remain <= 30
                        && (announcedMask & 8) == 0) {
                    announcedMask |= 8;
                    nodeHit = true;
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e30秒");
                }
                if (cleanupInterval >= 10
                        && remain <= 10
                        && (announcedMask & 16) == 0) {
                    announcedMask |= 16;
                    nodeHit = true;
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e10秒");
                }
                if (nodeHit) {
                    // ★ 第31批（#204/#205）：垃圾箱进入下一节点 → 查一次 插件设置.txt，
                    //   有变化整份重载（ConfigManager 内部比对，无变化静默、有变化打日志）
                    plugin.getConfig2()
                            .reloadSettingsIfChanged();
                }
                if (remain <= 5 && remain > 0)
                    broadcast("§e§l[垃圾站] §c" + remain + "§f...");
                if (remain == 0) {
                    doCleanup();
                    // ★ 第34批：新一轮开始 —— 清墙钟起点与节点位图
                    roundStartMs = 0L;
                    announcedMask = 0;
                    return;
                }
                // ★ 第30批：顶部血条常驻倒计时（软提醒）
                // ★ 第32批：血条只受 垃圾站_启用 控制，与启发式开关解耦
                // ★ 第34批：remain 取自墙钟唯一时间源，与广播/抢救打印同源
                updateBossBars(remain);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * ★ 第30批：顶部血条常驻倒计时 + 色段软提醒。
     *
     * <p>★ 第33批（#211）：色段改按「剩余时间」纯数值分段（用户明令）：
     * <ul>
     *   <li>剩余 ≥ 180 秒 → 绿 = 安全期</li>
     *   <li>30 秒 < 剩余 < 180 秒 → 黄 = 警告（只提示快动作了，
     *       丢的时候注意安全控好量，不误导「别丢」）</li>
     *   <li>剩余 ≤ 30 秒 → 红 = 危险</li>
     * </ul>
     * 第31批的「墙钟绝对时刻」方案废弃；百分比方案更早废弃（间隔一改就漂移）。
     *
     * <p>★ 第32批：血条与启发式开关解耦 —— 只要垃圾站启用，血条常驻显示；
     * 启发式开关（垃圾站_启发式拦截）关闭时只关掉「黄/红区title警告 + 末5秒双击Q拦截」，
     * 不再撤血条（否则玩家对清理状态一无所知）。
     */
    private void updateBossBars(int remain) {
        int interval = Math.max(1, cleanupInterval);
        double progress = (double) remain / (double) interval;
        if (progress < 0.0D) progress = 0.0D;
        if (progress > 1.0D) progress = 1.0D;

        BossBar.Color color;
        String label;
        if (remain <= 30) {
            color = BossBar.Color.RED;
            label = "§c§l危险 §7| §c马上清理，抓紧捡";
        } else if (remain < 180) {
            color = BossBar.Color.YELLOW;
            label = "§e§l注意 §7| §e快动作了，注意安全控好量";
        } else {
            color = BossBar.Color.GREEN;
            label = "§a§l安全期 §7| §a还早，放心丢";
        }
        String title = "§6§l垃圾站 §8| " + label
                + " §8| §f剩余 " + fmtRemain(remain);

        Component comp = LegacyComponentSerializer
                .legacySection().deserialize(title);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            BossBar bar = bossBars.get(uuid);
            if (bar == null) {
                // Adventure 的 BossBar 按观众分发，必须每人一份
                bar = BossBar.bossBar(comp, (float) progress,
                        color, BossBar.Overlay.NOTCHED_10);
                bossBars.put(uuid, bar);
                p.showBossBar(bar);
            } else {
                bar.name(comp);
                bar.color(color);
                if (Math.abs(bar.progress() - progress) > 0.001D) {
                    bar.progress((float) progress);
                }
            }
        }
    }

    /** 撤掉所有玩家的顶部血条（插件关闭/停服时用；启发式开关不再触发） */
    private void hideAllBossBars() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar bar = bossBars.get(p.getUniqueId());
            if (bar != null) {
                try {
                    p.hideBossBar(bar);
                } catch (Throwable ignored) {
                }
            }
        }
        bossBars.clear();
    }

    /** 剩余秒数格式化为 mm:ss */
    private String fmtRemain(int remain) {
        if (remain < 0) remain = 0;
        int m = remain / 60;
        int s = remain % 60;
        return (m < 10 ? "0" + m : String.valueOf(m))
                + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    /**
     * ★ 第34批：剩余秒数「唯一时间源」（墙钟推算）。
     *
     * <p>根因：旧实现里「血条 / 广播」读的是 tick 计数（increment 前的值），
     * 而「抢救打印 / 指令显示 / 丢弃拦截」读的是 increment 后的值，
     * 两者恒差 1 秒（用户截图：血条 02:47 vs 打印 02:46）。
     *
     * <p>现统一按墙钟推算：距本轮结束 = roundStartMs + 清理间隔 - now，
     * 向上取整到秒。血条标题、抢救提示、指令显示、丢弃拦截全部读本方法，
     * 保证 ±0 秒误差。
     *
     * @return 剩余秒数（0 = 本轮已到点 / 未开始）
     */
    private int remainSeconds() {
        if (roundStartMs <= 0L) {
            // 本轮尚未开始（刚启动 / 抢救后 / 全员离线）→ 视为满格
            return Math.max(0, cleanupInterval);
        }
        long endMs = roundStartMs + (long) cleanupInterval * 1000L;
        long leftMs = endMs - System.currentTimeMillis();
        if (leftMs <= 0L) return 0;
        // 向上取整到秒：剩余 2.001s 显示 3 秒，2.000s 显示 2 秒
        return (int) ((leftMs + 999L) / 1000L);
    }

    /**
     * ★ 第30批：抢救模式 —— 跳过本轮清理并重新开始计时。
     * 玩家物品被错误投入垃圾箱时可急停抢救。
     *
     * @return true=抢救成功（本轮清理已取消）
     */
    public boolean rescueRound(Player p) {
        if (!enabled) {
            if (p != null)
                p.sendMessage("§c§l[垃圾站] §f垃圾站未启用，无法抢救");
            return false;
        }
        // ★ 第34批：改用墙钟唯一时间源（与头顶血条显示值完全一致）
        if (remainSeconds() <= 0) {
            if (p != null)
                p.sendMessage("§c§l[垃圾站] §f本轮清理已经结束了，来不及抢救");
            return false;
        }
        // ★ 第31批（#203）：抢救窗口 = 本轮最后 1 分钟；未到时间不可触发
        // ★ 第34批：与血条同源（墙钟）—— 打印值 = 头顶血条值（±0 秒）
        int remainSec = remainSeconds();
        if (remainSec > 60) {
            if (p != null)
                p.sendMessage("§c§l[垃圾站] §f未到抢救窗口：本轮清理还剩 §e"
                        + fmtRemain(remainSec)
                        + "§f，最后 1 分钟内才能发起抢救");
            else
                plugin.getLogger().info(
                        "[垃圾站] 抢救被拒：还剩 " + remainSec
                                + " 秒，未进入最后1分钟窗口");
            return false;
        }
        // ★ 第34批：取消本轮 —— 清墙钟起点与节点位图，计时重新开始
        roundStartMs = 0L;
        announcedMask = 0;
        pendingDrops.clear();
        String who = p != null ? p.getName() : "控制台";
        broadcast("§a§l[垃圾站] §f玩家 §e" + who
                + " §f发起抢救，本轮清理已取消，计时重新开始");
        if (p != null)
            p.sendMessage("§a§l[垃圾站] §f抢救成功！本轮清理已取消，"
                    + "倒计时重新开始");
        return true;
    }

    /**
     * 当前剩余秒数（指令/UI 显示用）。
     * ★ 第34批：与头顶血条、抢救打印完全同源（墙钟），不再有 ±1 秒差。
     */
    public int getRemainSeconds() {
        return remainSeconds();
    }

    /**
     * ★ 第30批：垃圾箱丢弃拦截。
     *
     * <p>★ 第33批（#211）三处改造：
     * <ul>
     *   <li>警告期（黄区 30~180 秒）不加任何 title 干预 —— 该期的提示
     *       只走头顶血条（updateBossBars 文案已改「注意安全控好量」）；</li>
     *   <li>拦截窗口从末 10 秒收窄到末 5 秒，且仅启发式开关启用时生效；</li>
     *   <li>确认窗口从 2 秒收窄到 1 秒：拦下第一次 Q，1 秒内连按
     *       第二次 = 确认丢弃放行；超过 1 秒视为过期跳过（重新计时）。</li>
     * </ul>
     */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (!enabled) return;
        if (!plugin.getConfig2().garbageHeuristic) return;
        // ★ 第34批：与血条同源（墙钟）
        int remain = remainSeconds();
        if (remain > 30) return;   // 安全区+警告期（>30秒）→ 不干涉

        Player p = e.getPlayer();
        // 危险期（红区 ≤30秒）小标题：按用户口径「注意安全控好量」，不写「别丢」
        p.sendTitle("", "§c§l垃圾站警告 §f马上清理了，注意安全控好量！",
                3, 30, 5);

        if (remain > 5) return;   // ★ 拦截仅在最后 5 秒窗口

        long now = System.currentTimeMillis();
        UUID uuid = p.getUniqueId();
        Long first = pendingDrops.get(uuid);
        if (first != null && now - first <= 1000L) {
            pendingDrops.remove(uuid);
            return;                 // ★ 1 秒窗口内连按 2 次 Q = 确认丢弃 → 放行
        }
        // 首次按键（或上次按键已超 1 秒窗口 → 视为过期，重新计时）
        pendingDrops.put(uuid, now);
        e.setCancelled(true);
        p.sendMessage("§e§l[垃圾站] §e马上清理了！1秒内再按一次 §eQ§f 确认丢弃，"
                + "或用 §e/垃圾抢救§f 取消本轮清理");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        // 下一轮 tick 会自动建血条（第32批：不再受启发式开关守卫），
        // 这里只清掉可能残留的按键状态
        pendingDrops.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        pendingDrops.remove(uuid);
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            try {
                e.getPlayer().hideBossBar(bar);
            } catch (Throwable ignored) {
            }
        }
    }

    private void doCleanup() {
        // ★ 过期轮次删除是阻塞 DB -> 先异步执行；
        //   轮次递增 / 实体收集 / 广播 必须主线程 -> 异步完成后回主线程
        // ★ 第34批（#217）：保留轮次语义按用户思维导图重写 ——
        //   N = 目标保留轮次（配置 垃圾站_保留轮数）
        //   k = 当前轮次（本方法入口时 currentRound = k-1）
        //   公式余数 = N - k；余数为 0（k % N == 0）的那一轮才「永久倒垃圾」，
        //   其余轮次只把地面掉落物收进回收站，跳过永久删除。
        //   N=3 → k=1(余2)跳过 / k=2(余1)跳过 / k=3(余0)倒垃圾。
        //   到期批次 = 存活满 N 轮的批次（批次 j 存于第 j 轮，
        //   到第 j+N 轮才满 N 轮）→ j <= k - N。
        //   验证 N=2：k=1 跳过；k=2 倒垃圾(第1批尚未满2轮，删0件)；
        //             k=3 跳过；k=4 倒垃圾(第1、2批满2轮，删除)。
        //   验证 N=3：k=1/2 跳过；k=3 倒垃圾(无到期)；k=6 倒垃圾(删第1、2、3批)。
        //   性质：每个倒垃圾轮次结束后，回收站恰好保留 N 个批次的垃圾。
        final int k = currentRound + 1;         // 当前轮次（1 起）
        final int n = Math.max(1, maxRounds);   // 目标保留轮次
        final boolean purgeThisRound = (k % n == 0);
        final int deleteRound = k - n;           // <= 该批次的都存活满 n 轮
        Bukkit.getScheduler().runTaskAsynchronously(
                plugin, () -> {
                    final int deleted =
                            (purgeThisRound && deleteRound >= 0)
                                    ? deleteOldItems(deleteRound)
                                    : 0;
                    Bukkit.getScheduler().runTask(
                            plugin, () -> {
                                if (purgeThisRound) {
                                    if (deleted > 0) {
                                        broadcast("§7[垃圾站] §f永久清理了 §e"
                                                + deleted + " §f个过期物品");
                                    }
                                } else {
                                    // ★ 第34批：非倒垃圾轮次明确告知，避免
                                    //   「每轮都在清理」的误解
                                    broadcast("§7[垃圾站] §f本轮跳过永久清理"
                                            + "（保留 §e" + n
                                            + " §f轮，每 §e" + n
                                            + " §f轮才永久倒一次垃圾）");
                                }
                                // 递增轮次
                                currentRound++;
                                final int roundNow =
                                        currentRound;
                                // ★ 轮次落库是 DB -> 异步
                                Bukkit.getScheduler()
                                        .runTaskAsynchronously(
                                                plugin, () ->
                                                        setConfigValue("round",
                                                                String.valueOf(roundNow)));

                                // 收集当前掉落物
                                // （实体移除必须主线程，落库已在内部转异步）
                                int count = collectItems();
                                broadcast("§a§l[垃圾站] §f清理完成，"
                                        + "共清理 §e" + count
                                        + " §f个掉落物，存入回收站");

                                // 打印下次清理时间
                                long nextMs = System.currentTimeMillis()
                                        + (long) cleanupInterval * 1000L;
                                String nextTime =
                                        new SimpleDateFormat("HH:mm:ss")
                                                .format(new Date(nextMs));
                                broadcast("§e§l[垃圾站] §f下次清理时间: §a"
                                        + nextTime);
                            });
                });
    }

    public int collectItems() {
        int count = 0;
        List<ItemStack> picked = new ArrayList<>();
        for (org.bukkit.World w :
                Bukkit.getWorlds()) {
            Collection<Item> items =
                    w.getEntitiesByClass(Item.class);
            for (Item item : items) {
                ItemStack stack =
                        item.getItemStack();
                if (stack == null
                        || stack.getType()
                        == Material.AIR)
                    continue;
                // ★ 先取快照再移除实体（实体移除必须主线程）
                picked.add(stack.clone());
                item.remove();
                count++;
            }
        }
        // ★ 落库是阻塞 DB（含 SN 脱管登记）-> 丢异步批量写
        if (!picked.isEmpty()) {
            final List<ItemStack> toSave = picked;
            Bukkit.getScheduler()
                    .runTaskAsynchronously(plugin, () -> {
                        for (ItemStack s : toSave)
                            saveItem(s);
                    });
        }
        return count;
    }
    public void saveItem(ItemStack stack) {
        try {
            // ★ SN 物品进垃圾箱 = 脱离玩家本人管控，起算 12 小时找回期。
            //   shift 投入 / 光标放入 / 拖拽 / 扫地机收地面掉落全走这里。
            if (plugin.getSnManager() != null) {
                plugin.getSnManager().onConfiscated(stack, "垃圾站");
            }
            String data = stackToBase64(stack);
            if (data.isEmpty()) return;
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO items "
                            + "(item_data, amount, "
                            + "round, create_time) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, data);
            ps.setInt(2, stack.getAmount());
            ps.setInt(3, currentRound);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private int deleteOldItems(int beforeRound) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM items "
                            + "WHERE round <= ?");
            ps.setInt(1, beforeRound);
            int rows = ps.executeUpdate();
            ps.close();
            return rows;
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== 序列化 ==========

    private String stackToBase64(ItemStack stack) {
        try {
            Map<String, Object> map =
                    stack.serialize();
            String json = new Gson().toJson(map);
            return Base64.getEncoder()
                    .encodeToString(
                            json.getBytes(
                                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack base64ToStack(String base64) {
        try {
            byte[] bytes = Base64.getDecoder()
                    .decode(base64);
            String json = new String(bytes,
                    StandardCharsets.UTF_8);
            Map<String, Object> map =
                    new Gson().fromJson(json, Map.class);
            map = fixNumberTypes(map);
            return ItemStack.deserialize(map);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixNumberTypes(
            Map<String, Object> map) {
        Map<String, Object> result =
                new LinkedHashMap<>();
        for (Map.Entry<String, Object> e
                : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Double) {
                double d = (Double) v;
                if (d == (long) d) {
                    result.put(e.getKey(), (long) d);
                } else {
                    result.put(e.getKey(), d);
                }
            } else if (v instanceof Map) {
                result.put(e.getKey(),
                        fixNumberTypes(
                                (Map<String, Object>) v));
            } else if (v instanceof List) {
                List<Object> list =
                        new ArrayList<>();
                for (Object item : (List<?>) v) {
                    if (item instanceof Map) {
                        list.add(fixNumberTypes(
                                (Map<String, Object>) item));
                    } else {
                        list.add(item);
                    }
                }
                result.put(e.getKey(), list);
            } else {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    // ========== 回收站GUI ==========
    public void openRecycle(Player p) {
        openRecyclePage(p, 0);
    }

    public void openRecyclePage(Player p, int page) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§6§l垃圾回收站");

        List<Map<String, Object>> allItems =
                getStoredItems(999);
        int total = allItems.size();
        int pageSize = 40;
        int totalPages = Math.max(1,
                (int) Math.ceil(
                        (double) total / pageSize));

        // 修正页码
        if (page < 0) page = 0;
        if (page >= totalPages)
            page = totalPages - 1;

        recyclePages.put(p.getName(), page);

        // 填充物品（0-39 格 = 第1页，40-79 = 第2页...）
        int start = page * pageSize;
        int end = Math.min(start + pageSize,
                total);
        int slot = 0;
        for (int i = start; i < end; i++) {
            if (slot >= 40) break;
            Map<String, Object> item =
                    allItems.get(i);
            int id = ((Number) item.get("id"))
                    .intValue();
            String data = (String) item
                    .get("item_data");
            int amount = ((Number) item
                    .get("amount")).intValue();
            ItemStack stack = base64ToStack(data);
            if (stack != null) {
                stack.setAmount(amount);
                ItemMeta im =
                        stack.getItemMeta();
                if (im != null) {
                    List<String> lore =
                            new ArrayList<>();
                    lore.add("§7ID: #" + id);
                    lore.add("§a点击取出");
                    im.setLore(lore);
                    stack.setItemMeta(im);
                }
                g.setItem(slot, stack);
            }
            slot++;
        }

        // 49 格：上一页
        if (page > 0) {
            g.setItem(49, mk(Material.ARROW,
                    "§e上一页 §7("
                            + page + "/"
                            + totalPages + ")"));
        } else {
            g.setItem(49, mk(Material.ARROW,
                    "§e第一页 §7("
                            + "1/" + totalPages + ")"));
        }

        // 50 格：统计信息
        g.setItem(50, mk(Material.CHEST,
                "§e回收站统计",
                "§7总存量: §f" + total + " 个",
                "§7当前页: §f" + (page + 1)
                        + "/" + totalPages));

        // 51 格：下一页
        boolean canNext = page < totalPages - 1;
        if (canNext) {
            g.setItem(51, mk(Material.ARROW,
                    "§e下一页 §7("
                            + (page + 2) + "/"
                            + totalPages + ")"));
        } else {
            g.setItem(51, mk(Material.ARROW,
                    "§7已是最后一页"));
        }

        // 52 格：返回主菜单
        g.setItem(52, mk(Material.ARROW,
                "§7返回",
                "§7返回主菜单"));

        p.openInventory(g);
    }

    public int getRecyclePage(String name) {
        return recyclePages
                .getOrDefault(name, 0);
    }


    public boolean handleClick(Player p,
                               String title, int slot) {
        if (!title.equals("§6§l垃圾回收站"))
            return false;
        // 49：上一页
        if (slot == 49) {
            int page = recyclePages
                    .getOrDefault(p.getName(), 0);
            if (page > 0)
                openRecyclePage(p, page - 1);
            return true;
        }
        // 51：下一页
        if (slot == 51) {
            int page = recyclePages
                    .getOrDefault(p.getName(), 0);
            openRecyclePage(p, page + 1);
            return true;
        }
        // 52：返回
        if (slot == 52) {
            plugin.getGui().openMain(p);
            return true;
        }

        // 50/52/53：不处理
        if (slot >= 49 && slot <= 53)
            return true;
        return true;
    }

    private List<Map<String, Object>>
    getStoredItems(int limit) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT id, item_data, "
                                    + "amount "
                                    + "FROM items "
                                    + "ORDER BY "
                                    + "create_time "
                                    + "DESC LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id",
                        rs.getInt("id"));
                row.put("item_data",
                        rs.getString("item_data"));
                row.put("amount",
                        rs.getInt("amount"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public boolean removeItem(int id) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "DELETE FROM items "
                                    + "WHERE id=?");
            ps.setInt(1, id);
            int rows = ps.executeUpdate();
            ps.close();
            return rows > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取回前按 id 读原始存档（含原 lore 与 PDC）。
     * GUI 展示副本的 lore 已被覆盖成
     * 「ID/点击取出」，取回必须用这个还原，
     * 否则 §8SN 行与物品说明 lore 会丢。
     *
     * @param id items 表主键
     * @return 原始物品；读不到返回 null
     */
    public ItemStack fetchItem(int id) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT item_data, amount FROM items "
                            + "WHERE id=?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            ItemStack result = null;
            if (rs.next()) {
                result = base64ToStack(
                        rs.getString("item_data"));
                if (result != null) {
                    result.setAmount(rs.getInt("amount"));
                }
            }
            rs.close();
            ps.close();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public int getItemCount() {
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM items");
            int count = 0;
            if (rs.next())
                count = rs.getInt(1);
            rs.close();
            st.close();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private void broadcast(String msg) {
        // 同步打印到控制台
        plugin.getLogger().info(
                msg.replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
        // 同步打印到聊天栏
        Bukkit.broadcastMessage(msg);
    }


    private ItemStack mk(Material mat,
                         String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) {
                List<String> list =
                        new ArrayList<>();
                for (String l : lore)
                    if (!l.isEmpty()) list.add(l);
                if (!list.isEmpty())
                    im.setLore(list);
            }
            it.setItemMeta(im);
        }
        return it;
    }

    public void close() {
        try {
            hideAllBossBars();
            pendingDrops.clear();
        } catch (Exception ignored) {
        }
        try {
            if (db != null && !db.isClosed())
                db.close();
        } catch (Exception ignored) {
        }
    }
}