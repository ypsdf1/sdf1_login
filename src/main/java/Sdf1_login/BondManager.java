package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class BondManager {

    private final Main plugin;
    private Connection db;

    // ===== 交易监听器 =====
    public interface TransactionListener {
        void onTransactionLogged(String playerName, String type, int amount);
    }
    private final List<TransactionListener> transactionListeners = new CopyOnWriteArrayList<>();

    public void addTransactionListener(TransactionListener listener) {
        transactionListeners.add(listener);
    }

    private void notifyTransactionListeners(String playerName, String type, int amount) {
        for (TransactionListener listener : transactionListeners) {
            try {
                listener.onTransactionLogged(playerName, type, amount);
            } catch (Exception e) {
                plugin.getLogger().warning("[BondManager] 交易监听器异常: " + e.getMessage());
            }
        }
    }

    // ===== 交易类型常量 =====
    public static final String TX_ADMIN_GIVE   = "admin_give";
    public static final String TX_ADMIN_DEDUCT = "admin_deduct";
    public static final String TX_TRANSFER_OUT = "transfer_out";
    public static final String TX_TRANSFER_IN  = "transfer_in";
    public static final String TX_REDEEM       = "redeem";
    public static final String TX_SHOP_BUY     = "shop_buy";
    public static final String TX_DAILY_SIGN   = "daily_sign";
    public static final String TX_FREEZE       = "freeze";
    public static final String TX_UNFREEZE     = "unfreeze";
    public static final String TX_TICKET_REWARD = "ticket_reward";
    public static final String TX_TICKET_SERVICE = "ticket_service";


    public static final int MAX_WATER_DAYS = 14;
    public static final int ADMIN_MAX_WATER_DAYS = 30;

    public BondManager(Main plugin) {
        this.plugin = plugin;
        initDB();
    }

    // ======================== 数据库初始化 ========================

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    plugin.getDataFolder(), "bond.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");

            // 债券余额（含冻结状态）
            st.execute("CREATE TABLE IF NOT EXISTS bonds ("
                    + "player_name TEXT PRIMARY KEY,"
                    + "amount INTEGER DEFAULT 0,"
                    + "status TEXT DEFAULT 'normal')");

            // 兼容旧表
            try {
                st.execute("ALTER TABLE bonds "
                        + "ADD COLUMN status "
                        + "TEXT DEFAULT 'normal'");
            } catch (SQLException ignored) {}

            // 旧流水（保留兼容）
            st.execute("CREATE TABLE IF NOT EXISTS bond_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_name TEXT NOT NULL,"
                    + "amount INTEGER NOT NULL,"
                    + "reason TEXT DEFAULT '',"
                    + "time INTEGER DEFAULT 0)");

            // 新交易流水表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "bond_transaction ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_name TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "amount INTEGER NOT NULL,"
                    + "target_player TEXT DEFAULT '',"
                    + "operator TEXT DEFAULT '',"
                    + "reason TEXT DEFAULT '',"
                    + "balance_after INTEGER DEFAULT 0,"
                    + "time INTEGER DEFAULT 0)");

            try {
                st.execute("CREATE INDEX "
                        + "IF NOT EXISTS idx_tx_pt "
                        + "ON bond_transaction("
                        + "player_name, time)");
            } catch (SQLException ignored) {}
            try {
                st.execute("ALTER TABLE bond_transaction "
                        + "ADD COLUMN balance_before "
                        + "INTEGER DEFAULT 0");
            } catch (SQLException ignored) {}


            st.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[Bond] DB init failed: "
                            + e.getMessage(), e);
        }
    }

    // ======================== 账户状态 ========================

    public boolean isFrozen(String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT status FROM bonds "
                            + "WHERE player_name=?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String s = rs.getString("status");
                rs.close(); ps.close();
                return "frozen".equals(s);
            }
            rs.close(); ps.close();
        } catch (SQLException ignored) {}
        return false;
    }

    public String getAccountStatus(String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT status FROM bonds "
                            + "WHERE player_name=?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String s = rs.getString("status");
                rs.close(); ps.close();
                return s != null ? s : "normal";
            }
            rs.close(); ps.close();
        } catch (SQLException ignored) {}
        return "normal";
    }

    public void freezeAccount(String player) {
        ensureAccount(player);
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE bonds SET status='frozen' "
                            + "WHERE player_name=?");
            ps.setString(1, player);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void unfreezeAccount(String player) {
        ensureAccount(player);
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE bonds SET status='normal' "
                            + "WHERE player_name=?");
            ps.setString(1, player);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void ensureAccount(String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR IGNORE INTO bonds "
                            + "(player_name,amount,status) "
                            + "VALUES (?,0,'normal')");
            ps.setString(1, player);
            ps.executeUpdate(); ps.close();
        } catch (SQLException ignored) {}
    }

    // ======================== 余额查询 ========================

    public int getBonds(String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT amount FROM bonds "
                            + "WHERE player_name=?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            int v = 0;
            if (rs.next()) v = rs.getInt("amount");
            rs.close(); ps.close();
            return v;
        } catch (SQLException e) { return 0; }
    }

    /** 该玩家是否在 bond 表中有真实记录 */
    public boolean hasBondAccount(String player) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM bonds "
                            + "WHERE player_name=? "
                            + "AND amount > 0");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            boolean exists = rs.next();
            rs.close(); ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }

    // ======================== 增加债券 ========================

    public int addBonds(String player, int amount) {
        return addBondsInternal(player, amount,
                TX_ADMIN_GIVE, "", "",
                "管理员发放");
    }

    public int addBonds(String player, int amount,
                        String type,
                        String targetPlayer,
                        String operator,
                        String reason) {
        if (isFrozen(player)) return -1;
        return addBondsInternal(player, amount,
                type, targetPlayer, operator, reason);
    }

    private int addBondsInternal(String player,
                                 int amount,
                                 String type,
                                 String targetPlayer,
                                 String operator,
                                 String reason) {
        int before = getBonds(player);
        int after = before + amount;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO bonds "
                            + "(player_name,amount,status)"
                            + " VALUES (?,?, 'normal') "
                            + "ON CONFLICT(player_name) "
                            + "DO UPDATE SET amount=?");
            ps.setString(1, player);
            ps.setInt(2, after);
            ps.setInt(3, after);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        logTransaction(player, type, amount,
                targetPlayer, operator, reason,
                before, after);
        logLegacy(player, amount);
        return after;
    }

    // ======================== 扣除债券 ========================



    private boolean deductBondsInternal(String player,
                                        int amount,
                                        String type,
                                        String targetPlayer,
                                        String operator,
                                        String reason) {
        int before = getBonds(player);
        if (before < amount) return false;
        int after = before - amount;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE bonds SET amount=? "
                            + "WHERE player_name=?");
            ps.setInt(1, after);
            ps.setString(2, player);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        logTransaction(player, type, amount,
                targetPlayer, operator, reason,
                before, after);
        // 记录扣除流水，use positive amount, UI uses type to determine sign
        logLegacy(player, amount);
        return true;
    }

    // ======================== 流水写入 ========================

    private void logTransaction(String player,
                                String type,
                                int amount,
                                String targetPlayer,
                                String operator,
                                String reason,
                                int balanceBefore,
                                int balanceAfter) {
        try {
            // 合并模式：shop_buy 30秒内同商品合并
            if ("shop_buy".equals(type)
                    && reason != null
                    && !reason.isEmpty()) {
                long mergeWindow =
                        System.currentTimeMillis() - 30000L;
                PreparedStatement chk =
                        db.prepareStatement(
                                "SELECT id, amount "
                                        + "FROM bond_transaction "
                                        + "WHERE player_name=? "
                                        + "AND type=? "
                                        + "AND reason=? "
                                        + "AND time > ? "
                                        + "ORDER BY time DESC "
                                        + "LIMIT 1");
                chk.setString(1, player);
                chk.setString(2, type);
                chk.setString(3, reason);
                chk.setLong(4, mergeWindow);
                ResultSet rs = chk.executeQuery();
                if (rs.next()) {
                    int existId = rs.getInt("id");
                    int existAmt = rs.getInt("amount");
                    rs.close();
                    chk.close();
                    PreparedStatement upd =
                            db.prepareStatement(
                                    "UPDATE bond_transaction "
                                            + "SET amount=?, "
                                            + "balance_after=?, "
                                            + "time=? "
                                            + "WHERE id=?");
                    upd.setInt(1, existAmt + amount);
                    upd.setInt(2, balanceAfter);
                    upd.setLong(3,
                            System.currentTimeMillis());
                    upd.setInt(4, existId);
                    upd.executeUpdate();
                    upd.close();
                    return;
                }
                rs.close();
                chk.close();
            }

            // 原始插入
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO bond_transaction "
                            + "(player_name, type, amount,"
                            + " target_player, operator,"
                            + " reason, balance_before,"
                            + " balance_after, time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)");
            ps.setString(1, player);
            ps.setString(2, type);
            ps.setInt(3, amount);
            ps.setString(4,
                    targetPlayer != null ? targetPlayer : "");
            ps.setString(5,
                    operator != null ? operator : "");
            ps.setString(6,
                    reason != null ? reason : "");
            ps.setInt(7, balanceBefore);
            ps.setInt(8, balanceAfter);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        // ★ 通知监听器：交易已记录
        notifyTransactionListeners(player, type, amount);
    }


    // ======================== 扣除债券 ========================

    /** 完整版：含冻结检查 + 流水记录 */
    public boolean deductBonds(String player, int amount,
                               String type,
                               String targetPlayer,
                               String operator,
                               String reason) {
        if (isFrozen(player)) return false;
        return deductBondsInternal(player, amount,
                type, targetPlayer, operator, reason);
    }

    public boolean checkoutCart(String player,
                                String[] itemNames,
                                int[] subtotals) {
        int total = 0;
        for (int s : subtotals) total += s;
        if (isFrozen(player)) return false;

        int before = getBonds(player);
        if (before < total) return false;
        int after = before - total;

        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO bonds "
                            + "(player_name,amount,status) "
                            + "VALUES (?,?,'normal') "
                            + "ON CONFLICT(player_name) "
                            + "DO UPDATE SET amount=?");
            ps.setString(1, player);
            ps.setInt(2, after);
            ps.setInt(3, after);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        int running = before;
        for (int i = 0; i < itemNames.length; i++) {
            int next = running - subtotals[i];
            logTransaction(player, "shop_buy", subtotals[i],
                    "", "商店系统",
                    "购物车购买-" + itemNames[i],
                    running, next);
            running = next;
        }

        logLegacy(player, -total);
        return true;
    }

    public boolean setBonds(String player, int amount) {
        ensureAccount(player);
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO bonds "
                            + "(player_name,amount,status) "
                            + "VALUES (?,?, 'normal') "
                            + "ON CONFLICT(player_name) "
                            + "DO UPDATE SET amount=?");
            ps.setString(1, player);
            ps.setInt(2, amount);
            ps.setInt(3, amount);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
        return true;
    }

    // ======================== 转账 ========================

    public TransferResult transfer(String from,
                                   String to,
                                   int amount) {
        if (from.equalsIgnoreCase(to))
            return new TransferResult(false,
                    "不能给自己转账");
        if (isFrozen(from))
            return new TransferResult(false,
                    "你的账户已被冻结");
        if (isFrozen(to))
            return new TransferResult(false,
                    to + " 的账户已被冻结");
        if (isSameIP(from, to))
            return new TransferResult(false,
                    "不能与同IP玩家转账");

        if (!deductBondsInternal(from, amount,
                TX_TRANSFER_OUT, to, from,
                "转账给" + to))
            return new TransferResult(false, "债券不足");

        addBondsInternal(to, amount,
                TX_TRANSFER_IN, from, from,
                "收到来自" + from + "的转账");

        return new TransferResult(true, "转账成功");
    }

    // ======================== 同IP检测 ========================

    public boolean isSameIP(String p1, String p2) {
        String ip1 = getPlayerIPAny(p1);
        String ip2 = getPlayerIPAny(p2);
        if (ip1 == null || ip2 == null) return false;
        return ip1.equals(ip2);
    }

    /** 获取同IP下的所有其他在线玩家 */
    public List<String> getSameIPPlayers(String player) {
        String ip = getPlayerIPAny(player);
        if (ip == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (Player p :
                plugin.getServer().getOnlinePlayers()) {
            String pIp = plugin.getPlayerIP(p);
            if (pIp != null && pIp.equals(ip)
                    && !p.getName()
                    .equalsIgnoreCase(player)) {
                list.add(p.getName());
            }
        }
        return list;
    }

    private String getPlayerIPAny(String player) {
        // 在线：直接取
        Player p = plugin.getServer()
                .getPlayerExact(player);
        if (p != null) {
            String ip = plugin.getPlayerIP(p);
            if (ip != null) return ip;
        }
        // 离线：查主库
        try {
            Object v = plugin.getDb()
                    .getField(player, "last_login_ip");
            if (v != null) return v.toString();
        } catch (Exception ignored) {}
        try {
            Object v = plugin.getDb()
                    .getField(player, "register_ip");
            if (v != null) return v.toString();
        } catch (Exception ignored) {}
        return null;
    }

    // ======================== 流水查询 ========================

    public List<Map<String, Object>> getTransactions(
            String player, int days) {
        days = Math.min(days, MAX_WATER_DAYS);
        long cutoff = System.currentTimeMillis()
                - (long) days * 24 * 60 * 60 * 1000;
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM bond_transaction "
                            + "WHERE player_name=? "
                            + "AND time>=? "
                            + "ORDER BY time DESC");
            ps.setString(1, player);
            ps.setLong(2, cutoff);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("type", rs.getString("type"));
                row.put("amount",
                        rs.getInt("amount"));
                row.put("target_player",
                        rs.getString("target_player"));
                row.put("operator",
                        rs.getString("operator"));
                row.put("reason",
                        rs.getString("reason"));
                row.put("balance_after",
                        rs.getInt("balance_after"));
                row.put("balance_before", rs.getInt("balance_before"));
                row.put("time", rs.getLong("time"));
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }
// 放在 getTransactions 方法后面：

    /**
     * 获取该玩家全部流水（不限时间，供打印使用）
     */
    public List<Map<String, Object>>
    getAllTransactions(String player) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM bond_transaction "
                            + "WHERE player_name=? "
                            + "ORDER BY time ASC");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("player_name",
                        rs.getString("player_name"));
                row.put("type", rs.getString("type"));
                row.put("amount",
                        rs.getInt("amount"));
                row.put("target_player",
                        rs.getString("target_player"));
                row.put("operator",
                        rs.getString("operator"));
                row.put("reason",
                        rs.getString("reason"));
                row.put("balance_after",
                        rs.getInt("balance_after"));
                row.put("balance_before", rs.getInt("balance_before"));
                row.put("time", rs.getLong("time"));
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
// 放在 getAllTransactions 方法后面：

    /**
     * 获取所有有流水记录的玩家名（去重）
     */
    public List<String> getAllPlayerNames() {
        List<String> list = new ArrayList<>();
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT player_name "
                            + "FROM bond_transaction "
                            + "ORDER BY player_name ASC");
            while (rs.next()) {
                list.add(rs.getString("player_name"));
            }
            rs.close(); st.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, Object>>
    getTransferTransactions(
            String player, int days) {
        days = Math.min(days, MAX_WATER_DAYS);
        long cutoff = System.currentTimeMillis()
                - (long) days * 24 * 60 * 60 * 1000;
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM bond_transaction "
                            + "WHERE player_name=? "
                            + "AND time>=? "
                            + "AND (type=? OR type=?) "
                            + "ORDER BY time DESC");
            ps.setString(1, player);
            ps.setLong(2, cutoff);
            ps.setString(3, TX_TRANSFER_OUT);
            ps.setString(4, TX_TRANSFER_IN);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("type", rs.getString("type"));
                row.put("amount",
                        rs.getInt("amount"));
                row.put("target_player",
                        rs.getString("target_player"));
                row.put("reason",
                        rs.getString("reason"));
                row.put("balance_after",
                        rs.getInt("balance_after"));
                row.put("balance_before", rs.getInt("balance_before"));
                row.put("time", rs.getLong("time"));
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }


    private void logLegacy(String player, int amount) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO bond_log "
                            + "(player_name,amount,time) "
                            + "VALUES (?,?,?)");
            ps.setString(1, player);
            ps.setInt(2, amount);
            ps.setLong(3,
                    System.currentTimeMillis());
            ps.executeUpdate(); ps.close();
        } catch (SQLException ignored) {}
    }

    public Map<String, Object> getLog(
            String player, int limit) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("balance", getBonds(player));
        return info;
    }

    // ======================== 转账结果 ========================

    public static class TransferResult {
        public final boolean success;
        public final String message;
        public TransferResult(boolean success,
                              String message) {
            this.success = success;
            this.message = message;
        }
    }

    // ======================== 同步用：获取指定时间之后的交易 ========================

    /**
     * 获取指定时间之后的所有交易记录（用于推送到Web端）
     * 使用时间而非ID追踪，确保shop_buy合并后的UPDATE记录也能被同步
     * @param afterTime 上次同步的最晚时间（毫秒），0表示从头开始
     * @return 交易记录列表
     */
    public List<Map<String, Object>> getTransactionsAfterTime(long afterTime) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM bond_transaction "
                            + "WHERE time > ? "
                            + "ORDER BY time ASC "
                            + "LIMIT 200");
            ps.setLong(1, afterTime);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("player_name", rs.getString("player_name"));
                row.put("type", rs.getString("type"));
                row.put("amount", rs.getInt("amount"));
                row.put("target_player", rs.getString("target_player"));
                row.put("operator", rs.getString("operator"));
                row.put("reason", rs.getString("reason"));
                row.put("balance_before", rs.getInt("balance_before"));
                row.put("balance_after", rs.getInt("balance_after"));
                row.put("time", rs.getLong("time"));
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }
}
