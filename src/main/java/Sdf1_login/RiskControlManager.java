package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控管理器：检测异常登录行为，记录尝试次数，触发安全报警
 */
public class RiskControlManager {

    private final Main plugin;

    // 每个玩家名 -> 连续失败次数
    private final Map<String, Integer> failAttempts =
            new ConcurrentHashMap<>();

    // 每个IP -> 关联的可疑玩家列表
    private final Map<String, Set<String>> ipSuspicious =
            new ConcurrentHashMap<>();

    // 每个玩家 -> 上次验证时间（用于冷却）
    private final Map<String, Long> verifyCooldown =
            new ConcurrentHashMap<>();

    // 配置项
    private static final int MAX_FAIL_ATTEMPTS = 5;
    private static final int MAX_ACCOUNTS_PER_IP = 5;
    private static final long COOLDOWN_MS = 60000L;

    public RiskControlManager(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * 记录一次失败的登录尝试
     *
     * @param playerName 玩家名
     * @return 剩余尝试次数
     */
    public int recordFailAttempt(String playerName) {
        int current = failAttempts
                .getOrDefault(playerName, 0) + 1;
        failAttempts.put(playerName, current);
        return Math.max(0, MAX_FAIL_ATTEMPTS - current);
    }

    /**
     * 重置指定玩家的失败计数（登录成功时调用）
     */
    public void resetFailAttempts(String playerName) {
        failAttempts.remove(playerName);
    }

    /**
     * 获取指定玩家的失败次数
     */
    public int getFailAttempts(String playerName) {
        return failAttempts
                .getOrDefault(playerName, 0);
    }

    /**
     * 判断玩家是否已被锁定（失败次数达到上限）
     */
    public boolean isLocked(String playerName) {
        return failAttempts
                .getOrDefault(playerName, 0)
                >= MAX_FAIL_ATTEMPTS;
    }

    /**
     * 检查IP是否达到注册上限
     * 直接查数据库，不依赖 DatabaseManager 的方法
     */
    public boolean canRegisterFromIP(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        try {
            java.sql.Connection conn =
                    plugin.getDb().getDb();
            if (conn == null) return true;
            java.sql.PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT COUNT(*) FROM users "
                                    + "WHERE ip_address = ?");
            ps.setString(1, ip);
            java.sql.ResultSet rs = ps.executeQuery();
            int count = 0;
            if (rs.next()) count = rs.getInt(1);
            rs.close();
            ps.close();
            return count < 5;
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }


    /**
     * 记录IP关联的可疑行为
     */
    public void recordSuspiciousIP(String ip,
                                   String playerName) {
        if (ip == null || ip.isEmpty()) return;
        ipSuspicious
                .computeIfAbsent(ip,
                        k -> ConcurrentHashMap.newKeySet())
                .add(playerName);
    }

    /**
     * 获取某IP关联的所有可疑玩家
     */
    public Set<String> getSuspiciousAccounts(String ip) {
        return ipSuspicious
                .getOrDefault(ip, Collections.emptySet());
    }

    /**
     * 检查是否在验证冷却期内
     */
    public boolean isOnCooldown(String playerName) {
        Long last = verifyCooldown.get(playerName);
        if (last == null) return false;
        return System.currentTimeMillis() - last
                < COOLDOWN_MS;
    }

    /**
     * 设置验证冷却时间
     */
    public void setCooldown(String playerName) {
        verifyCooldown.put(playerName,
                System.currentTimeMillis());
    }

    /**
     * 当检测到可疑登录时触发报警
     * - 全服广播
     * - 有邮箱则发邮件
     * - 记录到数据库
     */
    public void triggerAlert(Player player,
                             String suspiciousIP) {
        String name = player.getName();

        // 记录到数据库
        plugin.getDb().recordSecurityAlert(
                name, suspiciousIP);

        // 记录IP可疑行为
        recordSuspiciousIP(suspiciousIP, name);

        // 全服广播
        String serverMsg = "§c§l[安全警告] §e" + name
                + " §f的账号疑似遭到 §c" + suspiciousIP
                + " §f的盗号尝试, 请各位玩家注意账号安全!";
        Bukkit.broadcastMessage(serverMsg);

        // 有邮箱则发邮件
        String emailAddr = (String) plugin.getDb()
                .getField(name, "email");
        if (emailAddr != null
                && !emailAddr.isEmpty()) {
            String subject =
                    "[安全警告] 您的账号疑似遭到盗号尝试";
            String body = "尊敬的玩家 " + name
                    + ":\n\n"
                    + "您的账号在 "
                    + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date())
                    + " 疑似遭到 IP: " + suspiciousIP
                    + " 的盗号尝试。\n\n"
                    + "建议您:\n"
                    + "1. 立即修改密码"
                    + "(使用 /sdf1_login pw)\n"
                    + "2. 如非本人操作,"
                    + "请联系管理员处理\n\n"
                    + "--Sdf1_login 安全系统";
            plugin.getEmail().sendTempPassword(
                    emailAddr, name, body);
        }
    }

    /**
     * 处理登录失败时的风控逻辑
     *
     * @param player       玩家
     * @param suspiciousIP 可疑来源IP
     * @return true=已被锁定（应踢出）, false=仍可继续尝试
     */
    public boolean handleLoginFailure(Player player,
                                      String suspiciousIP) {
        String name = player.getName();
        int remaining = recordFailAttempt(name);

        if (remaining <= 0) {
            // 达到上限，触发报警
            triggerAlert(player, suspiciousIP);
            player.kickPlayer(
                    "§c§l[风控] §f登录失败次数过多,"
                            + " 账号已被临时锁定, 请联系管理员");
            return true;
        }

        // 未达上限，提示剩余次数
        player.sendMessage(
                "§c§l[风控] §f密码错误! "
                        + "剩余" + remaining + "次机会");
        return false;
    }

    /**
     * 登录成功后重置风控状态
     */
    public void onLoginSuccess(String playerName) {
        resetFailAttempts(playerName);
    }

    /**
     * 清理过期的冷却记录（可由定时任务调用）
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        verifyCooldown.entrySet().removeIf(
                e -> now - e.getValue() > COOLDOWN_MS * 10);
    }

    /**
     * 获取所有玩家的失败尝试记录（调试用）
     */
    public Map<String, Integer> getAllFailAttempts() {
        return Collections.unmodifiableMap(failAttempts);
    }

    /**
     * 清除所有风控数据（管理员调试用）
     */
    public void clearAll() {
        failAttempts.clear();
        ipSuspicious.clear();
        verifyCooldown.clear();
    }
}
