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
        java.util.List<String> preservedLines = new ArrayList<>();
        for (String line : existingLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!trimmed.contains("=")) continue;
            int eq = trimmed.indexOf('=');
            String key = trimmed.substring(0, eq).trim();
            if (!managed.containsKey(key)) {
                preservedLines.add(key + "=" + trimmed.substring(eq + 1).trim());
            }
        }

        // ★ 写入完整配置
        List<String> L = new ArrayList<>();
        L.add("# ===== Sdf1_login 插件设置 =====");
        for (java.util.Map.Entry<String, String> e : managed.entrySet()) {
            L.add(e.getKey() + "=" + e.getValue());
        }
        // 追加保留的其他模块配置
        if (!preservedLines.isEmpty()) {
            L.add("");
            L.add("# ===== 其他模块配置 =====");
            L.addAll(preservedLines);
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
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String l : lines) { w.write(l); w.newLine(); }
        } catch (IOException ignored) {}
    }

    private double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    
    /**
     * 将自然语言时间字符串解析为秒数
     * 支持：1:30、1分30秒、1.30、90、一分钟三十秒、壹分种叁拾秒、Ninety、Ninety秒、IX〇秒、One minute thirty seconds等
     */
    public int parseIntFromString(String s) {
        if (s == null) return 90;
        s = s.trim().toLowerCase();
        
        // 规则1：纯数字
        try { return Integer.parseInt(s); } catch (Exception ignored) {}
        
        // 规则2：冒号分隔 mm:ss
        if (s.contains(":")) {
            String[] parts = s.split(":");
            try {
                int min = Integer.parseInt(parts[0]);
                int sec = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return min * 60 + sec;
            } catch (Exception ignored) {}
        }
        
        // 规则3：点号分隔 mm.ss
        if (s.matches("\\d+\\.\\d+")) {
            String[] parts = s.split("\\.");
            try {
                int min = Integer.parseInt(parts[0]);
                int sec = Integer.parseInt(parts[1]);
                return min * 60 + sec;
            } catch (Exception ignored) {}
        }
        
        // 规则4：中文/英文混合解析 —— 逐字扫描
        // 中文数字映射
        java.util.Map<String, Integer> cnNum = new java.util.LinkedHashMap<>();
        cnNum.put("零", 0); cnNum.put("一", 1); cnNum.put("壹", 1); cnNum.put("二", 2); cnNum.put("贰", 2); cnNum.put("两", 2);
        cnNum.put("三", 3); cnNum.put("叁", 3); cnNum.put("四", 4); cnNum.put("肆", 4); cnNum.put("五", 5); cnNum.put("伍", 5);
        cnNum.put("六", 6); cnNum.put("陆", 6); cnNum.put("七", 7); cnNum.put("漆", 7); cnNum.put("八", 8); cnNum.put("捌", 8);
        cnNum.put("九", 9); cnNum.put("玖", 9);
        
        // 中文数字转阿拉伯数字：逐字扫描法
        // 支持：一百二十、三千五百、十二万三千四百五十六
        // 单位：十(10)、百(100)、千(1000)、万(10000)
        boolean hasChineseDigit = false;
        for (char c : s.toCharArray()) {
            if (cnNum.containsKey(String.valueOf(c))) {
                hasChineseDigit = true;
                break;
            }
        }
        if (hasChineseDigit) {
            int total = 0;
            int current = 0;
            boolean hasUnit = false;
            for (int i = 0; i < s.length(); i++) {
                String ch = String.valueOf(s.charAt(i));
                Integer digit = cnNum.get(ch);
                if (digit != null) {
                    current = digit;
                    hasUnit = false;
                } else if (ch.equals("十")) {
                    if (current == 0) current = 1; // 十二 → 12
                    total += current * 10;
                    current = 0;
                    hasUnit = true;
                } else if (ch.equals("百")) {
                    if (current == 0) current = 1;
                    total += current * 100;
                    current = 0;
                    hasUnit = true;
                } else if (ch.equals("千")) {
                    if (current == 0) current = 1;
                    total += current * 1000;
                    current = 0;
                    hasUnit = true;
                } else if (ch.equals("万")) {
                    if (current == 0) current = 1;
                    total += current * 10000;
                    current = 0;
                    hasUnit = true;
                } else if (ch.equals("秒") || ch.equals("分") || ch.equals("钟")) {
                    // 单位字符，跳过
                    continue;
                }
            }
            total += current; // 加上最后剩余的数字
            // 如果有单位（秒/分钟），根据单位决定返回秒还是分钟
            if (s.contains("秒")) {
                return total;
            } else if (s.contains("分") || s.contains("钟")) {
                return total * 60;
            } else {
                return total; // 默认当作秒
            }
        }
        
        // 罗马数字映射（简单支持）
        java.util.Map<Character, Integer> romanNum = new java.util.HashMap<>();
        romanNum.put('I', 1); romanNum.put('V', 5); romanNum.put('X', 10); romanNum.put('L', 50); romanNum.put('C', 100);
        
        // 单位映射：中文
        java.util.regex.Pattern unitPat = java.util.regex.Pattern.compile("(\\d+)(分[钟钟]?|秒|分钟)?");
        
        // 尝试拆分"X分Y秒"格式
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)[分钟]?(?:秒)?(\\d+(?:\\.\\d+)?[秒]?)?").matcher(s);
        if (m.find()) {
            try {
                int minVal = (int) Float.parseFloat(m.group(1));
                int secVal = 0;
                if (m.group(2) != null) {
                    secVal = (int) Float.parseFloat(m.group(2).replaceAll("[^\\d\\.]", ""));
                }
                // 如果没有"分"关键字而是直接数字+秒，整个算秒
                if (!s.contains("分") && !s.contains("minute")) {
                    return (int) Float.parseFloat(s.replaceAll("[^\\d\\.]", ""));
                }
                return minVal * 60 + secVal;
            } catch (Exception ignored) {}
        }
        
        // 尝试英文解析："ninety seconds", "one minute thirty seconds"
        java.util.Map<String, Integer> enNum = new java.util.LinkedHashMap<>();
        enNum.put("zero", 0); enNum.put("one", 1); enNum.put("two", 2); enNum.put("three", 3); enNum.put("four", 4);
        enNum.put("five", 5); enNum.put("six", 6); enNum.put("seven", 7); enNum.put("eight", 8); enNum.put("nine", 9);
        enNum.put("ten", 10); enNum.put("eleven", 11); enNum.put("twelve", 12); enNum.put("thirteen", 13);
        enNum.put("fourteen", 14); enNum.put("fifteen", 15); enNum.put("sixteen", 16); enNum.put("seventeen", 17);
        enNum.put("eighteen", 18); enNum.put("nineteen", 19); enNum.put("twenty", 20);
        enNum.put("thirty", 30); enNum.put("forty", 40); enNum.put("fifty", 50); enNum.put("sixty", 60);
        enNum.put("minute", 60); enNum.put("minutes", 60); enNum.put("second", 1); enNum.put("seconds", 1); enNum.put("sec", 1);
        
        // 计算罗马数字
        if (s.chars().allMatch(c -> "IVXLCDM".indexOf(c) >= 0)) {
            int total = 0;
            int prev = 0;
            for (int i = s.length() - 1; i >= 0; i--) {
                int curr = romanNum.getOrDefault(s.charAt(i), 0);
                if (curr < prev) total -= curr; else total += curr;
                prev = curr;
            }
            if (total > 0) return total; // 纯罗马数字当作秒
        }
        
        // 尝试解析英文短语："one minute thirty seconds", "ninety seconds", "one fifty"
        try {
            String[] words = s.split("\\s+");
            int totalSeconds = 0;
            boolean foundUnit = false;
            for (String word : words) {
                word = word.toLowerCase().replaceAll("[^a-z]", "");
                if (word.isEmpty()) continue;
                Integer val = enNum.get(word);
                if (val != null) {
                    if (word.contains("second") || word.equals("sec")) {
                        totalSeconds += val; foundUnit = true;
                    } else if (word.equals("minute") || word.equals("minutes")) {
                        totalSeconds += val; foundUnit = true;
                    } else if (word.matches("\\d+")) {
                        totalSeconds += val; // 纯数字(英文单词形式)，当作最小单位（秒或分取决于上下文）
                    } else {
                        totalSeconds += val * 60; // 当作分钟数
                    }
                } else {
                    // 尝试阿拉伯数字
                    try {
                        int num = Integer.parseInt(word);
                        if (foundUnit && totalSeconds > 0) {
                            // 已经有单位了，这应该是个额外的数字
                            totalSeconds += num;
                        } else if (num <= 60) {
                            totalSeconds = num;
                        } else {
                            // 如"130" -> 130秒
                            totalSeconds = num;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (foundUnit || totalSeconds > 0) return totalSeconds;
        } catch (Exception ignored) {}
        
        // 兜底：提取所有数字
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("\\d+").matcher(s);
        List<Integer> nums = new ArrayList<>();
        while (nm.find()) nums.add(Integer.parseInt(nm.group()));
        if (nums.size() == 1) return nums.get(0);
        if (nums.size() == 2) {
            // 第一个当分钟，第二个当秒
            return nums.get(0) * 60 + nums.get(1);
        }
        
        return 90; // 默认
    }
}
