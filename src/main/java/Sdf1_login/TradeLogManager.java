package Sdf1_login;

import java.io.File;
import java.sql.*;

/**
 * 交易记录数据库（2026-10-11 新增）。
 *
 * <p>独立 db（交易记录/shop_trades.db），专门存放三类数据：
 * <ul>
 *   <li>{@code normal_shop_orders}  —— 正常商店的交易订单（直购 + 购物车结账）</li>
 *   <li>{@code secondhand_orders}   —— 二手商店的交易订单（含手续费与实际到手）</li>
 *   <li>{@code player_selling_prefs}—— 玩家的出售偏好（奸商模式=买方出 / 正常模式=卖方出）</li>
 * </ul>
 *
 * <p>铁律：db 文件创建即指向子目录，绝不人工移动/改名（WAL 三件套同进同退）。
 */
public class TradeLogManager {

    private final Main plugin;
    private Connection db;
    private boolean ready = false;

    public TradeLogManager(Main plugin) {
        this.plugin = plugin;
        init();
    }

    private synchronized void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            // ★ 创建即归位到子目录（2026-10-11 db 铁律：路径直接指向目标目录）
            File dir = new File(plugin.getDataFolder(), "交易记录");
            dir.mkdirs();
            File dbFile = new File(dir, "shop_trades.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");

                st.execute("CREATE TABLE IF NOT EXISTS normal_shop_orders ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "buyer TEXT NOT NULL,"
                        + "item_name TEXT NOT NULL,"
                        + "amount INTEGER NOT NULL,"
                        + "unit_price INTEGER NOT NULL,"
                        + "total_bonds INTEGER NOT NULL,"
                        + "discount_pct INTEGER DEFAULT 0,"
                        + "source TEXT DEFAULT 'direct',"
                        + "created_at INTEGER NOT NULL)");

                st.execute("CREATE TABLE IF NOT EXISTS secondhand_orders ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "listing_id INTEGER NOT NULL,"
                        + "buyer TEXT NOT NULL,"
                        + "seller TEXT NOT NULL,"
                        + "item_name TEXT NOT NULL,"
                        + "amount INTEGER NOT NULL,"
                        + "price INTEGER NOT NULL,"
                        + "fee INTEGER DEFAULT 0,"
                        + "fee_mode TEXT DEFAULT 'SELLER',"
                        + "buyer_paid INTEGER NOT NULL,"
                        + "seller_received INTEGER NOT NULL,"
                        + "created_at INTEGER NOT NULL)");

                st.execute("CREATE TABLE IF NOT EXISTS player_selling_prefs ("
                        + "player_name TEXT PRIMARY KEY,"
                        + "fee_mode TEXT NOT NULL,"
                        + "updated_at INTEGER NOT NULL)");
            }
            ready = true;
            plugin.getLogger().info("[交易记录] shop_trades.db 初始化完成: "
                    + dbFile.getAbsolutePath());
        } catch (Exception e) {
            ready = false;
            plugin.getLogger().warning("[交易记录] 初始化失败: " + e.getMessage());
        }
    }

    // ==================== 正常商店订单 ====================

    /**
     * 记录一笔正常商店交易订单。
     *
     * @param source direct=直购 / cart=购物车结账
     */
    public void recordNormalOrder(String buyer, String itemName, int amount,
                                  int unitPrice, int totalBonds,
                                  int discountPct, String source) {
        if (!ready) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO normal_shop_orders "
                        + "(buyer, item_name, amount, unit_price, total_bonds, "
                        + "discount_pct, source, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, nvl(buyer));
            ps.setString(2, nvl(itemName));
            ps.setInt(3, amount);
            ps.setInt(4, unitPrice);
            ps.setInt(5, totalBonds);
            ps.setInt(6, discountPct);
            ps.setString(7, nvl(source));
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("[交易记录] 正常订单写入失败: " + e.getMessage());
        }
    }

    // ==================== 二手商店订单 ====================

    /**
     * 记录一笔二手交易订单（含手续费与双方实际金额）。
     *
     * @param feeMode BUYER=奸商模式（买方承担手续费）/ SELLER=正常模式（卖方承担）
     */
    public void recordSecondHandOrder(long listingId, String buyer, String seller,
                                      String itemName, int amount, int price,
                                      int fee, String feeMode,
                                      int buyerPaid, int sellerReceived) {
        if (!ready) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO secondhand_orders "
                        + "(listing_id, buyer, seller, item_name, amount, price, "
                        + "fee, fee_mode, buyer_paid, seller_received, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, listingId);
            ps.setString(2, nvl(buyer));
            ps.setString(3, nvl(seller));
            ps.setString(4, nvl(itemName));
            ps.setInt(5, amount);
            ps.setInt(6, price);
            ps.setInt(7, fee);
            ps.setString(8, nvl(feeMode));
            ps.setInt(9, buyerPaid);
            ps.setInt(10, sellerReceived);
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("[交易记录] 二手订单写入失败: " + e.getMessage());
        }
    }

    // ==================== 玩家出售偏好 ====================

    /** 取玩家出售偏好；无记录返回 null（由调用方回退全局配置）。 */
    public String getSellingPref(String player) {
        if (!ready || player == null) return null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT fee_mode FROM player_selling_prefs WHERE player_name = ?")) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[交易记录] 读取偏好失败: " + e.getMessage());
        }
        return null;
    }

    /** 写入/覆盖玩家出售偏好（BUYER=奸商模式 / SELLER=正常模式）。 */
    public void setSellingPref(String player, String feeMode) {
        if (!ready || player == null || feeMode == null) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO player_selling_prefs "
                        + "(player_name, fee_mode, updated_at) VALUES (?, ?, ?)")) {
            ps.setString(1, player);
            ps.setString(2, feeMode);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("[交易记录] 写入偏好失败: " + e.getMessage());
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    public synchronized void close() {
        try {
            if (db != null) db.close();
        } catch (Exception ignored) {
        }
    }
}
