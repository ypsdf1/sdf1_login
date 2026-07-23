package Sdf1_login;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class IPGroupManager {

    private final Main plugin;
    private final int maxAccounts;

    public IPGroupManager(Main plugin, int maxAccounts) {
        this.plugin = plugin;
        this.maxAccounts = maxAccounts;
    }

    public boolean canRegister(String ip) {
        if (maxAccounts <= 0) return true;
        if (ip == null || ip.isEmpty()) return true;
        int count = getAccountCount(ip);
        boolean can = count < maxAccounts;
        plugin.getLogger().info(
                "[IP限制] IP=" + ip
                        + " 已注册=" + count
                        + " 上限=" + maxAccounts
                        + " 允许=" + can);
        return can;
    }

    public int getMaxAccounts() {
        return maxAccounts;
    }

    public int getAccountCount(String ip) {
        if (ip == null || ip.isEmpty()) return 0;
        return queryAccountsByIP(ip).size();
    }

    public List<String> getAccounts(String ip) {
        return queryAccountsByIP(ip);
    }

    private List<String> queryAccountsByIP(String ip) {
        List<String> accounts = new ArrayList<>();
        Connection conn = plugin.getDb().getDb();
        if (conn == null) return accounts;
        try {
            // 主查询：ip_address 列
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_name FROM users "
                            + "WHERE ip_address = ?");
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                accounts.add(
                        rs.getString("player_name"));
            }
            rs.close();
            ps.close();

            // 兜底：如果主列没数据，查 last_login_ip
            if (accounts.isEmpty()) {
                PreparedStatement ps2 = conn.prepareStatement(
                        "SELECT player_name FROM users "
                                + "WHERE last_login_ip = ? "
                                + "AND (ip_address IS NULL "
                                + "OR ip_address = '' "
                                + "OR ip_address != ?)");
                ps2.setString(1, ip);
                ps2.setString(2, ip);
                ResultSet rs2 = ps2.executeQuery();
                while (rs2.next()) {
                    String name = rs2.getString("player_name");
                    if (!accounts.contains(name))
                        accounts.add(name);
                }
                rs2.close();
                ps2.close();
            }

            // 兜底2：查 register_ip
            if (accounts.isEmpty()) {
                PreparedStatement ps3 = conn.prepareStatement(
                        "SELECT player_name FROM users "
                                + "WHERE register_ip = ? "
                                + "AND (ip_address IS NULL "
                                + "OR ip_address = '') "
                                + "AND (last_login_ip IS NULL "
                                + "OR last_login_ip = '' "
                                + "OR last_login_ip != ?)");
                ps3.setString(1, ip);
                ps3.setString(2, ip);
                ResultSet rs3 = ps3.executeQuery();
                while (rs3.next()) {
                    String name = rs3.getString("player_name");
                    if (!accounts.contains(name))
                        accounts.add(name);
                }
                rs3.close();
                ps3.close();
            }

            plugin.getLogger().info(
                    "[IP限制] 查询IP=" + ip
                            + " 结果数=" + accounts.size()
                            + " 账号=" + accounts);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return accounts;
    }
}
