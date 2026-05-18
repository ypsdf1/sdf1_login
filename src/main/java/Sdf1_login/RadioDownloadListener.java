package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player
        .PlayerResourcePackStatusEvent;

import java.util.UUID;
import java.util.concurrent
        .ConcurrentHashMap;

public class RadioDownloadListener
        implements Listener {

    private final Main plugin;
    private final java.util.logging.Logger log;

    private final ConcurrentHashMap<UUID, Integer>
            failCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean>
            blocked = new ConcurrentHashMap<>();

    private static final int MAX_FAILED = 3;

    public RadioDownloadListener(Main plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void onPlayerQuit(UUID uuid) {
        failCount.remove(uuid);
        blocked.remove(uuid);
    }

    @EventHandler
    public void onStatus(
            PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        String status =
                e.getStatus().name();

        log.info("[Radio] === 资源包状态 ===");
        log.info("[Radio] 玩家: "
                + p.getName());
        log.info("[Radio] UUID: " + uuid);
        log.info("[Radio] 状态: "
                + status);
        log.info("[Radio] 原始状态枚举: "
                + e.getStatus().getClass()
                .getSimpleName()
                + "."
                + e.getStatus().name());

        if (blocked.containsKey(uuid)) {
            log.info("[Radio] 已熔断，跳过: "
                    + p.getName());
            return;
        }

        switch (status) {
            case "SUCCESS":
                log.info("[Radio] "
                        + p.getName()
                        + " ✓ 资源包加载成功");
                failCount.remove(uuid);
                blocked.remove(uuid);
                break;

            case "ACCEPTED":
                log.info("[Radio] "
                        + p.getName()
                        + " 客户端已接受，"
                        + "等待下载...");
                break;

            case "DECLINED":
                log.warning("[Radio] "
                        + p.getName()
                        + " ✗ 拒绝了资源包");
                log.warning("[Radio] "
                        + "可能原因: "
                        + "1.客户端设置禁用资源包"
                        + " 2.已缓存拒绝记录"
                        + " 3.SSL证书不匹配");
                handleFailure(p, uuid);
                break;

            case "FAILED_DOWNLOAD":
                log.warning("[Radio] "
                        + p.getName()
                        + " ✗ 下载失败");
                log.warning("[Radio] "
                        + "可能原因: "
                        + "1.URL无法访问"
                        + " 2.SSL证书错误"
                        + " 3.网络超时"
                        + " 4.文件不存在(404)");
                handleFailure(p, uuid);
                break;

            default:
                log.info("[Radio] "
                        + p.getName()
                        + " 未知状态: "
                        + status);
                break;
        }
    }

    private void handleFailure(
            Player p, UUID uuid) {
        int failNum = failCount
                .getOrDefault(uuid, 0) + 1;
        failCount.put(uuid, failNum);

        log.warning("[Radio] "
                + p.getName()
                + " 失败次数: "
                + failNum + "/" + MAX_FAILED);

        if (failNum >= MAX_FAILED) {
            blocked.put(uuid, true);
            log.severe("[Radio] "
                    + p.getName()
                    + " 已达最大失败次数，"
                    + "触发踢出");
            Bukkit.getScheduler()
                    .runTaskLater(plugin,
                            () -> {
                                Player kp =
                                        Bukkit
                                                .getPlayer(
                                                        uuid);
                                if (kp != null
                                        && kp
                                        .isOnline()) {
                                    kp.kickPlayer(
                                            "§c资源包"
                                                    + "加载失败"
                                                    + "（"
                                                    + MAX_FAILED
                                                    + "次），"
                                                    + "请联系管理员");
                                }
                            }, 1L);
            return;
        }

        p.sendMessage(
                "§e§l[Radio] 下载失败，"
                        + "重试... ("
                        + failNum + "/"
                        + MAX_FAILED + ")");

        // 5秒后重试
        Bukkit.getScheduler()
                .runTaskLater(plugin,
                        () -> {
                            if (p.isOnline()
                                    && plugin
                                    .radio
                                    != null) {
                                plugin.getLogger()
                                        .info(
                                                "[Radio] "
                                                        + "第"
                                                        + failNum
                                                        + "次重试: "
                                                        + p
                                                        .getName());
                                plugin.radio
                                        .sendResourcePack(
                                                p);
                            }
                        }, 100L);
    }
}
