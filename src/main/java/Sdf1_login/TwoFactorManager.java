package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家端 2FA（TOTP）绑定管理。
 *
 * 全部逻辑在 Java 本地完成（PHP 只负责展示二维码，不做任何校验）：
 *   /2fa add            申请绑定 -> 生成 pending 密钥 + 网页二维码链接（10 分钟有效）
 *   /2fa code <6位>     用认证器 App 的动态码完成绑定
 *   /2fa remove <6位>   校验动态码 -> 进入 30 秒 confirm 确认窗口
 *   聊天输入 confirm    解绑；超时或输入其它任意内容 = 取消
 *
 * 前置条件：必须先绑定邮箱（/email）。
 */
public class TwoFactorManager {

    /** pending 绑定申请有效期：10 分钟 */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;
    /** 解绑确认窗口：30 秒 */
    private static final long REMOVE_WINDOW_MS = 30 * 1000L;
    /** TOTP 参数：SHA1 / 6位 / 30秒（与 PHP secOtpauthUri 管理端口径一致） */
    private static final String ISSUER = "SDF1";

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    private final Main plugin;
    /** 解绑确认窗口：玩家 UUID -> 截止时间毫秒（async 聊天线程会读，必须并发安全） */
    private final Map<UUID, Long> removeConfirmUntil = new ConcurrentHashMap<>();

    public TwoFactorManager(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 命令入口 ====================

    /** /2fa 命令分发，恒返回 true */
    public boolean handleCommand(Player p, String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "add":
                handleAdd(p);
                break;
            case "code":
                if (args.length < 2) {
                    p.sendMessage("§c用法: /2fa code <6位动态码>");
                    break;
                }
                handleCode(p, args[1]);
                break;
            case "remove":
            case "解绑":
                if (args.length < 2) {
                    p.sendMessage("§c用法: /2fa remove <6位动态码>");
                    break;
                }
                handleRemove(p, args[1]);
                break;
            default:
                sendHelp(p);
                break;
        }
        return true;
    }

    public void sendHelp(Player p) {
        p.sendMessage("§e===== §f二次验证 /2fa §e=====");
        p.sendMessage("§7/2fa add §8- 生成绑定二维码（需先绑定邮箱）");
        p.sendMessage("§7/2fa code <6位> §8- 用动态码完成绑定");
        p.sendMessage("§7/2fa remove <6位> §8- 解绑（30秒内聊天输入 confirm 确认）");
        String state;
        if (isEnabled(p)) {
            state = "§a已绑定";
        } else if (getPendingSecret(p) != null) {
            state = "§e绑定申请中（10分钟内有效）";
        } else {
            state = "§c未绑定";
        }
        p.sendMessage("§7当前状态: " + state);
    }

    // ==================== /2fa add ====================

    private void handleAdd(Player p) {
        // 前置1：必须已绑定邮箱
        String email = str(plugin.getDb().getField(p.getName(), "email"));
        if (email.isEmpty()) {
            p.sendMessage("§c§l[2FA] §c请先绑定邮箱再开通二次验证");
            p.sendMessage("§7输入 §f/email <邮箱> §7完成邮箱绑定");
            return;
        }
        // 前置2：已绑定则拒绝重复申请
        if (isEnabled(p)) {
            p.sendMessage("§e§l[2FA] §e您已绑定二次验证，无需重复操作");
            p.sendMessage("§7如需换绑，先 §f/2fa remove <动态码> §7解绑");
            return;
        }

        // 生成 pending 密钥（20 字节 -> Base32 32 字符）
        String secret = b32Encode(randomBytes(20));
        plugin.getDb().setField(p.getName(), "twofa_pending_secret", secret);
        plugin.getDb().setField(p.getName(), "twofa_pending_at",
                System.currentTimeMillis());

        // 一次性随机 token：玩家凭它读取二维码载荷（10 分钟有效）
        String token = hex(randomBytes(32));
        String uri = otpauthUri(p.getName(), secret);

        // pending 已落库，玩家立即可 /2fa code；二维码链接异步推送后补发
        p.sendMessage("§7[2FA] 正在生成绑定二维码…");
        pushAndAnnounce(p, token, uri, secret);
        // pending 有效期与 PHP 侧载荷一致，10 分钟后需重新 /2fa add
    }

    /**
     * 异步推送给 PHP 展示端（网络耗时不可控，绝不能在主线程等），
     * 结果回主线程补发聊天消息：成功给二维码页 URL，失败降级手动录入。
     */
    private void pushAndAnnounce(Player p, String token, String uri,
                                 String secret) {
        final UUID uuid = p.getUniqueId();
        final String name = p.getName();
        if (plugin.webManager == null) {
            announceFallback(p, name, secret);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String pageUrl = null;
            try {
                pageUrl = plugin.webManager.pushTwoFactorQr(
                        token, name, uri);
            } catch (Exception e) {
                plugin.getLogger().warning("[2FA] 推送二维码异常: "
                        + e.getMessage());
            }
            final String url = pageUrl;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pl = Bukkit.getPlayer(uuid);
                if (pl == null || !pl.isOnline()) return;
                if (url != null) {
                    pl.sendMessage("§a§l[2FA] §a已生成绑定二维码（10 分钟内有效）");
                    pl.sendMessage("§e  浏览器打开: §b" + url);
                    pl.sendMessage("§7  扫码添加到认证器 App 后，回游戏执行:");
                    pl.sendMessage("§e  /2fa code <6位动态码> §7完成绑定");
                } else {
                    plugin.getLogger().info(
                            "[2FA] 二维码推送失败，降级手动录入: " + name);
                    announceFallback(pl, name, secret);
                }
            });
        });
    }

    /** 推送失败降级：打印 Base32 密钥供手动录入 */
    private void announceFallback(Player p, String name, String secret) {
        p.sendMessage("§e§l[2FA] §e网页二维码推送失败，改用手动录入");
        p.sendMessage("§7  认证器 App 选择「手动输入密钥」，账号名填 §f" + name);
        p.sendMessage("§e  密钥: §f" + secret);
        p.sendMessage("§7  录入后执行 §f/2fa code <6位动态码> §7完成绑定");
    }

    // ==================== /2fa code（完成绑定） ====================

    private void handleCode(Player p, String code) {
        code = code.trim();
        if (!code.matches("\\d{6}")) {
            p.sendMessage("§c动态码为 6 位数字");
            return;
        }
        String pending = getPendingSecret(p);
        if (pending == null) {
            p.sendMessage("§c没有待完成的绑定申请，请先执行 /2fa add");
            return;
        }
        if (isPendingExpired(p)) {
            plugin.getDb().setField(p.getName(), "twofa_pending_secret", "");
            plugin.getDb().setField(p.getName(), "twofa_pending_at", 0L);
            p.sendMessage("§c绑定申请已过期（10分钟），请重新执行 /2fa add");
            return;
        }
        if (!verifyCode(pending, code)) {
            p.sendMessage("§c动态码错误，请核对认证器 App 上的 6 位数字");
            return;
        }

        // 绑定成功：pending 转正
        plugin.getDb().setField(p.getName(), "twofa_secret", pending);
        plugin.getDb().setField(p.getName(), "twofa_enabled", 1);
        plugin.getDb().setField(p.getName(), "twofa_pending_secret", "");
        plugin.getDb().setField(p.getName(), "twofa_pending_at", 0L);

        p.sendMessage("§a§l[2FA] §a二次验证绑定成功！");
        p.sendMessage("§7  此后敏感操作需提供认证器动态码");
        p.sendMessage("§c  ★ 换手机/卸载 App 前请先 /2fa remove 解绑，"
                + "否则动态码将永久丢失");
    }

    // ==================== /2fa remove（进入 30 秒 confirm 窗口） ====================

    private void handleRemove(Player p, String code) {
        code = code.trim();
        if (!code.matches("\\d{6}")) {
            p.sendMessage("§c动态码为 6 位数字");
            return;
        }
        if (!isEnabled(p)) {
            p.sendMessage("§c您尚未绑定二次验证");
            return;
        }
        String secret = str(plugin.getDb().getField(p.getName(), "twofa_secret"));
        if (secret.isEmpty() || !verifyCode(secret, code)) {
            p.sendMessage("§c动态码错误，请核对认证器 App 上的 6 位数字");
            return;
        }

        // 进入确认窗口
        final UUID uuid = p.getUniqueId();
        final long deadline = System.currentTimeMillis() + REMOVE_WINDOW_MS;
        removeConfirmUntil.put(uuid, deadline);

        p.sendMessage("§c§l[2FA] §c解绑确认");
        p.sendMessage("§7  请在 §e30秒 §7内聊天输入: §fconfirm");
        p.sendMessage("§c  输入其它任何内容或超时 = 取消解绑（动态码保持有效）");

        // 30 秒超时自动取消
        new BukkitRunnable() {
            @Override
            public void run() {
                Long d = removeConfirmUntil.get(uuid);
                if (d != null && d == deadline) {
                    removeConfirmUntil.remove(uuid);
                    Player pl = Bukkit.getPlayer(uuid);
                    if (pl != null && pl.isOnline()) {
                        pl.sendMessage("§7[2FA] 解绑确认已超时，操作取消");
                    }
                }
            }
        }.runTaskLater(plugin, REMOVE_WINDOW_MS / 50L);
    }

    /** onChat 调用：该玩家是否处于解绑确认窗口 */
    public boolean isRemoveConfirming(Player p) {
        Long d = removeConfirmUntil.get(p.getUniqueId());
        if (d == null) return false;
        if (System.currentTimeMillis() > d) {
            removeConfirmUntil.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    /** onChat 调用：消费确认窗口内的一条聊天输入（调用方需先 setCancelled） */
    public void handleRemoveConfirm(Player p, String msg) {
        Long deadline = removeConfirmUntil.remove(p.getUniqueId());
        if (deadline == null) return;
        if (System.currentTimeMillis() > deadline) {
            p.sendMessage("§7[2FA] 解绑确认已超时，操作取消");
            return;
        }
        if (msg != null && msg.trim().equals("confirm")) {
            // DB 写回主线程（本方法运行在异步聊天线程）
            final String name = p.getName();
            final UUID uuid = p.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDb().setField(name, "twofa_secret", "");
                plugin.getDb().setField(name, "twofa_enabled", 0);
                Player pl = Bukkit.getPlayer(uuid);
                if (pl != null && pl.isOnline()) {
                    pl.sendMessage("§a§l[2FA] §a二次验证已解绑");
                    pl.sendMessage("§7  账号回到仅密码保护状态，建议尽快重新绑定");
                }
            });
        } else {
            p.sendMessage("§7[2FA] 输入不正确，解绑已取消（二次验证保持绑定）");
        }
    }

    // ==================== 状态读取 ====================

    private boolean isEnabled(Player p) {
        Object v = plugin.getDb().getField(p.getName(), "twofa_enabled");
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "1".equals(String.valueOf(v));
    }

    /** 返回未过期的 pending 密钥；无申请或已过期返回 null */
    private String getPendingSecret(Player p) {
        String secret = str(plugin.getDb()
                .getField(p.getName(), "twofa_pending_secret"));
        if (secret.isEmpty()) return null;
        Object t = plugin.getDb().getField(p.getName(), "twofa_pending_at");
        long at = (t instanceof Number) ? ((Number) t).longValue() : 0L;
        if (at <= 0 || System.currentTimeMillis() - at > PENDING_TTL_MS) {
            return null;
        }
        return secret;
    }

    private boolean isPendingExpired(Player p) {
        Object t = plugin.getDb().getField(p.getName(), "twofa_pending_at");
        long at = (t instanceof Number) ? ((Number) t).longValue() : 0L;
        return at <= 0
                || System.currentTimeMillis() - at > PENDING_TTL_MS;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    // ==================== Base32 (RFC4648，无 padding，容错 0/1/8/9) ====================

    static String b32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(B32.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(B32.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return sb.toString();
    }

    static byte[] b32Decode(String s) {
        String t = s.trim().toUpperCase()
                .replace(" ", "").replace("-", "").replace("=", "")
                .replace('0', 'O').replace('1', 'I')
                .replace('8', 'B').replace('9', 'G');
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (int i = 0; i < t.length(); i++) {
            int idx = B32.indexOf(t.charAt(i));
            if (idx < 0) continue;              // 忽略非法字符
            buffer = (buffer << 5) | idx;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    // ==================== TOTP (RFC 6238, HMAC-SHA1, 6位, 30秒) ====================

    static String totp(String base32Secret, long timeMs) {
        try {
            byte[] key = b32Decode(base32Secret);
            long counter = timeMs / 1000L / 30L;
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            return "";
        }
    }

    /** 校验动态码：当前周期 ±1 个周期（容网络/时钟 30~60 秒偏差） */
    static boolean verifyCode(String base32Secret, String code) {
        if (base32Secret == null || base32Secret.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (int i = -1; i <= 1; i++) {
            if (totp(base32Secret, now + i * 30_000L).equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** otpauth://totp/SDF1:<玩家>?secret=..&issuer=SDF1&algorithm=SHA1&digits=6&period=30 */
    static String otpauthUri(String player, String base32Secret) {
        try {
            String label = java.net.URLEncoder
                    .encode(ISSUER + ":" + player, "UTF-8");
            String issuer = java.net.URLEncoder.encode(ISSUER, "UTF-8");
            return "otpauth://totp/" + label
                    + "?secret=" + base32Secret
                    + "&issuer=" + issuer
                    + "&algorithm=SHA1&digits=6&period=30";
        } catch (java.io.UnsupportedEncodingException e) {
            return "otpauth://totp/" + ISSUER + ":" + player
                    + "?secret=" + base32Secret + "&issuer=" + ISSUER
                    + "&algorithm=SHA1&digits=6&period=30";
        }
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
