package Sdf1_login;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控管理器：密码错误阈值封禁 + 报警邮件
 *
 * 功能1：密码错误达到阈值 → 自动封禁账号N分钟 + 推送报警邮件
 * 功能2：封禁期间拒绝所有登录尝试
 */
public class RiskControlManager {

    private final Main plugin;

    // 每个玩家名 -> 连续失败次数
    private final Map<String, Integer> failAttempts =
            new ConcurrentHashMap<>();

    // 每个玩家名 -> 封禁到期时间戳（毫秒）
    private final Map<String, Long> banUntil =
            new ConcurrentHashMap<>();

    public RiskControlManager(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * 获取当前密码错误阈值（从配置读取）
     */
    private int getMaxFailAttempts() {
        return plugin.getConfig2().passwordFailThreshold;
    }

    /**
     * 获取封禁时长（毫秒）（从配置读取）
     */
    private long getBanDurationMs() {
        return (long) plugin.getConfig2().passwordBanDuration * 1000L;
    }

    // ==================== 封禁检查 ====================

    /**
     * 检查玩家是否已被封禁
     * @return true=已被封禁（应拒绝登录）
     */
    public boolean isBanned(String playerName) {
        Long until = banUntil.get(playerName);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) {
            return true; // 仍在封禁期内
        }
        // 封禁已过期，自动解除
        banUntil.remove(playerName);
        failAttempts.remove(playerName);
        return false;
    }

    /**
     * 获取玩家剩余封禁时间（秒），0=未封禁
     */
    public int getBanRemainingSeconds(String playerName) {
        Long until = banUntil.get(playerName);
        if (until == null) return 0;
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            banUntil.remove(playerName);
            failAttempts.remove(playerName);
            return 0;
        }
        return (int) (remaining / 1000) + 1; // 向上取整
    }

    // ==================== 失败记录 ====================

    /**
     * 记录一次失败的登录尝试，判断是否触发封禁
     *
     * @param player 登录失败的玩家
     * @return 剩余尝试次数（-1=已被封禁）
     */
    public int recordFailAttempt(Player player) {
        String name = player.getName();
        int maxAttempts = getMaxFailAttempts();

        // 已被封禁，直接返回
        if (isBanned(name)) {
            return -1;
        }

        int current = failAttempts
                .getOrDefault(name, 0) + 1;
        failAttempts.put(name, current);

        int remaining = Math.max(0, maxAttempts - current);

        // 达到阈值 → 触发封禁
        if (current >= maxAttempts) {
            triggerBan(player);
        }

        return remaining;
    }

    /**
     * 触发封禁：Bukkit真实封禁 + 记录时间 + 发邮件 + 踢出
     * 双层封禁：Bukkit ban阻止连接加入，内存计时器到期自动解封
     */
    private void triggerBan(Player player) {
        String name = player.getName();
        long durationMs = getBanDurationMs();
        long until = System.currentTimeMillis() + durationMs;
        banUntil.put(name, until);

        String ip = plugin.getPlayerIP(player);
        int banMinutes = (int) (durationMs / 60000);

        plugin.getLogger().warning(
                "[风控] 玩家 " + name
                        + " 密码错误达到阈值("
                        + getMaxFailAttempts() + "次)"
                        + "，封禁 " + banMinutes + " 分钟");

        // ★ 真实封禁：调用Bukkit BanList API阻止玩家重新加入服务器
        String banReason = "§c§l[安全风控] 密码连续错误"
                + getMaxFailAttempts() + "次，"
                + "账号临时封禁" + banMinutes + "分钟";
        try {
            // 使用 BanList API 设置带过期时间的封禁
            Date expiry = new Date(System.currentTimeMillis() + durationMs);
            Bukkit.getBanList(BanList.Type.NAME).addBan(
                    name, banReason, expiry, "[Sdf1_login] 风控");
            plugin.getLogger().info(
                    "[风控] 已通过Bukkit BanList封禁玩家 "
                            + name + " (" + banMinutes + "分钟，到期: " + expiry + ")");
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[风控] BanList封禁失败: " + e.getMessage());
        }
        // 踢出玩家（无论BanList是否成功，立即踢出）
        player.kickPlayer(banReason);

        // 异步发送报警邮件
        sendBanAlertEmail(name, ip, banMinutes);
    }

    /**
     * 发送封禁报警邮件到玩家绑定的邮箱
     * 未绑定邮箱则跳过
     */
    private void sendBanAlertEmail(String playerName,
                                    String ip, int banMinutes) {
        String emailAddr = (String) plugin.getDb()
                .getField(playerName, "email");
        if (emailAddr == null || emailAddr.isEmpty()) {
            plugin.getLogger().info(
                    "[风控] 玩家 " + playerName
                            + " 未绑定邮箱，跳过报警邮件");
            return;
        }

        String subject =
                "[安全警告] 您的账号已被临时封禁";
        String body = "尊敬的玩家 " + playerName + ":\n\n"
                + "您的账号在 "
                + new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date())
                + " 因密码连续错误 "
                + getMaxFailAttempts() + " 次"
                + "，已被自动封禁 " + banMinutes + " 分钟。\n\n"
                + "来源IP: " + (ip != null ? ip : "未知") + "\n"
                + "封禁到期: "
                + new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(
                        System.currentTimeMillis()
                                + (long) banMinutes * 60000L))
                + "\n\n"
                + "如果这是您本人操作，请等待封禁到期后重试。\n"
                + "如果非本人操作，建议您立即修改密码"
                + "(使用 /sdf1_login pw)。\n\n"
                + "--Sdf1_login 安全系统";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getEmail().sendBody(
                        emailAddr, subject, body);
                plugin.getLogger().info(
                        "[风控] 封禁报警邮件已发送到 "
                                + emailAddr);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[风控] 报警邮件发送失败: "
                                + e.getMessage());
            }
        });
    }

    // ==================== 成功重置 ====================

    /**
     * 登录成功后重置风控状态
     */
    public void onLoginSuccess(String playerName) {
        failAttempts.remove(playerName);
        banUntil.remove(playerName);
    }

    // ==================== 清理 ====================

    /**
     * 清理过期的封禁记录（可由定时任务调用）
     * 同步解除过期玩家的Bukkit封禁
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        // 收集过期的玩家名
        List<String> expired = new ArrayList<>();
        banUntil.entrySet().removeIf(e -> {
            if (e.getValue() < now) {
                expired.add(e.getKey());
                return true;
            }
            return false;
        });
        // 同步解除Bukkit封禁
        for (String name : expired) {
            try {
                Bukkit.getBanList(BanList.Type.NAME)
                        .pardon(name);
                plugin.getLogger().info(
                        "[风控] 封禁到期，已解除Bukkit封禁: "
                                + name);
            } catch (Exception ignored) {}
            failAttempts.remove(name);
        }
    }

    /**
     * 获取所有封禁记录（调试/管理用）
     */
    public Map<String, Long> getAllBans() {
        return Collections.unmodifiableMap(banUntil);
    }

    /**
     * 获取所有失败尝试记录（调试用）
     */
    public Map<String, Integer> getAllFailAttempts() {
        return Collections.unmodifiableMap(failAttempts);
    }

    /**
     * 手动解除某玩家的封禁（管理员用）
     */
    public void unban(String playerName) {
        banUntil.remove(playerName);
        failAttempts.remove(playerName);
        // 同步解除Bukkit封禁
        try {
            Bukkit.getBanList(BanList.Type.NAME)
                    .pardon(playerName);
            plugin.getLogger().info("[风控] 已解除Bukkit封禁: "
                    + playerName);
        } catch (Exception ignored) {}
    }

    /**
     * 清除所有风控数据（管理员调试用）
     */
    public void clearAll() {
        failAttempts.clear();
        banUntil.clear();
    }
}
