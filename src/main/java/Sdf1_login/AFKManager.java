package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class AFKManager {

    // 玩家最后活动时间（空闲才开始计时）
    private final Map<UUID, Long> lastAction =
            new HashMap<>();
    // 每个玩家当前的警告阶段
    private final Map<UUID, Integer> lastStage =
            new HashMap<>();
    // 是否已进入挂机检测（false=还在活动，不计时）
    private final Map<UUID, Boolean> afkTracking =
            new HashMap<>();

    private final ConfigManager config;
    private final JavaPlugin plugin;

    public AFKManager(ConfigManager config,
                      JavaPlugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    public void startCheck() {
        new BukkitRunnable() {
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void tick() {
        if (!config.afkEnabled) return;
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            UUID uuid = p.getUniqueId();

            if (!isLoggedIn(name)) continue;
            if (config.getAfkWhitelist().contains(name))
                continue;

            // 确保有记录
            if (!lastAction.containsKey(uuid)) {
                lastAction.put(uuid, now);
                afkTracking.put(uuid, false);
                lastStage.put(uuid, 0);
                continue;
            }

            long last = lastAction.get(uuid);
            long idle = (now - last) / 1000;
            int timeout = config.afkTimeout;

            // 还在活动范围内：不进入挂机检测
            if (idle < 60) {
                afkTracking.put(uuid, false);
                lastStage.put(uuid, 0);
                continue;
            }

            // 超过60秒无活动：开始挂机检测
            afkTracking.put(uuid, true);
            long remain = timeout - idle;

            // 已超时：踢出
            if (remain <= 0) {
                p.kickPlayer("§c§l你已挂机 "
                        + fmtSec(idle) + "，被踢出");
                lastAction.remove(uuid);
                lastStage.remove(uuid);
                afkTracking.remove(uuid);
                continue;
            }

            int stage = lastStage.getOrDefault(uuid, 0);

            // 阶段3：最后10秒
            if (remain <= 10) {
                if (stage < 3) {
                    lastStage.put(uuid, 3);
                }
                p.sendTitle(
                        "§e§l挂机了 " + fmtSec(idle),
                        "§c还剩 " + remain + " 秒踢出",
                        0, 22, 3);
                playSound(p);
                continue;
            }

            // 阶段2：1分钟~10秒
            if (remain <= 60) {
                if (stage < 2) {
                    lastStage.put(uuid, 2);
                    p.sendMessage(
                            "§e§l⚠ 挂机警告 ⚠ §f你已挂机 §c"
                                    + fmtSec(idle)
                                    + "§f，还剩 §c"
                                    + fmtSec(remain)
                                    + "§f踢出");
                }
                p.sendTitle(
                        "§e§l挂机了 " + fmtSec(idle),
                        "§c还剩 " + fmtSec(remain) + " 踢出",
                        5, 25, 5);
                playSound(p);
                continue;
            }

            // 阶段1：3分钟提醒
            if (remain <= 180 && stage < 1) {
                lastStage.put(uuid, 1);
                p.sendMessage(
                        "§e§l⚠ 挂机警告 ⚠ §f你已挂机 §c"
                                + fmtSec(idle)
                                + "§f，还剩 §c"
                                + fmtSec(remain)
                                + "§f踢出");
                p.sendTitle(
                        "§e§l挂机了 " + fmtSec(idle),
                        "§f还剩 " + fmtSec(remain) + " 踢出",
                        10, 60, 20);
                for (int i = 0; i < 3; i++) {
                    final int delay = i * 10;
                    Bukkit.getScheduler().runTaskLater(
                            plugin, () -> {
                                if (p.isOnline()) playSound(p);
                            }, delay);
                }
            }

            if (remain > 180) {
                lastStage.remove(uuid);
            }
        }
    }

    /**
     * 玩家有活动时调用（移动/交互/放置/挖掘等）
     * 重置计时，退出挂机检测
     */
    public void recordAction(UUID uuid) {
        lastAction.put(uuid, System.currentTimeMillis());
        afkTracking.put(uuid, false);
        lastStage.put(uuid, 0);
    }

    /**
     * 玩家下线时调用
     * 彻底停止计时，清理所有数据
     */
    public void remove(UUID uuid) {
        lastAction.remove(uuid);
        lastStage.remove(uuid);
        afkTracking.remove(uuid);
    }

    /**
     * 获取玩家当前是否在挂机检测中
     */
    public boolean isAfkTracking(UUID uuid) {
        return afkTracking.getOrDefault(uuid, false);
    }

    private boolean isLoggedIn(String name) {
        return ((Main) plugin).getLoggedIn()
                .contains(name);
    }

    private void playSound(Player p) {
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_CHIME,
                1.0f, 0.79f);
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                1.0f, 0.79f);
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 0.79f);
    }

    private String fmtSec(long sec) {
        if (sec < 60) return sec + "秒";
        long m = sec / 60;
        long s = sec % 60;
        return m + "分" + (s > 0 ? s + "秒" : "");
    }
}
