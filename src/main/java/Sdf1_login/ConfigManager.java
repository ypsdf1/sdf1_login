package Sdf1_login;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

public class ConfigManager {

    private static final Logger logger = Logger.getLogger("Sdf1_login");
    private final File dataFolder;
    private final Map<String, String> messages
            = new LinkedHashMap<>();
    private final Map<String, String> smtpSettings
            = new LinkedHashMap<>();

    // ===== 公共字段 =====
    public String adminTag = "admin";
    public String adminPassword = "qweasd";
    public int maxAccountsPerIP = 3;
    public String approvalMode = "manual";
    public int autoApproveDelayMinutes = 30;
    public int loginTimeout = 60;
    public boolean afkEnabled = false;
    public int afkTimeout = 300;
    public boolean smtpSsl = true;
    public String smtpVerifyCode = "";
    public long smtpVerifyExpire = 0;
    public long smtpLastSendTime = 0;
    public String smtpConfiguringPlayer = "";
    public String broadcastServerUrl = "";
    public int httpPort = 8080;
    public String garbageTag = "admin";

    public double checkinRewardFixed = 0;
    public double checkinRewardMin = 0;
    public double checkinRewardMax = 0;
    public boolean checkinGivePoints = true;
    public int checkinPoints = 10;
    public String checkinRewardType = "none";
    public int backCheckPointMultiplier = 5;
    public int checkinBondMin = 1;
    public int checkinBondMax = 3;

    public double providerPointsPerScore = 2.0;
    public int baseEconomyReward = 100;
    public double providerEconomyPerScore = 50.0;
    public int requesterPointsReward = 10;

    public boolean garbageEnabled = true;
    public int garbageInterval = 300;
    public int garbageMaxRounds = 1;

    // ===== 区域防护配置 =====
    public String areaProtectAdminMode = "tag"; // "tag" 或 "op"，不共存
    public String areaProtectAdminTag = "admin"; // 管理员Tag名
    public double areaProtectPricePerSqM = 10.0; // 每平米价格(债券)
    public int areaProtectMaxLands = 3; // 每个玩家最大领地数

    // ===== 传送配置 =====
    public int tpRequestValidSeconds = 90; // 请求有效时间（秒）
    public int tpSendIntervalSeconds = 10; // 发送间隔（秒）

    // ===== 商店打包配置（来自 PHP shop_config，定时拉取）=====
    public double packingFee = 5.0;     // 彩色潜影盒打包加收债券数（原色免费，支持小数）
    public double greenDiscount = 10.0;  // 环保单折扣率（折数）：10=不打折，9.9=9.9折(支付99%)，9.8=9.8折(支付98%)，支持小数

    // ★ 构造函数接收 File，和 Main.java 调用一致 ★
    public ConfigManager(File dataFolder) {
        this.dataFolder = dataFolder;
    }
    public String rewardChannel = "债券";  // bonds = 债券，economy = Vault经济


    // ==================== 消息 ====================

    public void loadMessages() {
        messages.clear();
        File file = new File(dataFolder, "消息.txt");
        if (!file.exists()) createDefaultMessages(file);
        loadMap(file, messages);
    }

    public String msg(String key) {
        String val = messages.getOrDefault(key, key);
        val = val.replace("<b>", "&l");
        val = val.replace("</b>", "&r");
        val = val.replace("<br>", "\n");
        val = val.replace("\\n", "\n");
        val = val.replaceAll(
                "&([0-9a-fk-orA-FK-OR])", "§$1");
        return val;
    }

    private void createDefaultMessages(File f) {
        List<String> L = new ArrayList<>();
        L.add("# ===== Sdf1_login 消息配置 =====");
        L.add("login_timeout=§c登录超时");
        L.add("not_registered=§e您尚未注册");
        L.add("not_logged_in=§e您尚未登录");
        L.add("already_registered=§c您已注册过");
        L.add("already_logged_in=§c您已登录");
        L.add("reg_success=§a注册成功！");
        L.add("login_success=§a登录成功！");
        L.add("login_failed=§c密码错误");
        L.add("password_wrong=§c密码错误");
        L.add("password_changed=§a密码修改成功！");
        L.add("password_format_error=§c密码需6位以上含大小写和数字");
        L.add("password_same=§c新密码不能与旧密码相同");
        L.add("checkin_already=§c您今日已签到");
        L.add("checkin_success=§a签到成功！获得 {points} 积分");
        L.add("chat_muted=§c你已被禁言");
        L.add("未知参数=§c未知参数或权限不足");
        writeLines(f, L);
    }

    // ==================== SMTP ====================

    public void loadSmtp() {
        smtpSettings.clear();
        File file = new File(dataFolder, "SMTP设置.txt");
        if (!file.exists()) {
            createDefaultSmtp(file);
        }
        // 加载配置文件
        loadMap(file, smtpSettings);
        // 检查并补全缺失的配置项
        ensureSmtpConfigComplete(file);
        logger.info("[Sdf1_login] 加载邮件配置: 已加载 " + smtpSettings.size() + " 个配置项");
        for (String key : smtpSettings.keySet()) {
            logger.info("[Sdf1_login]   " + key + " = " + smtpSettings.get(key));
        }
    }

    /**
     * 重新加载邮件配置文件（热更新）
     */
    public void reloadSmtp() {
        loadSmtp();
    }

    /**
     * 确保SMTP配置文件包含所有必要的配置项
     * 如果缺少配置项，自动补充默认值并保存
     */
    private void ensureSmtpConfigComplete(File file) {
        boolean changed = false;

        // 检查必要的SMTP配置项
        String[] requiredSmtpKeys = {"smtp地址", "smtp端口", "smtp账号", "smtp密码", "发件人名称", "smtp加密"};
        for (String key : requiredSmtpKeys) {
            if (!smtpSettings.containsKey(key)) {
                logger.info("[Sdf1_login] 邮件配置缺失: " + key + "，自动补全");
                // 根据key提供默认值
                String defaultValue = "";
                switch (key) {
                    case "smtp地址": defaultValue = "smtp.example.com"; break;
                    case "smtp端口": defaultValue = "465"; break;
                    case "发件人名称": defaultValue = "Sdf1_login"; break;
                    case "smtp加密": defaultValue = "true"; break;
                }
                smtpSettings.put(key, defaultValue);
                changed = true;
            }
        }

        // 检查邮箱验证模式配置项
        if (!smtpSettings.containsKey("邮箱验证模式")) {
            logger.info("[Sdf1_login] 邮件配置缺失: 邮箱验证模式，自动补全");
            smtpSettings.put("邮箱验证模式", "默认");
            changed = true;
        }

        // 检查邮箱后缀列表配置项
        if (!smtpSettings.containsKey("邮箱后缀列表")) {
            logger.info("[Sdf1_login] 邮件配置缺失: 邮箱后缀列表，自动补全");
            smtpSettings.put("邮箱后缀列表", "");
            changed = true;
        }

        // 如果有变更，保存配置文件
        if (changed) {
            logger.info("[Sdf1_login] 邮件配置有变更，保存配置文件");
            saveSmtp();
        } else {
            logger.info("[Sdf1_login] 邮件配置完整，无需补全");
        }
    }

    public String getSmtp(String key) {
        return smtpSettings.getOrDefault(key, "");
    }

    public void setSmtp(String key, String value) {
        smtpSettings.put(key, value);
    }

    /**
     * 获取邮箱验证模式
     * @return "默认" / "白名单" / "黑名单"
     */
    public String getEmailValidationMode() {
        return smtpSettings.getOrDefault("邮箱验证模式", "默认");
    }

    /**
     * 获取邮箱后缀列表
     * @return 后缀列表（每行一个，不含@）
     */
    public String getEmailSuffixList() {
        return smtpSettings.getOrDefault("邮箱后缀列表", "");
    }

    /**
     * 检查邮箱后缀是否允许
     * @param email 邮箱地址
     * @return true=允许, false=禁止
     */
    public boolean isEmailSuffixAllowed(String email) {
        String mode = getEmailValidationMode();
        if ("默认".equals(mode)) {
            return true; // 默认模式不校验
        }

        String suffixList = getEmailSuffixList();
        if (suffixList == null || suffixList.trim().isEmpty()) {
            return true; // 列表为空，不校验
        }

        // 提取邮箱后缀
        String suffix = "";
        int atIndex = email.indexOf('@');
        if (atIndex >= 0 && atIndex < email.length() - 1) {
            suffix = email.substring(atIndex + 1).toLowerCase().trim();
        }

        // 解析后缀列表
        String[] suffixes = suffixList.split("[,;\\n]+");
        boolean found = false;
        for (String s : suffixes) {
            String trimmed = s.trim().toLowerCase();
            if (!trimmed.isEmpty() && trimmed.equals(suffix)) {
                found = true;
                break;
            }
        }

        if ("白名单".equals(mode)) {
            return found; // 白名单模式：必须在列表中
        } else if ("黑名单".equals(mode)) {
            return !found; // 黑名单模式：不能在列表中
        }

        return true;
    }

    /**
     * 获取允许的邮箱后缀提示信息
     * @return 如 "允许的后缀: @qq.com, @163.com" 或空字符串
     */
    public String getAllowedSuffixHint() {
        String mode = getEmailValidationMode();
        if ("默认".equals(mode)) return "";
        String suffixList = getEmailSuffixList();
        if (suffixList == null || suffixList.trim().isEmpty()) return "";
        String[] suffixes = suffixList.split("[,;\\n]+");
        StringBuilder sb = new StringBuilder();
        for (String s : suffixes) {
            String t = s.trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("@").append(t);
            }
        }
        if (sb.length() == 0) return "";
        if ("白名单".equals(mode)) {
            return "§7允许的后缀: " + sb.toString();
        } else if ("黑名单".equals(mode)) {
            return "§7以下后缀被禁止: " + sb.toString();
        }
        return "";
    }

    public void saveSmtp() {
        File file = new File(dataFolder, "SMTP设置.txt");
        List<String> L = new ArrayList<>();
        // 记录已写入的key，最后追加未写入的未知key
        java.util.Set<String> writtenKeys = new java.util.LinkedHashSet<>();

        L.add("# ===== 邮件配置 =====");
        L.add("# --- SMTP 信息 ---");
        // 保存SMTP相关配置（按固定顺序）
        String[] smtpKeys = {"smtp地址", "smtp端口", "smtp账号", "smtp密码", "发件人名称", "smtp加密"};
        for (String key : smtpKeys) {
            if (smtpSettings.containsKey(key)) {
                L.add(key + "=" + smtpSettings.get(key));
                writtenKeys.add(key);
            }
        }
        L.add("");
        L.add("# --- 邮箱验证模式 ---");
        L.add("# 模式: 默认 / 白名单 / 黑名单");
        L.add("# 默认: 不校验邮箱后缀");
        L.add("# 白名单: 只允许列表中的后缀");
        L.add("# 黑名单: 禁止列表中的后缀");
        // 保存邮箱验证模式
        if (smtpSettings.containsKey("邮箱验证模式")) {
            L.add("邮箱验证模式=" + smtpSettings.get("邮箱验证模式"));
        } else {
            L.add("邮箱验证模式=默认");
        }
        writtenKeys.add("邮箱验证模式");
        // 保存邮箱后缀列表（支持多行格式：每行一个后缀）
        L.add("# 邮箱后缀列表（每行一个，不含@）");
        String suffixVal = smtpSettings.getOrDefault("邮箱后缀列表", "");
        if (suffixVal != null && !suffixVal.trim().isEmpty()) {
            String[] parts = suffixVal.split("[,\\n]+");
            boolean firstSuffix = true;
            for (String s : parts) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    if (firstSuffix) {
                        L.add("邮箱后缀列表=" + trimmed);
                        firstSuffix = false;
                    } else {
                        L.add(trimmed);
                    }
                }
            }
            if (firstSuffix) {
                L.add("邮箱后缀列表=");
            }
        } else {
            L.add("邮箱后缀列表=");
        }
        writtenKeys.add("邮箱后缀列表");

        // ★ 追加所有未写入的未知key（防止丢失用户自定义配置项）
        java.util.Set<String> allKeys = new java.util.LinkedHashSet<>(smtpSettings.keySet());
        allKeys.removeAll(writtenKeys);
        if (!allKeys.isEmpty()) {
            L.add("");
            L.add("# --- 其他配置 ---");
            for (String key : allKeys) {
                L.add(key + "=" + smtpSettings.get(key));
            }
        }

        writeLines(file, L);
    }

    private void createDefaultSmtp(File f) {
        List<String> L = new ArrayList<>();
        L.add("# ===== 邮件配置 =====");
        L.add("# --- SMTP 信息 ---");
        L.add("smtp地址=smtp.example.com");
        L.add("smtp端口=465");
        L.add("smtp账号=");
        L.add("smtp密码=");
        L.add("发件人名称=Sdf1_login");
        L.add("smtp加密=true");
        L.add("");
        L.add("# --- 邮箱验证模式 ---");
        L.add("# 模式: 默认 / 白名单 / 黑名单");
        L.add("# 默认: 不校验邮箱后缀");
        L.add("# 白名单: 只允许列表中的后缀");
        L.add("# 黑名单: 禁止列表中的后缀");
        L.add("邮箱验证模式=默认");
        L.add("# 邮箱后缀列表（每行一个，不含@）");
        L.add("# 示例: qq.com");
        L.add("# 示例: gmail.com");
        L.add("邮箱后缀列表=");
        writeLines(f, L);
    }

    // ==================== 插件设置 ====================

    public void loadSettings() {
        File file = new File(dataFolder, "插件设置.txt");
        if (!file.exists()) createDefaultSettings(file);
        Map<String, String> m = new LinkedHashMap<>();
        loadMap(file, m);

        // 检查是否需要补充新配置项
        boolean hasNewKey = false;
        String[][] newKeys = {
            {"签到债券最小", String.valueOf(checkinBondMin)},
            {"签到债券最大", String.valueOf(checkinBondMax)},
            {"奖励发放方式", "债券"},
            {"区域防护_管理员模式", areaProtectAdminMode},
            {"区域防护_管理员Tag", areaProtectAdminTag},
            {"区域防护_每平米价格", String.valueOf(areaProtectPricePerSqM)},
            {"区域防护_最大领地数", String.valueOf(areaProtectMaxLands)},
            {"传送_请求有效秒", String.valueOf(tpRequestValidSeconds)},
            {"传送_发送间隔秒", String.valueOf(tpSendIntervalSeconds)},
        };
        for (String[] nk : newKeys) {
            if (!m.containsKey(nk[0])) { hasNewKey = true; }
        }
        if (hasNewKey) {
            try {
                FileWriter fw = new FileWriter(file, true);
                BufferedWriter bw = new BufferedWriter(fw);
                bw.newLine();
                bw.write("# ===== 新增配置项 =====");
                bw.newLine();
                for (String[] nk : newKeys) {
                    if (!m.containsKey(nk[0])) {
                        bw.write(nk[0] + "=" + nk[1]);
                        bw.newLine();
                    }
                }
                bw.flush(); bw.close();
                Bukkit.getLogger().info("[Config] 已补充缺失的配置项");
            } catch (IOException ignored) {}
        }

        // 读取ConfigManager管理的配置项
        adminTag = m.getOrDefault("管理标签", adminTag);
        adminPassword = m.getOrDefault("管理密码", adminPassword);
        maxAccountsPerIP = parseInt(m.getOrDefault("每IP最大账号数", "3"), 3);
        approvalMode = m.getOrDefault("审批模式", approvalMode);
        autoApproveDelayMinutes = parseInt(m.getOrDefault("自动审批延迟分钟", "30"), 30);
        loginTimeout = parseInt(m.getOrDefault("登录超时秒数", "60"), 60);
        afkEnabled = Boolean.parseBoolean(m.getOrDefault("挂机踢出", "false"));
        afkTimeout = parseInt(m.getOrDefault("挂机超时秒数", "300"), 300);
        smtpSsl = Boolean.parseBoolean(m.getOrDefault("smtp加密", "true"));
        httpPort = parseInt(m.getOrDefault("http-port", "8080"), 8080);
        broadcastServerUrl = m.getOrDefault("resource-pack-url", "");

        providerPointsPerScore = parseDouble(m.getOrDefault("服务商积分倍率", "2.0"), 2.0);
        baseEconomyReward = parseInt(m.getOrDefault("基础经济奖励", "100"), 100);
        providerEconomyPerScore = parseDouble(m.getOrDefault("经济奖励每评分", "50.0"), 50.0);
        requesterPointsReward = parseInt(m.getOrDefault("报单人积分奖励", "10"), 10);

        garbageEnabled = Boolean.parseBoolean(m.getOrDefault("垃圾站_启用", "true"));
        garbageInterval = parseInt(m.getOrDefault("垃圾站_清理间隔秒", "300"), 300);
        garbageMaxRounds = parseInt(m.getOrDefault("垃圾站_保留轮数", "1"), 1);

        checkinRewardFixed = parseDouble(m.getOrDefault("签到固定金额", "0"), 0);
        checkinRewardMin = parseDouble(m.getOrDefault("签到最小金额", "0"), 0);
        checkinRewardMax = parseDouble(m.getOrDefault("签到最大金额", "0"), 0);
        checkinGivePoints = Boolean.parseBoolean(m.getOrDefault("签到给积分", "true"));
        checkinPoints = parseInt(m.getOrDefault("签到积分数量", "10"), 10);
        backCheckPointMultiplier = parseInt(m.getOrDefault("补签积分倍率", "5"), 5);
        checkinBondMin = parseInt(m.getOrDefault("签到债券最小", "1"), 1);
        checkinBondMax = parseInt(m.getOrDefault("签到债券最大", "3"), 3);
        if (checkinBondMax < checkinBondMin) checkinBondMax = checkinBondMin;

        if (checkinRewardFixed > 0) checkinRewardType = "fixed";
        else if (checkinRewardMin > 0 && checkinRewardMax > checkinRewardMin) checkinRewardType = "range";
        else checkinRewardType = "none";
        rewardChannel = m.getOrDefault("奖励发放方式", "债券");
        rewardChannel = normalizeRewardChannel(rewardChannel);

        // ===== 区域防护配置 =====
        areaProtectAdminMode = m.getOrDefault("区域防护_管理员模式", "tag").toLowerCase();
        areaProtectAdminTag = m.getOrDefault("区域防护_管理员Tag", adminTag);
        areaProtectPricePerSqM = parseDouble(m.getOrDefault("区域防护_每平米价格", "10"), 10.0);
        areaProtectMaxLands = parseInt(m.getOrDefault("区域防护_最大领地数", "3"), 3);

        tpRequestValidSeconds = parseIntFromString(m.getOrDefault("传送_请求有效秒", "90"));
        // 安全下限：防止配置被误设为极小值（如把"3"当"3分钟"）导致传送请求秒过期。
        // 若发现配置过小，强制下限并告警，便于定位插件设置.txt 中的异常值。
        if (tpRequestValidSeconds < 30) {
            Bukkit.getLogger().warning("[Sdf1配置] 警告: 传送_请求有效秒=" + tpRequestValidSeconds
                    + " 过小，已强制下限为 30 秒（请检查插件设置.txt 是否误设为极小值）");
            tpRequestValidSeconds = 30;
        }
        tpSendIntervalSeconds = parseIntFromString(m.getOrDefault("传送_发送间隔秒", "10"));
        Bukkit.getLogger().info("[Sdf1配置] 传送请求有效时间 = " + tpRequestValidSeconds + " 秒；发送间隔 = "
                + tpSendIntervalSeconds + " 秒");

        // rewardChannel 已标准化为 bonds/economy，默认bonds
    }
    /**
     * 奖励方式标准化：中英文全兼容
     * "债券" "bonds" "Bond" "BONDS" → "bonds"
     * "经济" "economy" "Economy" "ECONOMY" → "economy"
     * 其他任何值 → "bonds"（默认债券）
     */
    private String normalizeRewardChannel(String raw) {
        if (raw == null) return "bonds";
        String s = raw.trim().toLowerCase();
        // 中文
        if (s.contains("债券")) return "bonds";
        if (s.contains("经济")) return "economy";
        // 英文
        if (s.contains("bond")) return "bonds";
        if (s.contains("econ")) return "economy";
        if (s.contains("Vault")) return "economy";
        // 兜底
        return "bonds";
    }

    public void saveSettings() {
        File file = new File(dataFolder, "插件设置.txt");

        // ★ 读取已有配置，保留Web通信等其他模块管理的配置行
        java.util.LinkedHashMap<String, String> existingPairs = new java.util.LinkedHashMap<>();
        java.util.List<String> existingLines = new ArrayList<>();
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    existingLines.add(line);
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        int eq = trimmed.indexOf('=');
                        String k = trimmed.substring(0, eq).trim();
                        existingPairs.put(k, trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (IOException ignored) {}
        }

        // ★ ConfigManager管理的配置项
        java.util.LinkedHashMap<String, String> managed = new java.util.LinkedHashMap<>();
        managed.put("管理标签", adminTag);
        managed.put("管理密码", adminPassword);
        managed.put("每IP最大账号数", String.valueOf(maxAccountsPerIP));
        managed.put("审批模式", approvalMode);
        managed.put("自动审批延迟分钟", String.valueOf(autoApproveDelayMinutes));
        managed.put("登录超时秒数", String.valueOf(loginTimeout));
        managed.put("挂机踢出", String.valueOf(afkEnabled));
        managed.put("挂机超时秒数", String.valueOf(afkTimeout));
        managed.put("resource-pack-url", broadcastServerUrl);
        managed.put("http-port", String.valueOf(httpPort));
        managed.put("垃圾站_启用", String.valueOf(garbageEnabled));
        managed.put("垃圾站_清理间隔秒", String.valueOf(garbageInterval));
        managed.put("垃圾站_保留轮数", String.valueOf(garbageMaxRounds));
        managed.put("签到固定金额", String.valueOf(checkinRewardFixed));
        managed.put("签到最小金额", String.valueOf(checkinRewardMin));
        managed.put("签到最大金额", String.valueOf(checkinRewardMax));
        managed.put("签到给积分", String.valueOf(checkinGivePoints));
        managed.put("签到积分数量", String.valueOf(checkinPoints));
        managed.put("签到债券最小", String.valueOf(checkinBondMin));
        managed.put("签到债券最大", String.valueOf(checkinBondMax));
        managed.put("补签积分倍率", String.valueOf(backCheckPointMultiplier));
        managed.put("奖励发放方式", "债券");
        managed.put("传送_请求有效秒", String.valueOf(tpRequestValidSeconds));
        managed.put("传送_发送间隔秒", String.valueOf(tpSendIntervalSeconds));

        // ★ 保留非ConfigManager管理的配置行（如web通信-*等）
        // ★ 同时保留连续的注释块（如 "# ===== Web通信配置 ====="）
        java.util.List<String> preservedLines = new ArrayList<>();
        for (String line : existingLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 如果是注释行，检查后面是否有非管理配置，保留这种注释块
            if (trimmed.startsWith("#")) {
                // 检查后续是否有非管理配置
                int idx = existingLines.indexOf(line);
                boolean hasNonManaged = false;
                for (int j = idx + 1; j < existingLines.size(); j++) {
                    String next = existingLines.get(j).trim();
                    if (next.isEmpty() || next.startsWith("#")) continue;
                    if (next.contains("=")) {
                        int eq = next.indexOf('=');
                        String k = next.substring(0, eq).trim();
                        if (!managed.containsKey(k)) { hasNonManaged = true; break; }
                    }
                }
                if (hasNonManaged) preservedLines.add(line);
                continue;
            }
            if (!trimmed.contains("=")) continue;
            int eq = trimmed.indexOf('=');
            String key = trimmed.substring(0, eq).trim();
            if (!managed.containsKey(key)) {
                preservedLines.add(key + "=" + trimmed.substring(eq + 1).trim());
            }
        }

        // ★ 写入完整配置：管理的配置在前，其他模块配置在后（带注释分隔）
        List<String> L = new ArrayList<>();
        L.add("# ===== Sdf1_login 插件设置 =====");
        for (java.util.Map.Entry<String, String> e : managed.entrySet()) {
            L.add(e.getKey() + "=" + e.getValue());
        }
        // 追加保留的其他模块配置
        if (!preservedLines.isEmpty()) {
            L.add("");
            // 从 preservedLines 中找出开头的注释块并添加
            int firstDataIdx = 0;
            for (int i = 0; i < preservedLines.size(); i++) {
                if (preservedLines.get(i).startsWith("#")) {
                    L.add(preservedLines.get(i));
                    firstDataIdx = i + 1;
                } else {
                    break;
                }
            }
            for (int i = firstDataIdx; i < preservedLines.size(); i++) {
                L.add(preservedLines.get(i));
            }
        }
        writeLines(file, L);
    }

    private void createDefaultSettings(File f) {
        List<String> L = new ArrayList<>();
        L.add("# ===== Sdf1_login 插件设置 =====");
        L.add("管理标签=admin");
        L.add("管理密码=qweasd");
        L.add("每IP最大账号数=3");
        L.add("审批模式=manual");
        L.add("自动审批延迟分钟=30");
        L.add("登录超时秒数=60");
        L.add("挂机踢出=false");
        L.add("挂机超时秒数=300");
        L.add("resource-pack-url=");
        L.add("http-port=8080");
        L.add("垃圾站_启用=true");
        L.add("垃圾站_清理间隔秒=300");
        L.add("垃圾站_保留轮数=1");
        L.add("签到固定金额=0");
        L.add("签到最小金额=50");
        L.add("签到最大金额=500");
        L.add("签到给积分=true");
        L.add("签到积分数量=10");
        L.add("签到债券最小=1");
        L.add("签到债券最大=3");
        L.add("补签积分倍率=5");
        L.add("奖励发放方式=债券");
        // 区域防护配置
        L.add("区域防护_管理员模式=tag");
        L.add("区域防护_管理员Tag=admin");
        L.add("区域防护_每平米价格=10");
        L.add("区域防护_最大领地数=3");
        L.add("传送_请求有效秒=90");
        L.add("传送_发送间隔秒=10");
        // Web通信配置（默认禁用，需手动开启）
        L.add("");
        L.add("# ===== Web通信配置 =====");
        L.add("web通信-启用=false");
        L.add("web通信-地址=https://caoyuan.ypshidifu.cn/plugin");
        L.add("web通信-Token有效期秒=600");
        L.add("web通信-同步间隔分钟=5");
        L.add("web通信-回调端口=9090");
        L.add("web通信-密钥=sdf1_web_comm_2026_ypshidifu");
        L.add("");
        L.add("# ===== 其他模块配置 =====");
        // 始终附加空行和注释分隔符，让后续追加逻辑更容易识别边界
        writeLines(f, L);
    }
    public List<String> getAfkWhitelist() {
        List<String> list = new ArrayList<>();
        File file = new File(dataFolder, "挂机白名单.txt");
        if (!file.exists()) {
            try {
                file.createNewFile();
                FileWriter fw = new FileWriter(file);
                fw.write("# 每行一个玩家名\n");
                fw.flush();
                fw.close();
            } catch (IOException ignored) {}
            return list;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#"))
                    list.add(line);
            }
        } catch (IOException ignored) {}
        return list;
    }

    // ==================== 其他 ====================

    public String generateSmtpVerifyCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        smtpVerifyCode = sb.toString();
        smtpVerifyExpire = System.currentTimeMillis() + 300000L;
        return smtpVerifyCode;
    }

    public boolean checkSmtpVerifyCode(String code) {
        if (System.currentTimeMillis() > smtpVerifyExpire) { smtpVerifyCode = ""; return false; }
        return smtpVerifyCode.equalsIgnoreCase(code);
    }

    public void clearSmtpVerifyCode() { smtpVerifyCode = ""; smtpVerifyExpire = 0; }
    public boolean canSendSmtpVerify() { return System.currentTimeMillis() - smtpLastSendTime >= 60000L; }
    public void recordSmtpSendTime() { smtpLastSendTime = System.currentTimeMillis(); }

    // ==================== 文件工具 ====================

    private void loadMap(File file, Map<String, String> map) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            String lastKey = null; // 跟踪上一个key，用于续行
            while ((line = r.readLine()) != null) {
                // 处理UTF-8 BOM头
                if (firstLine) {
                    firstLine = false;
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    lastKey = null; // 空行/注释行中断续行
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    // key=value 标准行
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    map.put(key, val);
                    lastKey = key; // 记录key，后续裸行可续行
                } else if (lastKey != null && !map.getOrDefault(lastKey, "").isEmpty()) {
                    // 裸行 + 上一个key有值 → 续行，用\n连接
                    String existing = map.get(lastKey);
                    map.put(lastKey, existing + "\n" + line);
                } else if (lastKey != null) {
                    // 裸行 + 上一个key值为空 → 直接赋值
                    map.put(lastKey, line);
                }
            }
        } catch (IOException ignored) {}
    }

    private void writeLines(File file, List<String> lines) {
        // ★ 原子写入：先写临时文件再rename，防止写入中断导致文件损坏
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (String l : lines) { w.write(l); w.newLine(); }
            w.flush(); // 强制刷盘
            w.close(); // 确保关闭
            // ★ 原子替换：将.tmp文件重命名为目标文件
            if (!tmp.renameTo(file)) {
                // renameTo在Windows上可能失败（文件锁定等），fallback到copy+delete
                try {
                    java.nio.file.Files.copy(tmp.toPath(), file.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tmp.delete();
                } catch (IOException e) {
                    logger.warning("[Sdf1_login] 配置文件保存失败: " + e.getMessage());
                    tmp.delete();
                }
            }
        } catch (IOException e) {
            logger.warning("[Sdf1_login] 配置文件写入失败: " + e.getMessage());
            tmp.delete();
        }
    }

    private double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    

    /**
     * 将自然语言时间字符串解析为秒数
     * 支持：90 | 1:30 | 1.30 | 一分钟三十秒 | 2分钟 | 90秒 | minute | min | II | IX | 贰分 | Roman numerals
     */
    public int parseIntFromString(String s) {
        if (s == null || s.trim().isEmpty()) return 90;
        String trimmed = s.trim();

        // ====== 0. 中文混合解析（优先：支持"2分钟"、"一分钟三十秒"、"贰分钟"等） ======
        String testTrimmed = trimmed;
        boolean hasChinese = false;
        for (int ci = 0; ci < testTrimmed.length(); ci++) {
            char cc = testTrimmed.charAt(ci);
            if (cc >= '\u4e00' && cc <= '\u9fff') { hasChinese = true; break; }
        }
        if (hasChinese) {
            int cnResult = parseChineseTime(testTrimmed);
            if (cnResult >= 0) return cnResult;
        }

        // ====== 1. 英文混合解析（支持"2 minutes"、"one hour thirty seconds"等） ======
        int enResult = parseEnglishTime(testTrimmed.toLowerCase());
        if (enResult >= 0) return enResult;

        // ====== 2. 纯数字（秒） ======
        try { return Integer.parseInt(testTrimmed); } catch (Exception ignored) {}

        // ====== 3. 冒号分隔 mm:ss ======
        if (testTrimmed.contains(":")) {
            String[] parts = testTrimmed.split(":");
            try {
                int min = Integer.parseInt(parts[0].trim());
                int sec = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                return min * 60 + sec;
            } catch (Exception ignored) {}
        }

        // ====== 4. 点号分隔 mm.ss ======
        if (testTrimmed.matches(".*\\d+\\.\\d+.*")) {
            try {
                String[] parts = testTrimmed.split("\\.");
                if (parts.length == 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
                    int min = Integer.parseInt(parts[0]);
                    int sec = Integer.parseInt(parts[1]);
                    return min * 60 + sec;
                }
            } catch (Exception ignored) {}
        }

        // ====== 5. 罗马数字（支持含空格：如"ii ii"→10） ======
        String testLower = testTrimmed.toLowerCase();
        if (testLower.chars().allMatch(c -> "ivxlcdm ".indexOf(c) >= 0) && !testTrimmed.replace(" ", "").isEmpty()) {
            String[] parts = testTrimmed.split("\\s+");
            int total = 0;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                int val = parseRoman(part);
                if (val > 0) total += val;
            }
            if (total > 0) return total;
        }

        // ====== 6. 兜底：提取所有数字 ======
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("\\d+").matcher(testTrimmed);
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        while (nm.find()) nums.add(Integer.parseInt(nm.group()));
        if (nums.size() == 1) return nums.get(0);
        if (nums.size() >= 2) return nums.get(0) * 60 + nums.get(1);

        return 90;
    }

    /** 解析单个罗马数字串 */
    private int parseRoman(String s) {
        s = s.toLowerCase();
        java.util.Map<Character, Integer> m = new java.util.HashMap<>();
        m.put('i', 1); m.put('v', 5); m.put('x', 10);
        m.put('l', 50); m.put('c', 100); m.put('d', 500); m.put('m', 1000);
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int curr = m.getOrDefault(s.charAt(i), 0);
            if (curr < prev) total -= curr; else total += curr;
            prev = curr;
        }
        return total;
    }

    /**
     * 中文时间解析（含阿拉伯数字+中文单位混合，如"2分钟"、"90秒"、"一分钟三十秒"、"贰分"等）
     * 返回秒数，无法解析返回-1
     */
    private int parseChineseTime(String raw) {
        // 中文数字映射（含繁体/大写/各种变体）
        java.util.Map<String, Integer> cnDigit = new java.util.LinkedHashMap<>();
        cnDigit.put("零", 0); cnDigit.put("\u3007", 0);
        cnDigit.put("一", 1); cnDigit.put("壹", 1); cnDigit.put("幺", 1);
        cnDigit.put("二", 2); cnDigit.put("贰", 2); cnDigit.put("貳", 2); cnDigit.put("两", 2); cnDigit.put("弐", 2);
        cnDigit.put("三", 3); cnDigit.put("叁", 3); cnDigit.put("參", 3);
        cnDigit.put("四", 4); cnDigit.put("肆", 4);
        cnDigit.put("五", 5); cnDigit.put("伍", 5);
        cnDigit.put("六", 6); cnDigit.put("陆", 6); cnDigit.put("陸", 6);
        cnDigit.put("七", 7); cnDigit.put("柒", 7); cnDigit.put("漆", 7);
        cnDigit.put("八", 8); cnDigit.put("捌", 8);
        cnDigit.put("九", 9); cnDigit.put("玖", 9);

        // 时间单位映射（优先匹配长单位）
        java.util.List<java.util.Map.Entry<String, Integer>> unitsList = new java.util.ArrayList<>();
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("秒钟", 1));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("小时", 3600));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("小時", 3600));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("分钟", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("分鍾", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("分鐘", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("分種", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("秒", 1));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("分", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("钟", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("鐘", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("時", 3600));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("s", 1));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("m", 60));
        unitsList.add(new java.util.AbstractMap.SimpleEntry<>("h", 3600));

        java.util.Set<String> multipliers = new java.util.HashSet<>(
            java.util.Arrays.asList("十", "拾", "百", "佰", "千", "仟", "万"));
        
        // 英文数字映射（混合解析用）
        java.util.Map<String, Integer> enDigit = new java.util.LinkedHashMap<>();
        enDigit.put("zero", 0); enDigit.put("one", 1); enDigit.put("two", 2); enDigit.put("three", 3); enDigit.put("four", 4);
        enDigit.put("five", 5); enDigit.put("six", 6); enDigit.put("seven", 7); enDigit.put("eight", 8); enDigit.put("nine", 9);
        enDigit.put("ten", 10); enDigit.put("eleven", 11); enDigit.put("twelve", 12); enDigit.put("thirteen", 13);
        enDigit.put("fourteen", 14); enDigit.put("fifteen", 15); enDigit.put("sixteen", 16); enDigit.put("seventeen", 17);
        enDigit.put("eighteen", 18); enDigit.put("nineteen", 19);
        enDigit.put("twenty", 20); enDigit.put("thirty", 30); enDigit.put("forty", 40); enDigit.put("fifty", 50);
        enDigit.put("sixty", 60); enDigit.put("seventy", 70); enDigit.put("eighty", 80); enDigit.put("ninety", 90);
        enDigit.put("hundred", 100); enDigit.put("thousand", 1000);
        
        String s = raw;
        int totalSeconds = 0;
        int pendingValue = 0;

        // ★ 第一步：按最大匹配原则分词
        java.util.List<String> tokens = new java.util.ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                // 尝试2字符匹配（先匹配单位复合词）
                if (i + 1 < s.length() && s.charAt(i+1) >= '\u4e00' && s.charAt(i+1) <= '\u9fff') {
                    String two = s.substring(i, i+2);
                    boolean matchedUnit = false;
                    for (var entry : unitsList) {
                        if (entry.getKey().equals(two)) {
                            tokens.add(two);
                            i += 2;
                            matchedUnit = true;
                            break;
                        }
                    }
                    if (matchedUnit) continue;
                    
                    // 检查两个中文字符串是否可以组合成中文数字（如"十五"→15、「三十」→30、「一百」→100）
                    String firstChar = String.valueOf(s.charAt(i));
                    String secondChar = String.valueOf(s.charAt(i+1));
                    Integer dFirst = cnDigit.get(firstChar);
                    Integer dSecond = cnDigit.get(secondChar);
                    
                    if (dFirst != null && dSecond != null) {
                        // 两个都是数字 → 拼成一个完整中文数字（如"一二"→12, "二五"→25）
                        tokens.add(firstChar + secondChar);
                        i += 2;
                        continue;
                    }
                    if (dFirst != null && multipliers.contains(secondChar)) {
                        // 数字+乘法器（如"一二"中"二"不是乘法器，跳过这分支）
                        // 实际上这种情况不会发生因为multipliers是{十,百,千,万}
                    }
                    // 不满足组合条件 → 拆开
                    tokens.add(firstChar);
                    i++;
                } else {
                    tokens.add(String.valueOf(c));
                    i++;
                }
            } else if ((c >= '0' && c <= '9') || c == '.') {
                int start = i;
                while (i < s.length() && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.')) i++;
                tokens.add(s.substring(start, i));
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                int start = i;
                while (i < s.length() && ((s.charAt(i) >= 'a' && s.charAt(i) <= 'z') || (s.charAt(i) >= 'A' && s.charAt(i) <= 'Z'))) i++;
                tokens.add(s.substring(start, i).toLowerCase());
            } else {
                i++;
            }
        }

        // ★ 第二步：将token中的中文数字组合串解析为实际数值
        // 如"十五"→15、"三十"→30、「一百二十」→120
        java.util.List<Integer> parsedTokens = new java.util.ArrayList<>();
        for (String tok : tokens) {
            // 检查是否是多字符中文数字组合（如"十五"）
            boolean isMultiCharNum = false;
            for (String t : tokens) {
                if (t.equals(tok) && t.length() > 1 && t.chars().allMatch(c -> (c >= '\u4e00' && c <= '\u9fff') || (c >= '0' && c <= '9'))) {
                    // 所有字符都是中文数字或乘法器 → 解析中文数字组合
                    int val = evalChineseCompoundNum(tok, cnDigit, multipliers);
                    if (val >= 0) {
                        parsedTokens.add(val);
                        isMultiCharNum = true;
                        break;
                    }
                }
            }
            if (isMultiCharNum) continue;
            parsedTokens.add(null); // placeholder
        }
        
        // ★ 第三步：遍历解析后的token序列，计算总值
        for (int idx = 0; idx < tokens.size(); idx++) {
            String tok = tokens.get(idx);
            
            // 检查是否是中文复合数字（已通过parsedTokens解析为整数）
            if (parsedTokens.get(idx) != null) {
                int val = parsedTokens.get(idx);
                if (val >= 0) {
                    pendingValue = val;
                }
                continue;
            }
            
            // 中文数字映射
            Integer digit = cnDigit.get(tok);
            if (digit != null) {
                pendingValue = digit;
                continue;
            }

            // 乘法器（十百千万）
            if (multipliers.contains(tok)) {
                if (pendingValue == 0) pendingValue = 1;
                if (tok.equals("十") || tok.equals("拾")) pendingValue *= 10;
                else if (tok.equals("百") || tok.equals("佰")) pendingValue *= 100;
                else if (tok.equals("千") || tok.equals("仟")) pendingValue *= 1000;
                else if (tok.equals("万")) pendingValue *= 10000;
                continue;
            }

            // 时间单位
            Integer unitMul = null;
            for (var entry : unitsList) {
                if (entry.getKey().equals(tok)) {
                    unitMul = entry.getValue();
                    break;
                }
            }
            if (unitMul != null) {
                if (pendingValue == 0) pendingValue = 1;
                totalSeconds += pendingValue * unitMul;
                pendingValue = 0;
                continue;
            }

            // 阿拉伯数字
            try {
                if (tok.contains(".")) pendingValue = (int) Double.parseDouble(tok);
                else pendingValue = Integer.parseInt(tok);
                continue;
            } catch (Exception ignored) {}

            // 英文数字单词（混合解析：如"壹零zero秒" → 100秒）
            Integer enVal = enDigit.get(tok);
            if (enVal != null) {
                pendingValue = enVal;
                continue;
            }

            // ★ 罗马数字（如"II分X秒"→2分10秒=130秒）
            if (tok.length() > 0 && tok.chars().allMatch(c -> "ivxlcdm".indexOf(c) >= 0)) {
                int romanVal = parseRoman(tok);
                if (romanVal > 0) {
                    pendingValue = romanVal;
                    continue;
                }
            }
            
            // 无法识别的token
            pendingValue = 0;
        }

        // 剩余的pendingValue当作秒
        totalSeconds += pendingValue;

        return totalSeconds > 0 ? totalSeconds : -1;
    }

    /** 评估中文数字组合串（如"十五"→15、「一百二十三」→123） */
    private int evalChineseCompoundNum(String s, java.util.Map<String, Integer> cnDigit, java.util.Set<String> multipliers) {
        int total = 0, current = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String ch = String.valueOf(c);
            Integer d = cnDigit.get(ch);
            if (d != null && d > 0) {
                if (current > 0) current = current * 10 + d;
                else current = d;
            } else if (d != null && d == 0) {
                // zero - skip
            } else if (multipliers.contains(ch)) {
                if (ch.equals("十") || ch.equals("拾")) current = (current == 0 ? 1 : current) * 10;
                else if (ch.equals("百") || ch.equals("佰")) current = (current == 0 ? 1 : current) * 100;
                else if (ch.equals("千") || ch.equals("仟")) current = (current == 0 ? 1 : current) * 1000;
                else if (ch.equals("万")) current = (current == 0 ? 1 : current) * 10000;
                total += current;
                current = 0;
            } else {
                // non-digit, non-multiplier character → not a pure Chinese number
                return -1;
            }
        }
        total += current;
        return total > 0 ? total : -1;
    }

    /** 解析英文时间字符串，返回秒数或-1(无法解析) */
    private int parseEnglishTime(String s) {
        // 英文数字映射（1-90, 100, 1000）
        java.util.Map<String, Integer> en = new java.util.LinkedHashMap<>();
        en.put("zero", 0); en.put("one", 1); en.put("two", 2); en.put("three", 3); en.put("four", 4);
        en.put("five", 5); en.put("six", 6); en.put("seven", 7); en.put("eight", 8); en.put("nine", 9);
        en.put("ten", 10); en.put("eleven", 11); en.put("twelve", 12); en.put("thirteen", 13);
        en.put("fourteen", 14); en.put("fifteen", 15); en.put("sixteen", 16); en.put("seventeen", 17);
        en.put("eighteen", 18); en.put("nineteen", 19); en.put("twenty", 20);
        en.put("thirty", 30); en.put("forty", 40); en.put("fifty", 50); en.put("sixty", 60);
        en.put("seventy", 70); en.put("eighty", 80); en.put("ninety", 90);
        en.put("hundred", 100); en.put("thousand", 1000);

        // 单位倍率
        java.util.Map<String, Integer> units = new java.util.LinkedHashMap<>();
        units.put("second", 1); units.put("seconds", 1); units.put("sec", 1); units.put("secs", 1);
        units.put("second", 1); units.put("minute", 60); units.put("minutes", 60); units.put("min", 60); units.put("mins", 60);
        units.put("hour", 3600); units.put("hours", 3600); units.put("hr", 3600); units.put("hrs", 3600);
        // 单字母缩写
        units.put("s", 1); units.put("m", 60); units.put("h", 3600);

        // 按空格和逗号分割
        String[] words = s.split("[\\s,]+");
        int totalSeconds = 0;
        int currentNum = 0;
        boolean hasUnit = false;

        for (String word : words) {
            if (word.isEmpty()) continue;

            // 1. 检查是否是单位（可能紧跟数字，如"2min"→拆分为"2"和"min"，这里处理不含数字的情况）
            // 由于已经按空格分割了，单位应该是独立的word
            Integer unitMul = units.get(word);
            if (unitMul != null) {
                if (currentNum == 0) currentNum = 1;
                totalSeconds += currentNum * unitMul;
                currentNum = 0;
                hasUnit = true;
                continue;
            }

            // 2. 检查是否是阿拉伯数字
            try {
                // 可能是带单位的合并词如"2min"、"5h"
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)([a-z]+)").matcher(word);
                if (matcher.matches()) {
                    currentNum = Integer.parseInt(matcher.group(1));
                    String unitStr = matcher.group(2);
                    Integer uMul = units.get(unitStr);
                    if (uMul != null) {
                        totalSeconds += currentNum * uMul;
                        currentNum = 0;
                        hasUnit = true;
                        continue;
                    }
                }
                currentNum = Integer.parseInt(word);
                continue;
            } catch (Exception ignored) {}

            // 3. 检查是否是英文数字单词
            Integer numVal = en.get(word);
            if (numVal != null) {
                if (numVal >= 100) {
                    if (currentNum == 0) currentNum = 1;
                    currentNum *= numVal;
                } else {
                    currentNum += numVal;
                }
                continue;
            }

            // 4. 检查连字符数字（twenty-one, thirty-five）
            if (word.contains("-")) {
                String[] parts = word.split("-");
                int compound = 0;
                for (String p : parts) {
                    Integer pv = en.get(p);
                    if (pv != null) compound += pv;
                }
                if (compound > 0) {
                    currentNum += compound;
                }
            }
        }

        return hasUnit ? totalSeconds : -1;
    }
}
