package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家端 2FA（TOTP）绑定管理。
 *
 * 全部逻辑在 Java 本地完成（PHP 只负责展示二维码，不做任何校验）：
 *   /2fa add            申请绑定 -> 生成 pending 密钥 + 网页二维码链接（10 分钟有效）
 *   /2fa code <6位>     ★绑定/验证统一端口：
 *                       有 pending 申请 → 用动态码完成绑定（成功后发 8 组恢复代码到邮箱）
 *                       已绑定        → 作为登录二次验证（IP 变更风控 / 主动验证）
 *                       输入是恢复代码 → 验证通过的同时立即解绑 2FA（一次性有效）
 *   /2fa remove <6位>   校验动态码 -> 进入 30 秒 confirm 确认窗口
 *   聊天输入 confirm    解绑；超时或输入其它任意内容 = 取消
 *   [控制台] /2fa remove <玩家名> --force  管理员强制解绑：30 秒冷静期后才真正销毁
 *   [控制台] /2fa remove -c                取消待执行的强制解绑（冷静期内可撤回）
 *
 * 安全约定：
 *   1) 玩家侧 remove 只接受认证器 App 的 6 位动态码，其它任何输入（含玩家名、--force、-c）
 *      一律回「验证码无效」，绝不提示权限问题（最小化信息透露）。
 *   2) 强制解绑权限级别 = 控制台，玩家侧代码路径完全不解析 --force。
 *   3) 解绑三场景（恢复代码 / 玩家主动 / 管理员强制）都会向「管理员邮箱」推报警邮件。
 *
 * 前置条件：必须先绑定邮箱（/email）。
 */
public class TwoFactorManager {

    /** pending 绑定申请有效期：10 分钟 */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;
    /** 解绑确认窗口：30 秒 */
    private static final long REMOVE_WINDOW_MS = 30 * 1000L;
    /** 管理员强制解绑冷静期：30 秒（到期才真正销毁，期间可 -c 撤回） */
    private static final long FORCE_COOLDOWN_MS = 30 * 1000L;
    /** TOTP 参数：SHA1 / 6位 / 30秒（与 PHP secOtpauthUri 管理端口径一致） */
    private static final String ISSUER = "SDF1";
    /** 绑定成功后随邮件下发的恢复代码组数 / 每组位数（一次性有效） */
    public static final int RECOVERY_CODE_COUNT = 8;
    public static final int RECOVERY_CODE_LEN = 6;

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    private final Main plugin;
    /** 解绑确认窗口：玩家 UUID -> 截止时间毫秒（async 聊天线程会读，必须并发安全） */
    private final Map<UUID, Long> removeConfirmUntil = new ConcurrentHashMap<>();
    /** 登录 IP 变更待验证：玩家 UUID -> 触发风控时的登录 IP */
    private final Map<UUID, String> loginPending = new ConcurrentHashMap<>();
    /** 管理员强制解绑待执行：小写玩家名 -> 冷静期记录（async 不涉及，主线程读写） */
    private final Map<String, ForcePending> forcePending = new ConcurrentHashMap<>();

    /** 冷静期记录：到期时间 + 调度任务ID（-c 时按 ID 撤销调度） */
    private static final class ForcePending {
        final long deadline;
        final int taskId;

        ForcePending(long deadline, int taskId) {
            this.deadline = deadline;
            this.taskId = taskId;
        }
    }

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
                    p.sendMessage("§c用法: /2fa code <6位动态码|恢复代码>");
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
        p.sendMessage("§7/2fa code <6位> §8- 绑定端口：完成绑定，或登录时完成一次二次验证");
        p.sendMessage("§7/2fa code <恢复代码> §8- 认证器丢失时验证，验证后自动解绑");
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
        if (isEnabled(p)) {
            p.sendMessage("§7剩余恢复代码: §f" + countRecoveryCodes(p.getName()) + " §7组（每组一次性）");
        }
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
        // token=裸hex64。WAF实测(2026-10-01矩阵)：仅「查询参数名token+≥32位hex」被404，
        // POST body 与「t=<hex>」均可过 —— 故推送走 POST、页面链接固定 ?t=<token>
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
            p.sendMessage("§c动态码 / 恢复代码均为 6 位数字");
            return;
        }
        String name = p.getName();
        String rawPending = str(plugin.getDb()
                .getField(name, "twofa_pending_secret"));
        String pending = getPendingSecret(p);   // null = 无申请或已过期
        boolean bound = isEnabled(p);

        // ===== 分支1：未绑定 + 有有效申请 → 绑定流程 =====
        if (!bound) {
            if (pending == null) {
                if (!rawPending.isEmpty()) {
                    plugin.getDb().setField(name, "twofa_pending_secret", "");
                    plugin.getDb().setField(name, "twofa_pending_at", 0L);
                    p.sendMessage("§c绑定申请已过期（10分钟），请重新执行 /2fa add");
                } else {
                    p.sendMessage("§c没有待完成的绑定申请，请先执行 /2fa add");
                }
                return;
            }
            if (!verifyCode(pending, code)) {
                p.sendMessage("§c动态码错误，请核对认证器 App 上的 6 位数字");
                return;
            }
            completeBinding(p, pending);
            return;
        }

        // ===== 分支2：已绑定 → 验证流程（/2fa code = 绑定/验证统一端口）=====
        // 残留的过期绑定申请先清掉，避免和验证语义打架
        if (!rawPending.isEmpty()) {
            plugin.getDb().setField(name, "twofa_pending_secret", "");
            plugin.getDb().setField(name, "twofa_pending_at", 0L);
            p.sendMessage("§e[2FA] 已忽略过期的绑定申请；如需换绑请 /2fa remove 后重新 /2fa add");
        }
        verifyForLogin(p, code);
    }

    // ==================== 绑定完成（含恢复代码下发） ====================

    private void completeBinding(Player p, String pending) {
        String name = p.getName();
        // 绑定成功：pending 转正
        plugin.getDb().setField(name, "twofa_secret", pending);
        plugin.getDb().setField(name, "twofa_enabled", 1);
        plugin.getDb().setField(name, "twofa_pending_secret", "");
        plugin.getDb().setField(name, "twofa_pending_at", 0L);

        p.sendMessage("§a§l[2FA] §a二次验证绑定成功！");
        p.sendMessage("§7  此后敏感操作需提供认证器动态码");
        p.sendMessage("§c  ★ 换手机/卸载 App 前请先 /2fa remove 解绑，"
                + "否则动态码将永久丢失");

        issueRecoveryCodes(p);
    }

    /**
     * 绑定成功后生成 8 组 6 位恢复代码：
     * 库里只存 SHA-256（明文只出现在邮件里），任一组用过即删（一次性）。
     * 发信在异步线程做（SMTP 最长阻塞 15 秒，绝不能卡主线程）。
     */
    private void issueRecoveryCodes(Player p) {
        String name = p.getName();
        String email = str(plugin.getDb().getField(name, "email"));
        if (email.isEmpty()) {
            p.sendMessage("§e§l[2FA] §e未绑定邮箱，无法下发恢复代码");
            p.sendMessage("§7  请先 §f/email <邮箱> §7再重新 /2fa remove → /2fa add 换绑");
            return;
        }

        List<String> codes = generateRecoveryCodes(RECOVERY_CODE_COUNT);
        plugin.getDb().setField(name, "twofa_recovery_codes", hashJoin(codes));

        StringBuilder body = new StringBuilder();
        body.append("玩家 ").append(name)
                .append("，您刚刚为「草原探险」开启了二次验证。\n\n");
        body.append("以下是您的 ").append(RECOVERY_CODE_COUNT)
                .append(" 组 ").append(RECOVERY_CODE_LEN)
                .append(" 位恢复代码（每组只能使用一次）：\n\n");
        for (int i = 0; i < codes.size(); i++) {
            body.append("  ").append(i + 1).append(". ")
                    .append(codes.get(i)).append('\n');
        }
        body.append("\n使用方式：游戏内执行 /2fa code <恢复代码>。\n");
        body.append("★ 用任意一组恢复代码完成验证后，二次验证会被立即解绑");
        body.append("（能用恢复代码说明认证器已丢失/不可用），请重新 /2fa add 绑定。\n");
        body.append("★ 请妥善保存本邮件，切勿泄露给他人。\n");

        final String to = email;
        final String subject = "[Sdf1_login] 2FA 恢复代码（" + name + "）";
        final String mailBody = body.toString();
        final UUID uuid = p.getUniqueId();

        p.sendMessage("§a§l[2FA] §a已生成 " + RECOVERY_CODE_COUNT
                + " 组 " + RECOVERY_CODE_LEN + " 位恢复代码");
        p.sendMessage("§7  正在发送到绑定邮箱: §f" + maskEmail(email));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = false;
            try {
                EmailManager em = plugin.getEmail();
                ok = em != null && em.sendBody(to, subject, mailBody);
            } catch (Exception e) {
                plugin.getLogger().warning("[2FA] 恢复代码邮件异常: "
                        + e.getMessage());
            }
            final boolean sent = ok;
            if (!sent) {
                plugin.getLogger().warning("[2FA] 恢复代码邮件发送失败: "
                        + name + " -> " + maskEmail(to));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pl = Bukkit.getPlayer(uuid);
                if (pl == null || !pl.isOnline()) return;
                if (sent) {
                    pl.sendMessage("§a  恢复代码已发送到 §f" + maskEmail(to));
                    pl.sendMessage("§7  请离线查收并妥善保存，每组一次性有效");
                } else {
                    pl.sendMessage("§c  恢复代码邮件发送失败（SMTP 未配置或网络异常）");
                    pl.sendMessage("§c  请联系管理员补发，否则换机后将无法找回二次验证");
                }
            });
        });
    }

    // ==================== 已绑定状态下的验证（登录场景） ====================

    /**
     * /2fa code 在已绑定状态下的校验：
     * 先按认证器动态码验（通过则不干预正常登录），
     * 不匹配再按恢复代码验（一次性 + 立即解绑）。
     */
    private void verifyForLogin(Player p, String code) {
        String name = p.getName();
        String secret = str(plugin.getDb().getField(name, "twofa_secret"));

        if (!secret.isEmpty() && verifyCode(secret, code)) {
            finishLoginVerify(p, false);
            return;
        }

        if (consumeRecoveryCode(name, code)) {
            // 恢复代码有效 = 认证器已丢失/不可用 → 立即解绑 2FA
            plugin.getDb().setField(name, "twofa_secret", "");
            plugin.getDb().setField(name, "twofa_enabled", 0);
            plugin.getDb().setField(name, "twofa_recovery_codes", "");
            plugin.getLogger().info("[2FA] 玩家 " + name
                    + " 使用恢复代码通过验证，已自动解绑二次验证");
            sendUnbindAlarm(name, "恢复代码解绑", "玩家本人（恢复代码）",
                    plugin.getPlayerIP(p));
            p.sendMessage("§e§l[2FA] §e恢复代码有效，本次验证通过");
            p.sendMessage("§c  检测到您使用了恢复代码，已立即解除二次验证绑定");
            p.sendMessage("§7  认证器恢复可用后请重新执行 §f/2fa add §7绑定");
            finishLoginVerify(p, true);
            return;
        }

        p.sendMessage("§c动态码或恢复代码错误，请核对后重试");
    }

    /** 验证通过后的收口：处理"IP 变更待验证"状态，或仅回执（不干预正常登录） */
    private void finishLoginVerify(Player p, boolean usedRecovery) {
        String name = p.getName();
        java.util.UUID uuid = p.getUniqueId();

        // ===== IP风控「密码 + 2FA」顺序两关 =====
        if (dualVerify.containsKey(uuid)) {
            DualState st = dualVerify.get(uuid);
            if (st == null || !st.passwordPassed) {
                // 第二关先到 → 不放行，也不消耗/不信任IP，等第一步密码
                p.sendMessage("§c§l[双重验证] §f请先完成第一步: §f/login <密码>");
                p.sendMessage("§7（密码 + 二次验证两步全部通过后才会解冻并恢复背包）");
                return;
            }
            // ★ 双通过 → 信任本次IP + 解冻 + 恢复背包
            String ip = loginPending.remove(uuid);
            dualVerify.remove(uuid);
            if (ip != null && !ip.isEmpty()) {
                plugin.getDb().setField(name, "last_oauth_ip", ip);
                plugin.getDb().setField(name, "last_login_ip", ip);
            }
            p.sendMessage("§a§l[双重验证] §a第一步密码 ✓  第二步二次验证 ✓");
            p.sendMessage("§a双重验证全部通过，已解冻并恢复背包");
            plugin.getLoginMgr().finishPasswordLogin(p, st.tempPassword);
            return;
        }

        String pendingIp = loginPending.remove(uuid);
        if (pendingIp == null) {
            // 玩家主动验证，不在登录风控流程里 → 只回执，不干预
            p.sendMessage("§a§l[2FA] §a验证通过");
            return;
        }
        // IP 变更场景：把本次 IP 记为可信，然后放行登录
        if (!pendingIp.isEmpty()) {
            plugin.getDb().setField(name, "last_oauth_ip", pendingIp);
            plugin.getDb().setField(name, "last_login_ip", pendingIp);
        }
        p.sendMessage("§a§l[2FA] §a二次验证通过，已信任本次登录 IP");
        if (plugin.getLoggedIn().contains(name)) {
            return;   // 已经登录成功了 → 不做任何多余动作
        }
        if (plugin.isVerifiedPremiumPlayer(name)) {
            plugin.autoLogin(p, "premium");
            return;
        }
        p.sendMessage("§7请继续使用 §f/login <密码> §7完成登录");
    }

    /**
     * 登录 IP 变更风控调用点（Main 检查点0）：
     * 记下"待完成一次二次验证"的状态，提示玩家走 /2fa code。
     */
    public void markLoginPending(Player p, String currentIp) {
        loginPending.put(p.getUniqueId(),
                currentIp == null ? "" : currentIp);
    }

    /** 该玩家是否处于"IP 变更待验证"状态 */
    public boolean isLoginPending(Player p) {
        return loginPending.containsKey(p.getUniqueId());
    }

    /** 玩家下线时清理待验证状态 */
    public void clearLoginPending(Player p) {
        loginPending.remove(p.getUniqueId());
        dualVerify.remove(p.getUniqueId());
    }

    // ==================== IP风控「密码 + 2FA」双验证状态机 ====================

    /** 双验证进度：passwordPassed=false 等第一步(/login)，true 密码已过等第二步(/2fa code) */
    private static final class DualState {
        boolean passwordPassed;
        boolean tempPassword;
    }

    private final java.util.Map<java.util.UUID, DualState> dualVerify =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * IP风控触发时调用（Main 检查点3 / 检查点0）：
     * 该账号必须先 /login 通过密码、再 /2fa code 通过二次验证，两步全过才解冻+恢复背包。
     */
    public void markDualVerify(Player p, String currentIp) {
        dualVerify.put(p.getUniqueId(), new DualState());
        markLoginPending(p, currentIp);
    }

    /** 该玩家是否处于「密码+2FA 双验证」流程 */
    public boolean isDualVerify(Player p) {
        return dualVerify.containsKey(p.getUniqueId());
    }

    /**
     * 第一步通过（密码正确）：只记录进度，<b>不解冻、不恢复背包</b>。
     */
    public void onDualPasswordPassed(Player p, boolean tempPassword) {
        DualState st = dualVerify.get(p.getUniqueId());
        if (st == null) return;
        st.passwordPassed = true;
        st.tempPassword = tempPassword;
    }

    // ==================== 恢复代码 ====================

    static List<String> generateRecoveryCodes(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(RECOVERY_CODE_LEN);
            for (int j = 0; j < RECOVERY_CODE_LEN; j++) {
                sb.append(RNG.nextInt(10));
            }
            out.add(sb.toString());
        }
        return out;
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String hashJoin(List<String> codes) {
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(sha256Hex(c));
        }
        return sb.toString();
    }

    /** 该玩家还剩几组未用的恢复代码 */
    public int countRecoveryCodes(String name) {
        String raw = str(plugin.getDb()
                .getField(name, "twofa_recovery_codes"));
        if (raw.isEmpty()) return 0;
        int n = 0;
        for (String h : raw.split(",")) {
            if (!h.trim().isEmpty()) n++;
        }
        return n;
    }

    /** 消费一个恢复代码（一次性）：命中就从库里删掉并返回 true */
    private boolean consumeRecoveryCode(String name, String code) {
        String raw = str(plugin.getDb()
                .getField(name, "twofa_recovery_codes"));
        if (raw.isEmpty()) return false;
        String want = sha256Hex(code);
        if (want.isEmpty()) return false;

        List<String> kept = new ArrayList<>();
        boolean hit = false;
        for (String h : raw.split(",")) {
            String t = h.trim();
            if (t.isEmpty()) continue;
            if (!hit && t.equalsIgnoreCase(want)) {
                hit = true;
                continue;
            }
            kept.add(t);
        }
        if (!hit) return false;

        StringBuilder sb = new StringBuilder();
        for (String k : kept) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        plugin.getDb().setField(name, "twofa_recovery_codes", sb.toString());
        return true;
    }

    static String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) return email == null ? "" : email;
        String user = email.substring(0, at);
        String dom = email.substring(at);
        if (user.length() <= 2) {
            return user.charAt(0) + "***" + dom;
        }
        return user.substring(0, 2) + "***" + dom;
    }

    // ==================== /2fa remove（进入 30 秒 confirm 窗口） ====================

    private void handleRemove(Player p, String code) {
        code = code.trim();
        // ★ 最小化信息透露：玩家名、--force、-c 等任何非动态码输入
        //   一律按「验证码无效」处理，不提示权限/用法差异
        if (!code.matches("\\d{6}")) {
            p.sendMessage("§c验证码无效，请核对认证器 App 上的 6 位数字");
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
                // 解绑要彻底：恢复代码一并作废，避免残留可再次触发解绑/验证
                plugin.getDb().setField(name, "twofa_recovery_codes", "");
                Player pl = Bukkit.getPlayer(uuid);
                String srcIp = "";
                if (pl != null && pl.isOnline()) {
                    srcIp = plugin.getPlayerIP(pl) == null ? "" : plugin.getPlayerIP(pl);
                    pl.sendMessage("§a§l[2FA] §a二次验证已解绑");
                    pl.sendMessage("§7  账号回到仅密码保护状态，建议尽快重新绑定");
                    // 已解绑 → 「密码+2FA」双验证失去第二关，退回普通密码登录
                    if (isDualVerify(pl)) {
                        clearLoginPending(pl);
                        pl.sendMessage("§7  您的 IP 风控双验证已解除，请用 §f/login <密码> §7完成登录");
                    }
                } else {
                    Object o = plugin.getDb().getField(name, "last_login_ip");
                    srcIp = o == null ? "" : String.valueOf(o);
                }
                sendUnbindAlarm(name, "玩家主动解绑", "玩家本人", srcIp);
            });
        } else {
            p.sendMessage("§7[2FA] 输入不正确，解绑已取消（二次验证保持绑定）");
        }
    }

    // ==================== 控制台强制解绑（权限级别=控制台） ====================

    /**
     * 控制台版 /2fa 分发：只开放管理员强制解绑，执行结果全盘输出。
     * <pre>
     * /2fa remove <玩家名> --force   下发强制解绑（30 秒冷静期，到期才真正销毁）
     * /2fa remove <玩家名> -c        取消指定玩家的待执行任务
     * /2fa remove -c                 取消全部待执行任务
     * </pre>
     */
    public boolean handleConsoleCommand(CommandSender sender, String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        if (!"remove".equals(sub) && !"解绑".equals(sub)) {
            sender.sendMessage("§c[2FA] 控制台仅支持强制解绑子命令");
            sender.sendMessage("§7用法: /2fa remove <玩家名> --force   （30秒冷静期后执行）");
            sender.sendMessage("§7取消: /2fa remove -c");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§c[2FA] 用法: /2fa remove <玩家名> --force");
            sender.sendMessage("§7取消待执行任务: /2fa remove -c");
            return true;
        }
        String a1 = args[1];
        if ("-c".equalsIgnoreCase(a1) || "cancel".equalsIgnoreCase(a1)) {
            cancelForcePending(sender, null);
            return true;
        }
        if (args.length >= 3 && ("-c".equalsIgnoreCase(args[2])
                || "cancel".equalsIgnoreCase(args[2]))) {
            cancelForcePending(sender, a1);
            return true;
        }
        boolean force = false;
        for (int i = 2; i < args.length; i++) {
            if ("--force".equalsIgnoreCase(args[i])) {
                force = true;
                break;
            }
        }
        if (!force) {
            sender.sendMessage("§c[2FA] 缺少 --force，未执行任何操作");
            sender.sendMessage("§7用法: /2fa remove <玩家名> --force");
            return true;
        }
        scheduleForceRemove(sender, a1);
        return true;
    }

    /** 下发强制解绑：校验账号 -> 进入 30 秒冷静期 -> 到期自动执行（期间不销毁任何数据） */
    private void scheduleForceRemove(CommandSender sender, String target) {
        String key = target.toLowerCase();
        if (forcePending.containsKey(key)) {
            sender.sendMessage("§e[2FA] 玩家 " + target + " 已有待执行的强制解绑任务（30秒冷静期内）");
            sender.sendMessage("§7重新下发前先取消: §f/2fa remove " + target + " -c");
            return;
        }
        if (!plugin.getDb().userExists(target)) {
            sender.sendMessage("§c[2FA] 删除异常：数据库中不存在玩家 " + target);
            return;
        }
        if (!isEnabled(target)) {
            sender.sendMessage("§e[2FA] 玩家 " + target + " 未绑定二次验证，无需删除");
            return;
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                executeForceRemove(key, target);
            }
        };
        int taskId = task.runTaskLater(plugin, FORCE_COOLDOWN_MS / 50L).getTaskId();
        forcePending.put(key, new ForcePending(
                System.currentTimeMillis() + FORCE_COOLDOWN_MS, taskId));

        sender.sendMessage("§e§l[2FA] §e已进入 30 秒冷静期：将强制解除玩家 " + target + " 的二次验证");
        sender.sendMessage("§7  到期后自动执行（冷静期内不销毁任何数据）");
        sender.sendMessage("§7  取消: §f/2fa remove " + target + " -c §7或 §f/2fa remove -c");
        sender.sendMessage("§7  到期执行结果会完整输出到本控制台");
        plugin.getLogger().info("[2FA] 控制台下发强制解绑: " + target + "（30秒冷静期）");
    }

    /** 取消待执行的强制解绑（target == null 表示全部） */
    private void cancelForcePending(CommandSender sender, String target) {
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, ForcePending> e
                : new ArrayList<>(forcePending.entrySet())) {
            String key = e.getKey();
            if (target != null && !key.equals(target.toLowerCase())) continue;
            forcePending.remove(key);
            Bukkit.getScheduler().cancelTask(e.getValue().taskId);
            done.add(key);
        }
        if (done.isEmpty()) {
            sender.sendMessage("§e[2FA] 当前没有待执行的强制解绑任务");
            if (target != null) {
                sender.sendMessage("§7  指定玩家: " + target);
            }
            return;
        }
        sender.sendMessage("§a[2FA] 已取消 " + done.size()
                + " 个强制解绑任务（二次验证保持绑定）");
        for (String k : done) {
            sender.sendMessage("§7  - " + k);
        }
        plugin.getLogger().info("[2FA] 强制解绑已取消: " + done);
    }

    /** 冷静期到期：真正销毁 2FA 数据，执行结果全盘输出到控制台 */
    private void executeForceRemove(String key, String target) {
        if (forcePending.remove(key) == null) {
            return;   // 已被 -c 取消
        }
        CommandSender out = Bukkit.getConsoleSender();
        try {
            if (!plugin.getDb().userExists(target)) {
                out.sendMessage("§c[2FA] 删除异常：冷静期内玩家 " + target
                        + " 已不存在（数据库无此账号）");
                plugin.getLogger().warning("[2FA] 强制解绑中止: 玩家 " + target + " 不存在");
                return;
            }
            if (!isEnabled(target)) {
                out.sendMessage("§e[2FA] 玩家 " + target
                        + " 在冷静期内已自行解绑，无需删除");
                plugin.getLogger().info("[2FA] 强制解绑跳过: "
                        + target + " 已自行解绑");
                return;
            }

            plugin.getDb().setField(target, "twofa_secret", "");
            plugin.getDb().setField(target, "twofa_enabled", 0);
            plugin.getDb().setField(target, "twofa_recovery_codes", "");
            plugin.getDb().setField(target, "twofa_pending_secret", "");
            plugin.getDb().setField(target, "twofa_pending_at", 0L);

            out.sendMessage("§a[2FA] 强制解绑成功：玩家 " + target);
            out.sendMessage("§7  已清空 twofa_secret / twofa_enabled / "
                    + "twofa_recovery_codes / twofa_pending_*");
            plugin.getLogger().info("[2FA] 控制台强制解绑成功: " + target);

            String ip = "";
            Player pl = Bukkit.getPlayerExact(target);
            if (pl != null && pl.isOnline()) {
                ip = plugin.getPlayerIP(pl) == null ? "" : plugin.getPlayerIP(pl);
                if (isDualVerify(pl)) {
                    clearLoginPending(pl);
                    pl.sendMessage("§7  您的 IP 风控双验证已解除，"
                            + "请用 §f/login <密码> §7完成登录");
                }
                pl.sendMessage("§e§l[2FA] §e管理员已强制解除您的二次验证");
                pl.sendMessage("§7  账号回到仅密码保护状态，如需重新绑定执行 §f/2fa add");
            } else {
                Object o = plugin.getDb().getField(target, "last_login_ip");
                ip = o == null ? "" : String.valueOf(o);
            }

            if (!sendUnbindAlarm(target, "管理员强制解绑", "控制台（管理员）", ip)) {
                out.sendMessage("§e[2FA] 报警邮件未发送：SMTP设置.txt 未配置"
                        + "「管理员邮箱」（详情见后台日志）");
            } else {
                out.sendMessage("§7[2FA] 解绑报警邮件已投递至 "
                        + maskEmail(safeAdminEmail()) + "（结果见后台日志）");
            }
        } catch (Exception e) {
            out.sendMessage("§c[2FA] 删除异常：" + e);
            plugin.getLogger().warning("[2FA] 强制解绑异常: " + target + " -> " + e);
        }
    }

    // ==================== 解绑报警邮件 ====================

    /** SMTP设置.txt 的「管理员邮箱」（安全事件报警收件人），未配置返回空串 */
    private String safeAdminEmail() {
        try {
            String v = plugin.getConfig2().getSmtp("管理员邮箱");
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 2FA 解绑报警邮件（恢复代码解绑 / 玩家主动解绑 / 管理员强制解绑 三场景统一调用）。
     * 收件人 = SMTP设置.txt 的「管理员邮箱」；未配置则只记日志。
     * 发信在异步线程做（SMTP 阻塞最长 15 秒，绝不能卡主线程）。
     *
     * @return true = 已投递发送（成败见后台日志）；false = 未配置管理员邮箱
     */
    private boolean sendUnbindAlarm(String playerName, String mode,
                                    String operator, String ip) {
        String admin = safeAdminEmail();
        if (admin.isEmpty()) {
            plugin.getLogger().warning("[2FA] 解绑报警邮件未发送："
                    + "SMTP设置.txt 未配置「管理员邮箱」（玩家=" + playerName
                    + " 方式=" + mode + "）");
            return false;
        }
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
        String bound = str(plugin.getDb().getField(playerName, "email"));

        StringBuilder b = new StringBuilder();
        b.append("【安全报警】玩家 ").append(playerName)
                .append(" 的二次验证(2FA)已解除\n\n");
        b.append("触发方式：").append(mode).append('\n');
        b.append("执行者：").append(operator).append('\n');
        b.append("时间：").append(time).append('\n');
        b.append("来源IP：").append(ip == null || ip.isEmpty() ? "未知" : ip).append('\n');
        b.append("玩家绑定邮箱：").append(bound.isEmpty() ? "无" : maskEmail(bound)).append('\n');
        b.append("服务器：草原探险\n\n");
        b.append("若该操作非本人发起，账号当前仅剩密码保护，");
        b.append("请立即让玩家修改密码并重新绑定二次验证。");

        final String to = admin;
        final String subject = "[Sdf1_login] 2FA解绑报警 - " + playerName + "（" + mode + "）";
        final String mailBody = b.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = false;
            try {
                EmailManager em = plugin.getEmail();
                ok = em != null && em.sendBody(to, subject, mailBody);
            } catch (Exception e) {
                plugin.getLogger().warning("[2FA] 解绑报警邮件异常: " + e.getMessage());
            }
            plugin.getLogger().info("[2FA] 解绑报警邮件" + (ok ? "已发送至 " + maskEmail(to)
                    : "发送失败") + "（玩家=" + playerName + " 方式=" + mode + "）");
        });
        return true;
    }

    // ==================== 状态读取 ====================

    private boolean isEnabled(Player p) {
        return isEnabled(p.getName());
    }

    /** 按玩家名判断是否已绑定 2FA（Main 的登录 IP 变更风控会用） */
    public boolean isEnabled(String playerName) {
        Object v = plugin.getDb().getField(playerName, "twofa_enabled");
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
