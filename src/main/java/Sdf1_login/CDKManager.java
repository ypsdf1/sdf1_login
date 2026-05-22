package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
public class CDKManager {

    private final Main plugin;
    private Connection db;
    private final Map<String, CDKInput> pendingInputs
            = new ConcurrentHashMap<>();

    public CDKManager(Main plugin) {
        this.plugin = plugin;
        initDB();
    }

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    plugin.getDataFolder(), "bond.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS cdk ("
                    + "code TEXT PRIMARY KEY,"
                    + "amount INTEGER DEFAULT 0,"
                    + "type TEXT DEFAULT 'bond',"
                    + "used INTEGER DEFAULT 0,"
                    + "used_by TEXT DEFAULT '',"
                    + "created_time INTEGER DEFAULT 0,"
                    + "used_time INTEGER DEFAULT 0)");
            st.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[CDK] DB init failed: " + e.getMessage(), e);
        }
    }

    public void loadCDKsFromDir() {
        File dir = new File(
                plugin.getDataFolder(), "cdk");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.getLogger().info(
                    "[CDK] 已创建 cdk/ 目录");
            return;
        }
        File[] files = dir.listFiles(
                (d, n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning(
                    "[CDK] cdk/ 目录下"
                            + "无 .txt 文件");
            return;
        }
        int count = 0;
        for (File f : files) {
            int loaded = loadCDKFile(f);
            plugin.getLogger().info(
                    "[CDK] 文件: " + f.getName()
                            + " 加载 " + loaded + " 条");
            count += loaded;
        }
        plugin.getLogger().info(
                "[CDK] 共导入 " + count + " 条");
    }

    /** 单文件加载，返回成功数 */
    private int loadCDKFile(File f) {
        int count = 0;
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            int lineNum = 0;
            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;
                if (line.startsWith("//")) continue;

                // 支持格式:
                // CODE 100
                // CODE,100
                // CODE 100 bond
                // CODE,100,bond
                String[] parts = line
                        .split("[,\\s]+");
                if (parts.length < 2) {
                    plugin.getLogger().warning(
                            "[CDK] " + f.getName()
                                    + " L" + lineNum
                                    + " 格式错误: "
                                    + line);
                    continue;
                }

                String code = parts[0].trim();
                if (code.isEmpty()) continue;

                int amount;
                try {
                    amount = Integer.parseInt(
                            parts[1].trim());
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning(
                            "[CDK] " + f.getName()
                                    + " L" + lineNum
                                    + " 金额格式错误: "
                                    + parts[1]);
                    continue;
                }

                String type = parts.length >= 3
                        ? parts[2].trim() : "bond";

                boolean ok = addCDK(code, amount,
                        type);
                if (ok) {
                    count++;
                } else {
                    plugin.getLogger().warning(
                            "[CDK] " + f.getName()
                                    + " L" + lineNum
                                    + " 重复跳过: "
                                    + code);
                }
            }
            br.close();
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[CDK] 读取失败: " + f.getName()
                            + " " + e.getMessage());
        }
        return count;
    }


    public int importFromFile(String fileName) {
        File dir = new File(
                plugin.getDataFolder(), "cdk");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 先在 cdk/ 目录下找
        File f = new File(dir, fileName);
        if (!f.exists()) {
            // 再在插件根目录找
            f = new File(
                    plugin.getDataFolder(), fileName);
        }
        if (!f.exists()) {
            plugin.getLogger().warning(
                    "[CDK] 文件不存在: "
                            + fileName);
            return -1;
        }

        plugin.getLogger().info(
                "[CDK] 开始导入: "
                        + f.getAbsolutePath()
                        + " 大小:" + f.length()
                        + " bytes");

        int count = loadCDKFile(f);
        plugin.getLogger().info(
                "[CDK] 导入完成: " + count + " 条");
        return count;
    }

    public int importFromSdf1(String fileName) {
        int count = 0;
        Scoreboard board = Bukkit.getScoreboardManager()
                .getMainScoreboard();
        // 尝试从sdf1的计分板读取口令
        // 这部分依赖sdf1插件的配置
        return count;
    }


    private boolean addCDK(String code, int amount,
                           String type) {
        try {
            // 先检查是否已存在
            PreparedStatement check =
                    db.prepareStatement(
                            "SELECT code FROM cdk "
                                    + "WHERE code=?");
            check.setString(1, code);
            ResultSet rs = check.executeQuery();
            if (rs.next()) {
                rs.close();
                check.close();
                return false;
            }
            rs.close();
            check.close();

            PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO cdk "
                                    + "(code, amount, type,"
                                    + " created_time)"
                                    + " VALUES (?,?,?,?)");
            ps.setString(1, code);
            ps.setInt(2, amount);
            ps.setString(3,
                    type != null ? type : "bond");
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[CDK] addCDK失败: " + code
                            + " " + e.getMessage());
            return false;
        }
    }

    // ===== 替换整个 redeem 方法 =====
    public String redeem(String code, String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT amount,type,used FROM cdk "
                            + "WHERE code=?");
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                rs.close(); ps.close();
                return null;
            }
            int amount = rs.getInt("amount");
            String type = rs.getString("type");
            int used = rs.getInt("used");
            rs.close(); ps.close();

            if (used == 1) return "该兑换码已被使用";

            // [新增] 冻结检查
            if (plugin.getBonds().isFrozen(player)) {
                return "账户已被冻结，无法兑换";
            }

            PreparedStatement up = db.prepareStatement(
                    "UPDATE cdk SET used=1,"
                            + "used_by=?,used_time=? "
                            + "WHERE code=?");
            up.setString(1, player);
            up.setLong(2, System.currentTimeMillis());
            up.setString(3, code);
            up.executeUpdate(); up.close();

            if ("bond".equals(type)) {
                // [修改] 使用带流水的重载方法
                plugin.getBonds().addBonds(player, amount,
                        BondManager.TX_REDEEM, "",
                        "system", "CDK兑换: " + code);
                return "兑换成功！获得 " + amount + " 债券";
            }
            return null;
        } catch (SQLException e) {
            return "兑换失败";
        }
    }

    // ===== 聊天输入系统（主插件转发调用）=====

    public void requestInput(Player p, String type, String context) {
        CDKInput input = new CDKInput();
        input.type = type;
        input.context = context;
        input.time = System.currentTimeMillis();
        pendingInputs.put(p.getName(), input);
    }

    public boolean isListening(String playerName) {
        return pendingInputs.containsKey(playerName);
    }

    public void onChat(Player p, String msg) {
        CDKInput input = pendingInputs.remove(p.getName());
        if (input == null) return;

        if (System.currentTimeMillis() - input.time > 60000) {
            p.sendMessage("§c输入超时，请重新操作");
            return;
        }

        if ("cdk".equals(input.type)) {
            String result = redeem(msg.trim(), p.getName());
            p.sendMessage(result != null
                    ? "§e§l[CDK] §f" + result
                    : "§c§l[CDK] §f无效的兑换码");
            return;
        }

        if ("econ".equals(input.type)) {
            handleEconInput(p, msg.trim(), input.context);
            return;
        }

        if ("bond".equals(input.type)) {
            handleBondInput(p, msg.trim(), input.context);
        }
    }

    private void handleEconInput(Player admin, String msg, String target) {
        net.milkbowl.vault.economy.Economy econ = null;
        try {
            var reg = plugin.getServer().getServicesManager()
                    .getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (reg != null) econ = reg.getProvider();
        } catch (Exception ignored) {}
        if (econ == null) {
            admin.sendMessage("§c经济系统不可用");
            return;
        }
        if (!msg.startsWith("+") && !msg.startsWith("-")) {
            admin.sendMessage("§c格式: §a+100 §7给钱 / §c-50 §7扣钱");
            return;
        }
        try {
            String numStr = msg.substring(1).trim();
            double amount = Double.parseDouble(numStr);
            if (amount <= 0) {
                admin.sendMessage("§c金额必须大于0");
                return;
            }
            if (msg.startsWith("-")) {
                Player tp = plugin.getServer().getPlayerExact(target);
                if (tp == null) {
                    admin.sendMessage("§c" + target + " 不在线");
                    return;
                }
                if (!econ.has(tp, amount)) {
                    admin.sendMessage("§c" + target + " 余额不足");
                    return;
                }
                double oldBal = econ.getBalance(tp);
                econ.withdrawPlayer(tp, amount);
                double newBal = econ.getBalance(tp);
                admin.sendMessage("§7============== §c余额扣款 §7==============");
                admin.sendMessage("§7目标: §e" + target);
                admin.sendMessage("§c扣款: §e-$" + String.format("%.2f", amount));
                admin.sendMessage("§7余额: §e" + String.format("%.2f", oldBal) + " → §c" + String.format("%.2f", newBal));
                admin.sendMessage("§7============== §c余额扣款 §7==============");
                tp.sendMessage("§7============== §c余额扣款 §7==============");
                tp.sendMessage("§c扣款: §e-$" + String.format("%.2f", amount));
                tp.sendMessage("§7余额: §e" + String.format("%.2f", oldBal) + " → §c" + String.format("%.2f", newBal));
                tp.sendMessage("§7操作者: §e" + admin.getName());
                tp.sendMessage("§7============== §c余额扣款 §7==============");
            } else {
                double oldBal = econ.getBalance(target);
                econ.depositPlayer(target, amount);
                double newBal = econ.getBalance(target);
                Player tp = plugin.getServer().getPlayerExact(target);
                admin.sendMessage("§7============== §6余额转账 §7==============");
                admin.sendMessage("§7目标: §e" + target);
                admin.sendMessage("§a到账: §e$" + String.format("%.2f", amount));
                admin.sendMessage("§7余额: §e" + String.format("%.2f", oldBal) + " → §a" + String.format("%.2f", newBal));
                admin.sendMessage("§7============== §6余额转账 §7==============");
                if (tp != null) {
                    tp.sendMessage("§7============== §a收到转账 §7==============");
                    tp.sendMessage("§a到账: §e$" + String.format("%.2f", amount));
                    tp.sendMessage("§7余额: §e" + String.format("%.2f", oldBal) + " → §a" + String.format("%.2f", newBal));
                    tp.sendMessage("§7来自: §e" + admin.getName());
                    tp.sendMessage("§7============== §a收到转账 §7==============");
                }
            }
        } catch (NumberFormatException e) {
            admin.sendMessage("§c格式错误，请输入 §a+数字 §7或 §c-数字");
        }
    }
    // ===== 替换整个 handleBondInput 方法 =====
    private void handleBondInput(Player admin,
                                 String msg,
                                 String target) {
        if (!msg.startsWith("+")
                && !msg.startsWith("-")) {
            admin.sendMessage(
                    "§c格式: §a+100 §7给债券"
                            + " / §c-50 §7扣债券");
            admin.sendMessage(
                    "§7可附加理由: §a+100 活动奖励");
            return;
        }
        try {
            String sign = msg.substring(0, 1);
            String rest = msg.substring(1).trim();

            // 拆分金额和理由
            String[] parts = rest.split("\\s+", 2);
            int amount = Integer.parseInt(parts[0]);
            String reason = parts.length >= 2
                    ? parts[1].trim()
                    : "管理员手动调整";

            if (amount <= 0) {
                admin.sendMessage("§c金额必须大于0");
                return;
            }
            String opName = admin.getName();
            String txType = "-".equals(sign)
                    ? BondManager.TX_ADMIN_DEDUCT
                    : BondManager.TX_ADMIN_GIVE;

            if ("-".equals(sign)) {
                if (plugin.getBonds()
                        .isFrozen(target)) {
                    admin.sendMessage("§c" + target
                            + " 账户已被冻结");
                    return;
                }
                int oldBal = plugin.getBonds()
                        .getBonds(target);
                if (plugin.getBonds().deductBonds(
                        target, amount, txType,
                        "", opName, reason)) {
                    int newBal = plugin.getBonds()
                            .getBonds(target);
                    admin.sendMessage(
                            "§7============== §c"
                                    + "债券扣款 §7"
                                    + "=============");
                    admin.sendMessage(
                            "§7目标: §e" + target);
                    admin.sendMessage("§c扣款: §c-"
                            + amount + " §6枚");
                    admin.sendMessage("§7理由: §f"
                            + reason);
                    admin.sendMessage("§7债券: §e"
                            + oldBal + " → §c"
                            + newBal);
                    admin.sendMessage(
                            "§7============== §c"
                                    + "债券扣款 §7"
                                    + "=============");
                    Player tp = plugin.getServer()
                            .getPlayerExact(target);
                    if (tp != null) {
                        tp.sendMessage(
                                "§7============== §c"
                                        + "债券扣款 §7"
                                        + "=============");
                        tp.sendMessage("§c扣款: §c-"
                                + amount + " §6枚");
                        tp.sendMessage("§7理由: §f"
                                + reason);
                        tp.sendMessage("§7债券: §e"
                                + oldBal + " → §c"
                                + newBal);
                        tp.sendMessage("§7操作者: §e"
                                + opName);
                        tp.sendMessage(
                                "§7============== §c"
                                        + "债券扣款 §7"
                                        + "=============");
                    }
                } else {
                    admin.sendMessage("§c" + target
                            + " 债券不足(当前: "
                            + oldBal + ")");
                }
            } else {
                if (plugin.getBonds()
                        .isFrozen(target)) {
                    admin.sendMessage("§c" + target
                            + " 账户已被冻结");
                    return;
                }
                int oldBal = plugin.getBonds()
                        .getBonds(target);
                plugin.getBonds().addBonds(target,
                        amount, txType,
                        "", opName, reason);
                int newBal = plugin.getBonds()
                        .getBonds(target);
                admin.sendMessage(
                        "§7============== §6"
                                + "债券转账 §7"
                                + "=============");
                admin.sendMessage(
                        "§7目标: §e" + target);
                admin.sendMessage("§a到账: §a+"
                        + amount + " §6枚");
                admin.sendMessage("§7理由: §f"
                        + reason);
                admin.sendMessage("§7债券: §e"
                        + oldBal + " → §a"
                        + newBal);
                admin.sendMessage(
                        "§7============== §6"
                                + "债券转账 §7"
                                + "=============");
                Player tp = plugin.getServer()
                        .getPlayerExact(target);
                if (tp != null) {
                    tp.sendMessage(
                            "§7============== §a"
                                    + "收到债券 §7"
                                    + "=============");
                    tp.sendMessage("§a到账: §a+"
                            + amount + " §6枚");
                    tp.sendMessage("§7理由: §f"
                            + reason);
                    tp.sendMessage("§7债券: §e"
                            + oldBal + " → §a"
                            + newBal);
                    tp.sendMessage("§7来自: §e"
                            + opName);
                    tp.sendMessage(
                            "§7============== §a"
                                    + "收到债券 §7"
                                    + "=============");
                }
            }
        } catch (NumberFormatException e) {
            admin.sendMessage("§c格式错误，请输入 "
                    + "§a+数字 §7或 §c-数字");
        }
    }





    public void cancelInput(String player) {
        pendingInputs.remove(player);
    }

    static class CDKInput {
        String type;
        String context;
        long time;
    }
}
