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

    // ===== 区域防护配置 =====
    public String areaProtectAdminMode = "tag"; // "tag" 或 "op"，不共存
    public String areaProtectAdminTag = "admin"; // 管理员Tag名
    public double areaProtectPricePerSqM = 10.0; // 每平米价格(债券)
    public int areaProtectMaxLands = 3; // 每个玩家最大领地数

    // ===== 传送配置 =====
    public int tpRequestValidSeconds = 90; // 请求有效时间（秒）
    public int tpSendIntervalSeconds = 10; // 发送间隔（秒）

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
                System.out.println("[Config] 已补充缺失的配置项");
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
        tpSendIntervalSeconds = parseIntFromString(m.getOrDefault("传送_发送间隔秒", "10"));

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
        // ★ 原子写入：先写临时文件再rename，防止写入中断导致文件损坏
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (String l : lines) { w.write(l); w.newLine(); }
            w.flush(); // 强制刷盘
            w.close(); // 确保关闭
            // renameTo 是原子操作，成功则替换失败则保留原文件
            if (!file.renameTo(tmp) && !tmp.renameTo(file)) {
                // 如果rename失败，删除临时文件
                tmp.delete();
            }
        } catch (IOException ignored) {
            // 写入失败时删除临时文件，保留原文件不变
            tmp.delete();
        }
    }

    private double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    

    /**
     * 将自然语言时间字符串解析为秒数
     * 支持：1:30、1分30秒、1.30、90、一分钟三十秒、壹贰分叁拾秒、
     *       1 hour 30 minutes、one minute thirty seconds、1小时30分钟、
     *       贰分、90秒、ninety seconds、罗马数字等
     */
    public int parseIntFromString(String s) {
        if (s == null || s.trim().isEmpty()) return 90;
        s = s.trim().toLowerCase();

        // ====== 1. 纯数字 ======
        try { return Integer.parseInt(s); } catch (Exception ignored) {}

        // ====== 2. 冒号分隔 mm:ss ======
        if (s.contains(":")) {
            String[] parts = s.split(":");
            try {
                int min = Integer.parseInt(parts[0].trim());
                int sec = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                return min * 60 + sec;
            } catch (Exception ignored) {}
        }

        // ====== 3. 点号分隔 mm.ss ======
        if (s.matches(".*\\d+\\.\\d+.*")) {
            try {
                String[] parts = s.split("\\.");
                if (parts.length == 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
                    int min = Integer.parseInt(parts[0]);
                    int sec = Integer.parseInt(parts[1]);
                    return min * 60 + sec;
                }
            } catch (Exception ignored) {}
        }

        // ====== 4. 中文数字解析 ======
        int cnResult = parseChineseTime(s);
        if (cnResult >= 0) return cnResult;

        // ====== 5. 英文解析（复合格式） ======
        int enResult = parseEnglishTime(s);
        if (enResult >= 0) return enResult;

        // ====== 6. 罗马数字 ======
        if (s.chars().allMatch(c -> "ivxlcdm".indexOf(c) >= 0) && !s.isEmpty()) {
            java.util.Map<Character, Integer> romanNum = new java.util.HashMap<>();
            romanNum.put('i', 1); romanNum.put('v', 5); romanNum.put('x', 10);
            romanNum.put('l', 50); romanNum.put('c', 100); romanNum.put('d', 500); romanNum.put('m', 1000);
            int total = 0, prev = 0;
            for (int i = s.length() - 1; i >= 0; i--) {
                int curr = romanNum.getOrDefault(s.charAt(i), 0);
                if (curr < prev) total -= curr; else total += curr;
                prev = curr;
            }
            if (total > 0) return total;
        }

        // ====== 7. 兜底：提取所有数字 ======
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("\\d+").matcher(s);
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        while (nm.find()) nums.add(Integer.parseInt(nm.group()));
        if (nums.size() == 1) return nums.get(0);
        if (nums.size() >= 2) return nums.get(0) * 60 + nums.get(1);

        return 90;
    }

    /** 解析中文时间字符串，返回秒数或-1(无法解析) */
    private int parseChineseTime(String s) {
        // 中文数字映射（含繁体/大写/各种变体）
        java.util.Map<String, Integer> cn = new java.util.LinkedHashMap<>();
        cn.put("零", 0); cn.put("\u3007", 0); // 〇
        cn.put("一", 1); cn.put("壹", 1); cn.put("幺", 1);
        cn.put("二", 2); cn.put("贰", 2); cn.put("貳", 2); cn.put("两", 2); cn.put("弐", 2);
        cn.put("三", 3); cn.put("叁", 3); cn.put("參", 3);
        cn.put("四", 4); cn.put("肆", 4);
        cn.put("五", 5); cn.put("伍", 5);
        cn.put("六", 6); cn.put("陆", 6); cn.put("陸", 6);
        cn.put("七", 7); cn.put("柒", 7); cn.put("漆", 7);
        cn.put("八", 8); cn.put("捌", 8);
        cn.put("九", 9); cn.put("玖", 9);

        // 检测是否包含中文数字字符（数字本身或乘法单位）
        boolean hasCnDigit = false;
        for (char c : s.toCharArray()) {
            if (cn.containsKey(String.valueOf(c))) { hasCnDigit = true; break; }
        }
        if (!hasCnDigit) {
            if (s.contains("十") || s.contains("拾") || s.contains("百") || s.contains("佰")
                    || s.contains("千") || s.contains("仟") || s.contains("万")) {
                hasCnDigit = true;
            }
        }
        if (!hasCnDigit) return -1;

        // 逐字扫描中文数字 + 时间单位
        int total = 0;
        int current = 0;
        int i = 0;
        boolean hasTimeUnit = false;
        while (i < s.length()) {
            // 检查"小时"（两个字符的单位）
            if (i + 1 < s.length()) {
                String twoChar = s.substring(i, i + 2);
                if (twoChar.equals("小时") || twoChar.equals("小時")) {
                    if (current == 0) current = 1;
                    total = (total + current) * 3600;
                    current = 0;
                    hasTimeUnit = true;
                    i += 2;
                    continue;
                }
            }
            String ch = String.valueOf(s.charAt(i));
            Integer digit = cn.get(ch);
            if (digit != null && digit >= 0 && digit <= 9) {
                // 单个中文数字：如果已经有乘法器累积的值，则追加；否则直接赋值
                if (current > 0 && total == 0) {
                    // 连续数字如"一二"→12
                    current = current * 10 + digit;
                } else {
                    current = digit;
                }
            } else if (ch.equals("十") || ch.equals("拾")) {
                if (current == 0) current = 1; // "十二"→12, "十" alone→10
                total += current * 10;
                current = 0;
            } else if (ch.equals("百") || ch.equals("佰")) {
                if (current == 0) current = 1;
                total += current * 100;
                current = 0;
            } else if (ch.equals("千") || ch.equals("仟")) {
                if (current == 0) current = 1;
                total += current * 1000;
                current = 0;
            } else if (ch.equals("万")) {
                if (current == 0) current = 1;
                total += current * 10000;
                current = 0;
            } else if (ch.equals("秒")) {
                total += current;
                current = 0;
                hasTimeUnit = true;
            } else if (ch.equals("分")) {
                // 分后面可能跟"钟"单独出现，这里处理"分"作为分钟单位
                total = (total + current) * 60;
                current = 0;
                hasTimeUnit = true;
            } else if (ch.equals("钟") || ch.equals("鐘")) {
                // 单独出现的"钟"跟在"分"后面——已经被前面的分处理过了
                // 如果"钟"单独出现（极少见），也当分钟处理
                total = (total + current) * 60;
                current = 0;
                hasTimeUnit = true;
            }
            i++;
        }
        total += current;

        if (hasTimeUnit) return total;
        return hasCnDigit ? total : -1;
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

        // 单位倍率（值 = 换算为秒的乘数）
        java.util.Map<String, Integer> units = new java.util.LinkedHashMap<>();
        units.put("second", 1); units.put("seconds", 1); units.put("sec", 1); units.put("secs", 1);
        units.put("minute", 60); units.put("minutes", 60); units.put("min", 60); units.put("mins", 60);
        units.put("hour", 3600); units.put("hours", 3600); units.put("hr", 3600); units.put("hrs", 3600);

        // 检测是否有英文时间单位
        boolean hasEnUnit = false;
        for (String u : units.keySet()) {
            if (s.contains(" " + u + " ") || s.endsWith(" " + u) || s.startsWith(u + " ") || s.equals(u)) {
                hasEnUnit = true;
                break;
            }
        }
        if (!hasEnUnit) return -1;

        // 按空格分割，逐词扫描
        String[] words = s.split("\\s+");
        int totalSeconds = 0;
        int currentNum = 0;
        boolean hasSeenUnit = false; // 是否已见过明确的时间单位

        for (String word : words) {
            String clean = word.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (clean.isEmpty()) continue;

            // 1. 检查是否是单位
            Integer unitMul = units.get(clean);
            if (unitMul != null) {
                if (currentNum == 0) currentNum = 1;
                totalSeconds += currentNum * unitMul;
                currentNum = 0;
                hasSeenUnit = true;
                continue;
            }

            // 2. 检查是否是阿拉伯数字
            try {
                currentNum = Integer.parseInt(clean);
                continue;
            } catch (Exception ignored) {}

            // 3. 检查是否是英文数字单词
            Integer numVal = en.get(clean);
            if (numVal != null) {
                if (numVal >= 100) {
                    // hundred/thousand: currentNum * 100 or * 1000
                    if (currentNum == 0) currentNum = 1;
                    currentNum *= numVal;
                } else {
                    currentNum += numVal;
                }
                continue;
            }

            // 4. 检查连字符数字（twenty-one, thirty-five）
            if (clean.contains("-")) {
                String[] parts = clean.split("-");
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

        return totalSeconds > 0 ? totalSeconds : -1;
    }
}
