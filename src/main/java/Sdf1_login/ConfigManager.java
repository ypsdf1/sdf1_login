package Sdf1_login;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigManager {

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
        if (!file.exists()) createDefaultSmtp(file);
        loadMap(file, smtpSettings);
    }

    public String getSmtp(String key) {
        return smtpSettings.getOrDefault(key, "");
    }

    public void setSmtp(String key, String value) {
        smtpSettings.put(key, value);
    }

    public void saveSmtp() {
        File file = new File(dataFolder, "SMTP设置.txt");
        List<String> L = new ArrayList<>();
        L.add("# ===== SMTP 配置 =====");
        for (Map.Entry<String, String> e
                : smtpSettings.entrySet()) {
            L.add(e.getKey() + "=" + e.getValue());
        }
        writeLines(file, L);
    }

    private void createDefaultSmtp(File f) {
        List<String> L = new ArrayList<>();
        L.add("# ===== SMTP 配置 =====");
        L.add("smtp地址=smtp.example.com");
        L.add("smtp端口=465");
        L.add("smtp账号=");
        L.add("smtp密码=");
        L.add("发件人名称=Sdf1_login");
        L.add("smtp加密=true");
        writeLines(f, L);
    }

    // ==================== 插件设置 ====================

    public void loadSettings() {
        File file = new File(dataFolder, "插件设置.txt");
        if (!file.exists()) createDefaultSettings(file);
        Map<String, String> m = new LinkedHashMap<>();
        loadMap(file, m);

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

// 验证合法值
        if (!"economy".equalsIgnoreCase(rewardChannel)
                && !"bonds".equalsIgnoreCase(rewardChannel)) {
            rewardChannel = "bonds";
        }



        // ★ 自动补充新配置项 ★
        boolean needAppend = false;
        List<String> newKeys = new ArrayList<>();
        if (!m.containsKey("签到债券最小")) { needAppend = true; newKeys.add("签到债券最小=" + checkinBondMin); }
        if (!m.containsKey("签到债券最大")) { needAppend = true; newKeys.add("签到债券最大=" + checkinBondMax); }
        if (!m.containsKey("奖励发放方式")) {
            needAppend = true;
            newKeys.add("奖励发放方式=债券");
        }
        if (needAppend) {
            try {
                FileWriter fw = new FileWriter(file, true);
                BufferedWriter bw = new BufferedWriter(fw);
                bw.newLine();
                for (String key : newKeys) { bw.write(key); bw.newLine(); }
                bw.flush(); bw.close();
                System.out.println("[Config] 已补充签到债券配置");

            } catch (IOException ignored) {}
            if (!m.containsKey("奖励发放方式")) {
                try {
                    FileWriter fw = new FileWriter(file, true);
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.newLine();
                    bw.write("奖励发放方式=bonds");
                    bw.newLine();
                    bw.flush(); bw.close();
                } catch (IOException ignored) {}
            }

        }
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
        List<String> L = new ArrayList<>();
        L.add("# ===== Sdf1_login 插件设置 =====");
        L.add("管理标签=" + adminTag);
        L.add("管理密码=" + adminPassword);
        L.add("每IP最大账号数=" + maxAccountsPerIP);
        L.add("审批模式=" + approvalMode);
        L.add("自动审批延迟分钟=" + autoApproveDelayMinutes);
        L.add("登录超时秒数=" + loginTimeout);
        L.add("挂机踢出=" + afkEnabled);
        L.add("挂机超时秒数=" + afkTimeout);
        L.add("resource-pack-url=" + broadcastServerUrl);
        L.add("http-port=" + httpPort);
        L.add("垃圾站_启用=" + garbageEnabled);
        L.add("垃圾站_清理间隔秒=" + garbageInterval);
        L.add("垃圾站_保留轮数=" + garbageMaxRounds);
        L.add("签到固定金额=" + checkinRewardFixed);
        L.add("签到最小金额=" + checkinRewardMin);
        L.add("签到最大金额=" + checkinRewardMax);
        L.add("签到给积分=" + checkinGivePoints);
        L.add("签到积分数量=" + checkinPoints);
        L.add("签到债券最小=" + checkinBondMin);
        L.add("签到债券最大=" + checkinBondMax);
        L.add("补签积分倍率=" + backCheckPointMultiplier);
        L.add("奖励发放方式=债券");
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
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {}
    }

    private void writeLines(File file, List<String> lines) {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String l : lines) { w.write(l); w.newLine(); }
        } catch (IOException ignored) {}
    }

    private double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
}
