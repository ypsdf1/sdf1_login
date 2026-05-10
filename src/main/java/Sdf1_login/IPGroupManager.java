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

    /**
     * 检查该IP是否还能注册新账号
     */
    public boolean canRegister(String ip) {
        if (maxAccounts <= 0) return true;
        if (ip == null || ip.isEmpty()) return true;
        return queryAccountsByIP(ip).size() < maxAccounts;
    }

    /**
     * 获取该IP下已注册的账号数量
     */
    public int getAccountCount(String ip) {
        if (ip == null || ip.isEmpty()) return 0;
        return queryAccountsByIP(ip).size();
    }

    /**
     * 获取该IP下所有账号名
     */
    public List<String> getAccounts(String ip) {
        return queryAccountsByIP(ip);
    }

    /**
     * 直接通过数据库连接查询指定IP下的所有账号
     */
    private List<String> queryAccountsByIP(String ip) {
        List<String> accounts = new ArrayList<>();
        Connection conn = plugin.getDb().getDb();
        if (conn == null) return accounts;
        try {
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
        } catch (Exception e) {
            e.printStackTrace();
        }
        return accounts;
    }
}
