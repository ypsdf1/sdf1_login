package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * 玩家正版/离线身份验证管理器
 *
 * 核心逻辑：在离线模式(online-mode=false)服务器中，所有玩家UUID都是离线格式，
 * 无法通过UUID区分正版/盗版。因此：
 * - UUID非离线格式 → 服务器在线模式，正版玩家 → autoLogin
 * - UUID离线格式 → 一律视为非正版，需手动登录或OAuth验证
 * - 正版玩家通过 /mslogin OAuth验证后，由Main.addVerifiedPremiumPlayer()标记，
 *   在onJoin检查点0直接autoLogin，不经过此验证器
 */
public class VerificationManager {

    private final Main plugin;

    public VerificationManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(Player p) {
        String uuid = p.getUniqueId().toString();
        return uuid.contains("f851ba4")
                || uuid.startsWith("00000000-0000-0000");
    }

    public boolean isOnlineMode() {
        return Bukkit.getServer().getOnlineMode();
    }

    /**
     * 异步验证玩家身份
     * 注意：此方法仅用于判断UUID格式，不执行自动登录
     * 自动登录由onJoin中的检查点0/1/2控制
     */
    public void verifyPremiumAsync(Player player,
                                   Consumer<Boolean> callback) {
        Thread t = new Thread(() -> doVerify(player, callback),
                "Sdf1_login-PremiumVerify");
        t.setDaemon(true);
        t.start();
    }

    private void doVerify(Player player,
                          Consumer<Boolean> callback) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();

        plugin.getLogger().info(
                "[Sdf1_login] ====== 验证开始 ======");
        plugin.getLogger().info(
                "[Sdf1_login] 玩家: " + name);
        plugin.getLogger().info(
                "[Sdf1_login] 当前UUID: " + uuid);

        try {
            java.util.UUID offlineUuid = java.util.UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            boolean isOfflineUuid =
                    player.getUniqueId().equals(offlineUuid);

            plugin.getLogger().info(
                    "[Sdf1_login] 离线UUID: " + offlineUuid);
            plugin.getLogger().info(
                    "[Sdf1_login] UUID匹配离线格式: "
                            + isOfflineUuid);

            if (!isOfflineUuid) {
                // UUID不是离线格式 → 服务器在线模式的正版玩家
                plugin.getLogger().info(
                        "[Sdf1_login] 结论: 正版玩家（非离线UUID）");
                plugin.getLogger().info(
                        "[Sdf1_login] ====== 验证结束 ======");
                callback.accept(true);
                return;
            }

            // ★ 离线UUID → 无法通过UUID区分正版/盗版，一律视为非正版
            //   正版玩家需通过 /mslogin OAuth验证 或 /login 手动登录
            plugin.getLogger().info(
                    "[Sdf1_login] 结论: 离线UUID玩家，需手动登录");
            plugin.getLogger().info(
                    "[Sdf1_login] （正版玩家请使用 /mslogin 或 /正版 命令验证）");
            plugin.getLogger().info(
                    "[Sdf1_login] ====== 验证结束 ======");
            callback.accept(false);

        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Sdf1_login] 验证异常: "
                            + e.getMessage());
            callback.accept(false);
        }
    }
}
