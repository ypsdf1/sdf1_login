package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
                    "[CDK] DB init failed: "
                            + e.getMessage(), e);
        }
    }

    // ===== CDK兑换（供SDF1调用） =====

    /**
     * 兑换CDK，返回结果字符串
     * @return "success:金额:余额前:余额后"
     *         或 "fail:原因"
     */
    public String redeem(String code, String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT amount,type,used FROM cdk "
                            + "WHERE code=?");
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                rs.close(); ps.close();
                return "fail:not_found";
            }
            int amount = rs.getInt("amount");
            String type = rs.getString("type");
            int used = rs.getInt("used");
            rs.close(); ps.close();

            if (used == 1)
                return "fail:already_used";

            if (plugin.getBonds()
                    .isFrozen(player))
                return "fail:frozen";

            PreparedStatement up = db.prepareStatement(
                    "UPDATE cdk SET used=1,"
                            + "used_by=?,used_time=? "
                            + "WHERE code=?");
            up.setString(1, player);
            up.setLong(2,
                    System.currentTimeMillis());
            up.setString(3, code);
            up.executeUpdate(); up.close();

            if ("bond".equals(type)) {
                int bef = plugin.getBonds()
                        .getBonds(player);
                plugin.getBonds().addBonds(player,
                        amount,
                        BondManager.TX_REDEEM,
                        "", "CDK系统",
                        "CDK兑换: " + code);
                int aft = plugin.getBonds()
                        .getBonds(player);
                return "success:" + amount
                        + ":" + bef + ":" + aft;
            }
            return "fail:unknown_type";
        } catch (SQLException e) {
            return "fail:" + e.getMessage();
        }
    }

    // ===== 聊天输入系统 =====

    public void requestInput(Player p,
                             String type,
                             String context) {
        CDKInput input = new CDKInput();
        input.type = type;
        input.context = context;
        input.time = System.currentTimeMillis();
        pendingInputs.put(p.getName(), input);
    }

    public boolean isListening(String playerName) {
        return pendingInputs
                .containsKey(playerName);
    }

    public void onChat(Player p, String msg) {
        CDKInput input = pendingInputs
                .remove(p.getName());
        if (input == null) return;

        if (System.currentTimeMillis()
                - input.time > 60000) {
            p.sendMessage("§c输入超时");
            return;
        }

        if ("cdk".equals(input.type)) {
            String result = redeem(
                    msg.trim(), p.getName());
            if (result != null
                    && result.startsWith("success:")) {
                String[] parts = result.split(":");
                int amt = Integer.parseInt(parts[1]);
                int bef = Integer.parseInt(parts[2]);
                int aft = Integer.parseInt(parts[3]);
                p.sendMessage("§e§l[CDK] §f兑换成功！"
                        + "获得 §c" + amt + "§f债券");
                p.sendMessage("§7余额: §e" + bef
                        + " §7-> §a" + aft);
            } else {
                p.sendMessage("§c§l[CDK] §f"
                        + (result != null
                        ? result.replace("fail:", "")
                        : "无效的兑换码"));
            }
            return;
        }

        if ("econ".equals(input.type)) {
            handleEconInput(p, msg.trim(),
                    input.context);
            return;
        }

        if ("bond".equals(input.type)) {
            handleBondInput(p, msg.trim(),
                    input.context);
            return;
        }

        if ("points".equals(input.type)) {
            handlePointsInput(p, msg.trim(),
                    input.context);
        }
    }

    private void handleEconInput(Player admin,
                                 String msg,
                                 String target) {
        // Economy已移除，所有经济操作改用债券系统
        admin.sendMessage("§c[经济操作] 功能已移除，请使用债券系统");
    }

    private void handleBondInput(Player admin,
                                 String msg,
                                 String target) {
        if (!msg.startsWith("+")
                && !msg.startsWith("-")) {
            admin.sendMessage("§c格式: §a+100"
                    + " §7或 §c-50");
            return;
        }
        try {
            String sign = msg.substring(0, 1);
            String rest =
                    msg.substring(1).trim();
            String[] parts =
                    rest.split("\\s+", 2);
            int amount = Integer.parseInt(parts[0]);
            String reason = parts.length >= 2
                    ? parts[1].trim()
                    : "管理员手动调整";
            if (amount <= 0) {
                admin.sendMessage("§c金额必须>0");
                return;
            }
            String opName = admin.getName();
            String txType = "-".equals(sign)
                    ? BondManager.TX_ADMIN_DEDUCT
                    : BondManager.TX_ADMIN_GIVE;

            if ("-".equals(sign)) {
                if (plugin.getBonds()
                        .isFrozen(target)) {
                    admin.sendMessage("§c账户冻结");
                    return;
                }
                if (plugin.getBonds().deductBonds(
                        target, amount, txType,
                        "", opName, reason)) {
                    admin.sendMessage("§a扣除成功");
                } else {
                    admin.sendMessage("§c债券不足");
                }
            } else {
                if (plugin.getBonds()
                        .isFrozen(target)) {
                    admin.sendMessage("§c账户冻结");
                    return;
                }
                plugin.getBonds().addBonds(target,
                        amount, txType,
                        "", opName, reason);
                admin.sendMessage("§a发放成功");
            }
        } catch (NumberFormatException e) {
            admin.sendMessage("§c格式错误");
        }
    }

    private void handlePointsInput(Player admin,
                                   String msg,
                                   String target) {
        if (!msg.startsWith("+")
                && !msg.startsWith("-")) {
            admin.sendMessage("§c格式: §a+100"
                    + " §7或 §c-50");
            return;
        }
        try {
            String sign = msg.substring(0, 1);
            String rest =
                    msg.substring(1).trim();
            int amount = Integer.parseInt(rest);
            if (amount <= 0) {
                admin.sendMessage("§c金额必须>0");
                return;
            }
            if ("-".equals(sign)) {
                if (plugin.getDb().deductPoints(
                        target, amount)) {
                    admin.sendMessage("§a扣除成功");
                } else {
                    admin.sendMessage("§c积分不足");
                }
            } else {
                plugin.getDb().addPoints(
                        target, amount);
                admin.sendMessage("§a发放成功");
            }
        } catch (NumberFormatException e) {
            admin.sendMessage("§c格式错误");
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
