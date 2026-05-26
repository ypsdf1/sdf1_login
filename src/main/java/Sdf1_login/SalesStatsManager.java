package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SalesStatsManager {

    private final Main plugin;

    private final Map<String, Integer> sessionSales =
            new ConcurrentHashMap<>();
    private int sessionRevenue = 0;
    private int sessionOrders = 0;
    private int sessionItems = 0;
    private int sessionRefunds = 0;
    private int sessionRefundAmount = 0;
    private long sessionStart = System.currentTimeMillis();

    // 状态
    private boolean waitingForEmpty = false;
    private boolean hasReported = false;
    private BukkitRunnable waitTask = null;

    public SalesStatsManager(Main plugin) {
        this.plugin = plugin;
        startMonitor();
    }

    // ★ 每10秒检测一次
    private void startMonitor() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int online = Bukkit.getOnlinePlayers().size();

                if (online > 0) {
                    // 有玩家在线 → 重置等待状态
                    if (waitingForEmpty) {
                        // 刚有人进来了，清除已汇报标记
                        // 下次没人时会重新汇报
                    }
                    waitingForEmpty = false;
                    hasReported = false;
                    cancelWaitTask();
                } else if (!waitingForEmpty && !hasReported) {
                    // 没人了，且还没汇报过 → 开始1分钟倒计时
                    waitingForEmpty = true;
                    startWaitCountdown();
                }
            }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    // ★ 1分钟等待
    private void startWaitCountdown() {
        cancelWaitTask();
        waitTask = new BukkitRunnable() {
            @Override
            public void run() {
                int online = Bukkit.getOnlinePlayers().size();

                if (online > 0) {
                    // 等待期间有人进来 → 取消，等下次没人
                    waitingForEmpty = false;
                    hasReported = false;
                    waitTask = null;
                    cancel();
                    return;
                }

                // 1分钟没人 → 汇报一次
                if (!hasReported) {
                    hasReported = true;
                    report();
                }
                waitingForEmpty = false;
                waitTask = null;
                cancel();
            }
        };
        waitTask.runTaskLater(plugin, 1200L); // 60秒
    }

    private void cancelWaitTask() {
        if (waitTask != null) {
            waitTask.cancel();
            waitTask = null;
        }
    }

    // ===== 记录 =====

    public void recordSale(String itemName, int price) {
        sessionSales.merge(itemName, 1, Integer::sum);
        sessionRevenue += price;
        sessionOrders++;
        sessionItems++;
    }

    public void recordRefund(int amount) {
        sessionRefunds++;
        sessionRefundAmount += amount;
    }

    // ===== 汇报 =====

    public void report() {
        if (sessionItems == 0 && sessionRefunds == 0) {
            plugin.getLogger().info("[Sales] 无数据，跳过");
            return;
        }

        long minutes =
                (System.currentTimeMillis() - sessionStart)
                        / 60000;
        if (minutes < 1) minutes = 1;

        StringBuilder sb = new StringBuilder();
        sb.append("§6§l========== 销量汇总 ==========");
        sb.append("\n§7时长: §f").append(minutes).append("分钟");
        sb.append("\n§7订单: §f").append(sessionOrders).append("笔");
        sb.append("\n§7商品: §f").append(sessionItems).append("件");
        sb.append("\n§7成交额: §6").append(sessionRevenue).append("枚");
        sb.append("\n§7退款: §c").append(sessionRefunds)
                .append("次(§c").append(sessionRefundAmount)
                .append("枚§7)");
        sb.append("\n§6§l------------------------------");

        if (!sessionSales.isEmpty()) {
            sb.append("\n§7销量排行:");
            List<Map.Entry<String, Integer>> sorted =
                    new ArrayList<>(sessionSales.entrySet());
            sorted.sort((a, b) ->
                    b.getValue() - a.getValue());
            int rank = 1;
            for (Map.Entry<String, Integer> e : sorted) {
                if (rank > 10) break;
                sb.append("\n§e").append(rank).append(". ")
                        .append(e.getKey())
                        .append(" §7x").append(e.getValue());
                rank++;
            }
        }
        sb.append("\n§6§l==============================");

        String msg = sb.toString();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                for (String line : msg.split("\n"))
                    p.sendMessage(line);
            }
        }
        plugin.getLogger().info("[Sales] 汇报完成");
        resetSession();
    }

    public void resetSession() {
        sessionSales.clear();
        sessionRevenue = 0;
        sessionOrders = 0;
        sessionItems = 0;
        sessionRefunds = 0;
        sessionRefundAmount = 0;
        sessionStart = System.currentTimeMillis();
    }
}
