package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class AFKManager {

    private final Map<UUID, Long> lastAction =
            new HashMap<>();
    private final Map<UUID, Integer> lastStage =
            new HashMap<>();
    private final ConfigManager config;
    private final JavaPlugin plugin;

    public AFKManager(ConfigManager config,
                      JavaPlugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    public void startCheck() {
        Bukkit.getScheduler().runTaskTimer(plugin,
                () -> {
                    if (!config.afkEnabled) return;
                    long now = System.currentTimeMillis();
                    for (Player p :
                            Bukkit.getOnlinePlayers()) {
                        if (!isLoggedIn(p.getName()))
                            continue;
                        if (config.afkWhitelist
                                .contains(p.getName()))
                            continue;
                        UUID uuid = p.getUniqueId();
                        Long last = lastAction
                                .getOrDefault(uuid, now);
                        long idle = (now - last) / 1000;
                        int timeout = config.afkTimeout;
                        long remain = timeout - idle;

                        if (remain <= 0) {
                            p.kickPlayer(config.msg(
                                    "afk_kicked"));
                            lastAction.remove(uuid);
                            lastStage.remove(uuid);
                            continue;
                        }

                        int stage =
                                lastStage.getOrDefault(uuid, 0);

                        if (remain <= 10) {
                            // 第三阶段：最后10秒
                            if (stage < 3) {
                                lastStage.put(uuid, 3);
                            }
                            // 聊天刷屏
                            p.sendMessage(config.msg(
                                    "afk_warning",
                                    "duration", fmtSec(idle),
                                    "remain",
                                    fmtSec(remain)));
                            // 红色大标题循环
                            p.sendTitle(
                                    "§c§l⚠ 即将踢出 ⚠",
                                    "§c" + fmtSec(remain)
                                            + "后被踢出",
                                    5, 25, 5);
                            // 音效循环
                            p.playSound(p.getLocation(),
                                    Sound.BLOCK_NOTE_BLOCK_PLING,
                                    1.0f, 0.79f);
                            p.playSound(p.getLocation(),
                                    Sound.BLOCK_NOTE_BLOCK_BASS,
                                    1.0f, 0.79f);

                        } else if (remain <= 60) {
                            // 第二阶段：剩余1分钟
                            if (stage < 2) {
                                lastStage.put(uuid, 2);
                                // 仅一次聊天提醒
                                p.sendMessage(config.msg(
                                        "afk_warning",
                                        "duration", fmtSec(idle),
                                        "remain",
                                        fmtSec(remain)));
                            }
                            // 持续大标题循环
                            p.sendTitle(
                                    "§e§l⚠ 挂机警告 ⚠",
                                    "§f" + fmtSec(remain)
                                            + "后被踢出",
                                    10, 40, 10);
                            // 音效循环
                            p.playSound(p.getLocation(),
                                    Sound.BLOCK_NOTE_BLOCK_PLING,
                                    1.0f, 0.79f);
                            p.playSound(p.getLocation(),
                                    Sound.BLOCK_NOTE_BLOCK_BASS,
                                    1.0f, 0.79f);

                        } else if (remain <= 180) {
                            // 第一阶段：剩余3分钟
                            if (stage < 1) {
                                lastStage.put(uuid, 1);
                                // 一次聊天提醒
                                p.sendMessage(config.msg(
                                        "afk_warning",
                                        "duration", fmtSec(idle),
                                        "remain",
                                        fmtSec(remain)));
                                // 一次大标题
                                p.sendTitle(
                                        "§e§l⚠ 挂机警告 ⚠",
                                        "§f" + fmtSec(remain)
                                                + "后被踢出",
                                        10, 60, 20);
                                // 3次音效
                                p.playSound(p.getLocation(),
                                        Sound.BLOCK_NOTE_BLOCK_PLING,
                                        1.0f, 0.79f);
                                p.playSound(p.getLocation(),
                                        Sound.BLOCK_NOTE_BLOCK_BASS,
                                        1.0f, 0.79f);
                                Bukkit.getScheduler()
                                        .runTaskLater(plugin,
                                                () -> {
                                                    if (p.isOnline()) {
                                                        p.playSound(
                                                                p.getLocation(),
                                                                Sound.BLOCK_NOTE_BLOCK_PLING,
                                                                1.0f, 0.79f);
                                                        p.playSound(
                                                                p.getLocation(),
                                                                Sound.BLOCK_NOTE_BLOCK_BASS,
                                                                1.0f, 0.79f);
                                                    }
                                                }, 10L);
                                Bukkit.getScheduler()
                                        .runTaskLater(plugin,
                                                () -> {
                                                    if (p.isOnline()) {
                                                        p.playSound(
                                                                p.getLocation(),
                                                                Sound.BLOCK_NOTE_BLOCK_PLING,
                                                                1.0f, 0.79f);
                                                        p.playSound(
                                                                p.getLocation(),
                                                                Sound.BLOCK_NOTE_BLOCK_BASS,
                                                                1.0f, 0.79f);
                                                    }
                                                }, 20L);
                            }
                        } else {
                            lastStage.remove(uuid);
                        }
                    }
                }, 20L, 20L);
    }

    public void recordAction(UUID uuid) {
        lastAction.put(uuid,
                System.currentTimeMillis());
        lastStage.remove(uuid);
    }

    public void remove(UUID uuid) {
        lastAction.remove(uuid);
        lastStage.remove(uuid);
    }

    private boolean isLoggedIn(String name) {
        return ((Main) plugin)
                .getLoggedIn().contains(name);
    }

    private String fmtSec(long sec) {
        if (sec < 60) return sec + "秒";
        long m = sec / 60;
        long s = sec % 60;
        return m + "分" + (s > 0 ? s + "秒" : "");
    }
}
