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

    private final Logger log;
    private final Main plugin;
    private final Map<UUID, Integer> retryCount =
            new ConcurrentHashMap<>();
    private static final int MAX_RETRY = 2;

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
                + " status: " + status);

        switch (status) {
            case "ACCEPTED":
                break;

            case "DOWNLOADED":
                break;

            case "SUCCESS":
                log.info("[Radio] " + p.getName()
                        + " 资源包加载成功");
                retryCount.remove(uuid);
                break;

            case "FAILED_DOWNLOAD":
                handleRetry(p, uuid);
                break;

            case "DECLINED":
                log.info("[Radio] " + p.getName()
                        + " 拒绝下载");
                p.sendMessage(
                        "§e[Radio] §f你拒绝了资源包");
                retryCount.remove(uuid);
                break;

            case "DISCARDED":
                log.warning("[Radio] " + p.getName()
                        + " DISCARDED");
                p.sendMessage(
                        "§c[Radio] §f资源包下载失败"
                                + "，可能原因:");
                p.sendMessage(
                        "§71. CDN/防火墙拦截");
                p.sendMessage(
                        "§72. SSL证书问题");
                retryCount.remove(uuid);
                break;

            case "INVALID_URL":
                log.warning("[Radio] " + p.getName()
                        + " URL无效");
                retryCount.remove(uuid);
                break;

            default:
                log.info("[Radio] " + p.getName()
                        + " 未知: " + status);
                break;
        }
    }

    private void handleRetry(Player p, UUID uuid) {
        int count = retryCount
                .getOrDefault(uuid, 0) + 1;
        retryCount.put(uuid, count);

        log.warning("[Radio] " + p.getName()
                + " 下载失败 (" + count
                + "/" + MAX_RETRY + ")");

        if (count <= MAX_RETRY) {
            p.sendMessage(
                    "§e[Radio] §f下载失败，重试中... ("
                            + count + "/" + MAX_RETRY + ")");
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        if (p.isOnline()
                                && plugin.radio != null) {
                            plugin.radio
                                    .sendResourcePack(p);
                        }
                    }, 60L);
        } else {
            p.sendMessage(
                    "§c[Radio] §f资源包加载失败，"
                            + "部分音乐无法播放");
            retryCount.remove(uuid);
        }
    }
}
