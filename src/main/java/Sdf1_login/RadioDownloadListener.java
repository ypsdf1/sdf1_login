package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class RadioDownloadListener
        implements Listener {

    private final Main plugin;
    private final Logger log;
    private final Map<UUID, Integer> retryCount =
            new ConcurrentHashMap<>();
    private final Map<UUID, Integer> totalAttempts =
            new ConcurrentHashMap<>();
    private static final int MAX_RETRY = 3;
    // 每位玩家最多总共尝试5次，防死循环
    private static final int MAX_TOTAL = 5;

    public RadioDownloadListener(Main plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();

    }

    @EventHandler
    public void onStatus(
            PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        String status = e.getStatus().name();

        log.info("[Radio] " + p.getName()
                + " 资源包: " + status);

        switch (status) {
            case "SUCCESS":
                log.info("[Radio] " + p.getName()
                        + " 资源包加载成功");
                retryCount.remove(uuid);
                totalAttempts.remove(uuid);
                break;

            case "FAILED_DOWNLOAD":
                int retry = retryCount
                        .getOrDefault(uuid, 0) + 1;
                int total = totalAttempts
                        .getOrDefault(uuid, 0) + 1;
                retryCount.put(uuid, retry);
                totalAttempts.put(uuid, total);

                log.warning("[Radio] " + p.getName()
                        + " 下载失败 (重试"
                        + retry + "/" + MAX_RETRY
                        + ", 总计" + total
                        + "/" + MAX_TOTAL + ")");

                if (total >= MAX_TOTAL) {
                    p.sendMessage(
                            "§7[Radio] §f资源包加载失败，跳过");
                    retryCount.remove(uuid);
                    totalAttempts.remove(uuid);
                    break;
                }

                if (retry > MAX_RETRY) {
                    retryCount.put(uuid, 0);
                    Bukkit.getScheduler()
                            .runTaskLater(plugin,
                                    () -> {
                                        if (p.isOnline()
                                                && plugin
                                                .radio
                                                != null) {
                                            plugin.radio
                                                    .sendResourcePack(
                                                            p);
                                        }
                                    }, 100L);
                    break;
                }

                p.sendMessage(
                        "§e[Radio] §f下载失败，重试中...");
                Bukkit.getScheduler()
                        .runTaskLater(plugin,
                                () -> {
                                    if (p.isOnline()
                                            && plugin.radio
                                            != null) {
                                        plugin.radio
                                                .sendResourcePack(
                                                        p);
                                    }
                                }, 60L);
                break;

            case "DECLINED":
                log.info("[Radio] " + p.getName()
                        + " 拒绝下载");
                retryCount.remove(uuid);
                totalAttempts.remove(uuid);
                break;

            default:
                retryCount.remove(uuid);
                totalAttempts.remove(uuid);
                break;
        }
    }
}