package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

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
            UUID offlineUuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name)
                            .getBytes(StandardCharsets.UTF_8));
            String offlineStr = offlineUuid.toString();

            plugin.getLogger().info(
                    "[Sdf1_login] 离线UUID: " + offlineStr);

            boolean isOfflineUuid =
                    player.getUniqueId().equals(offlineUuid);

            plugin.getLogger().info(
                    "[Sdf1_login] UUID匹配离线格式: "
                            + isOfflineUuid);

            if (!isOfflineUuid) {
                plugin.getLogger().info(
                        "[Sdf1_login] 结论: 正版玩家");
                plugin.getLogger().info(
                        "[Sdf1_login] ====== 验证结束 ======");
                callback.accept(true);
                return;
            }

            plugin.getLogger().info(
                    "[Sdf1_login] UUID为离线格式，"
                            + "查询Mojang API...");
            UUID mojangUuid = fetchMojangUuid(name);

            if (mojangUuid != null) {
                plugin.getLogger().info(
                        "[Sdf1_login] Mojang返回UUID: "
                                + mojangUuid);
                plugin.getLogger().info(
                        "[Sdf1_login] 结论: 盗版客户端"
                                + "使用了正版名字");
            } else {
                plugin.getLogger().info(
                        "[Sdf1_login] Mojang无此名字");
                plugin.getLogger().info(
                        "[Sdf1_login] 结论: 纯离线玩家");
            }

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

    private UUID fetchMojangUuid(String name) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(
                    "https://api.mojang.com/users/profiles/minecraft/"
                            + name);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            plugin.getLogger().info(
                    "[Sdf1_login] Mojang API响应: " + code);

            if (code == 200) {
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        conn.getInputStream(),
                                        StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String json = sb.toString();
                String uuidStr = json.replaceAll(
                        ".*\"id\"\\s*:\\s*\"([^\"]+)\".*",
                        "$1");
                plugin.getLogger().info(
                        "[Sdf1_login] Mojang原始UUID: "
                                + uuidStr);

                String formatted = uuidStr.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5");
                return UUID.fromString(formatted);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Sdf1_login] Mojang查询异常: "
                            + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
