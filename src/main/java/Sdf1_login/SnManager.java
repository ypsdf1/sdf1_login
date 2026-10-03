package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeBookClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品 SN（序列号）防刷系统。
 * <p>
 * ★ SN 组成：玩家名 + 10位 unix秒 + 玩家专属2位识别码，例如 test0000000000AB。
 * <p>
 * ★ 管控的 4 类插件自定义物品：
 * menu_snowball（雪球菜单，含自定义图标）/ land_wand（区域选择工具·烈焰棒）/
 * echo_shard（回声碎片）/ pvp_tool（PVP 圈地棒）。
 * <p>
 * ★ 生命周期：
 * 申领（去重 + 冷静期校验）→ 打标（PDC sdf1_sn）→ 出入库登记（容器坐标 + 领地）
 * → 拾取探查（SN 归属不符写队列）→ 销毁解绑 / 非法途径永久绑定 / 注销冷静期。
 */
public class SnManager implements Listener {

    private final Main plugin;

    // ==================== 常量 ====================

    /** SN 写在物品上的 PDC key（4 类物品统一） */
    public static final String PDC_SN = "sdf1_sn";
    /** 区域选择工具的标识 key（历史物品无 PDC，新发补打） */
    public static final String PDC_WAND = "sdf1_wand";

    public static final String TYPE_MENU = "menu_snowball";
    public static final String TYPE_WAND = "land_wand";
    public static final String TYPE_ECHO = "echo_shard";
    public static final String TYPE_PVP = "pvp_tool";

    /** 有效状态：仍占用申领名额（未注销、未销毁） */
    public static final String ST_ACTIVE = "active";
    /** 已销毁解绑（可重新申领） */
    public static final String ST_DESTROYED = "destroyed";
    /** 已注销（冷静期从这里算） */
    public static final String ST_CANCELLED = "cancelled";
    /** 报失处理中 */
    public static final String ST_LOST = "lost";
    /** 非法途径处理，永久绑定（不可再申领同类） */
    public static final String ST_ILLEGAL = "illegal";
    /** 已补发（旧 SN 作废） */
    public static final String ST_REISSUED = "reissued";

    /** 注销冷静期：1 小时 */
    public static final long COOLDOWN_MS = 60L * 60L * 1000L;

    /**
     * 脱离宽限期：扔在地上超过 10 分钟没人捡，才算脱离玩家本人管控
     * （10 分钟内捡回不算脱离，计时清零）。进本插件垃圾箱无宽限，投入即脱离。
     */
    public static final long DETACH_GRACE_MS = 10L * 60L * 1000L;

    /** 脱离自身管控硬性阈值：12 小时，超时 = 彻底丢失，可注销 / 补发 */
    public static final long CUSTODY_DETACH_MS = 12L * 60L * 60L * 1000L;

    /** 4 类物品的中文名（提示与报表用） */
    private static final Map<String, String> TYPE_CN = new LinkedHashMap<>();

    static {
        TYPE_CN.put(TYPE_MENU, "雪球菜单");
        TYPE_CN.put(TYPE_WAND, "区域选择工具");
        TYPE_CN.put(TYPE_ECHO, "回声碎片");
        TYPE_CN.put(TYPE_PVP, "PVP圈地棒");
    }

    /** 拾取探查只认这 4 类 */
    private static final Set<String> WATCH_TYPES = new HashSet<>();

    static {
        WATCH_TYPES.add(TYPE_MENU);
        WATCH_TYPES.add(TYPE_WAND);
        WATCH_TYPES.add(TYPE_ECHO);
        WATCH_TYPES.add(TYPE_PVP);
    }

    /** 最近一次报错限流，避免刷日志 */
    private final Map<String, Long> errThrottle = new ConcurrentHashMap<>();

    /** 作废实物拦截提示限流（玩家名 -> 上次提示时间） */
    private final Map<String, Long> deadMsgAt = new ConcurrentHashMap<>();

    /** PHP 命令执行结果缓冲（id + \u0001 + result），由 tickSync 回传 */
    private final List<String> ackBuf = new ArrayList<String>();

    /** 目录快照节流计数（每 3 轮 tickSync 推一次全量） */
    private int catalogTick = 0;

    public SnManager(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 建表 ====================

    /** 建表（幂等，onEnable 调用） */
    public void init() {
        try {
            Connection db = plugin.getDb().getConnection();
            Statement st = db.createStatement();

            st.execute("CREATE TABLE IF NOT EXISTS item_sn ("
                    + "sn TEXT PRIMARY KEY,"
                    + "item_type TEXT NOT NULL,"
                    + "owner TEXT NOT NULL,"
                    + "owner_uuid TEXT DEFAULT '',"
                    + "player_code TEXT DEFAULT '',"
                    + "issue_time INTEGER NOT NULL,"
                    + "status TEXT DEFAULT 'active',"
                    + "loc_type TEXT DEFAULT 'player',"
                    + "loc_player TEXT DEFAULT '',"
                    + "loc_world TEXT DEFAULT '',"
                    + "loc_x INTEGER DEFAULT 0,"
                    + "loc_y INTEGER DEFAULT 0,"
                    + "loc_z INTEGER DEFAULT 0,"
                    + "container_type TEXT DEFAULT '',"
                    + "in_land INTEGER DEFAULT 0,"
                    + "land_name TEXT DEFAULT '',"
                    + "last_seen INTEGER DEFAULT 0,"
                    + "cancel_time INTEGER DEFAULT 0,"
                    + "lost_count INTEGER DEFAULT 0,"
                    + "lost_first INTEGER DEFAULT 0,"
                    + "lost_last INTEGER DEFAULT 0,"
                    + "lost_state TEXT DEFAULT '',"
                    + "bind_reason TEXT DEFAULT '',"
                    + "bind_time INTEGER DEFAULT 0,"
                    + "remark TEXT DEFAULT '',"
                    // 脱离自身管控的起始时刻（毫秒；0 = 当前仍在本人管辖内）
                    + "detached_at INTEGER DEFAULT 0,"
                    // 登记容器是否落在"本人名下领地"里（= 自己的箱子）
                    + "own_chest INTEGER DEFAULT 0"
                    + ")");
            // 老库补列：注销 / 报失 / 补办三条件判定的数据基础
            try {
                st.execute("ALTER TABLE item_sn ADD COLUMN detached_at INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
            try {
                st.execute("ALTER TABLE item_sn ADD COLUMN own_chest INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }

            st.execute("CREATE TABLE IF NOT EXISTS sn_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "sn TEXT NOT NULL,"
                    + "item_type TEXT DEFAULT '',"
                    + "action TEXT NOT NULL,"
                    + "player TEXT DEFAULT '',"
                    + "detail TEXT DEFAULT '',"
                    + "time INTEGER NOT NULL"
                    + ")");

            // 出入库登记：容器坐标 / 领地 / 操作人
            st.execute("CREATE TABLE IF NOT EXISTS sn_stock_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "sn TEXT NOT NULL,"
                    + "item_type TEXT DEFAULT '',"
                    + "action TEXT NOT NULL,"
                    + "world TEXT DEFAULT '',"
                    + "x INTEGER DEFAULT 0,"
                    + "y INTEGER DEFAULT 0,"
                    + "z INTEGER DEFAULT 0,"
                    + "container_type TEXT DEFAULT '',"
                    + "in_land INTEGER DEFAULT 0,"
                    + "land_name TEXT DEFAULT '',"
                    + "player TEXT DEFAULT '',"
                    + "time INTEGER NOT NULL"
                    + ")");

            // 玩家专属识别码（SN 末 2 位）
            st.execute("CREATE TABLE IF NOT EXISTS sn_player_code ("
                    + "player TEXT PRIMARY KEY,"
                    + "code TEXT NOT NULL,"
                    + "created_at INTEGER DEFAULT 0"
                    + ")");

            // 注销冷静期
            st.execute("CREATE TABLE IF NOT EXISTS sn_cooldown ("
                    + "player TEXT PRIMARY KEY,"
                    + "until INTEGER DEFAULT 0"
                    + ")");

            // Java -> PHP 待同步队列（拾取归属异常等）
            st.execute("CREATE TABLE IF NOT EXISTS sn_queue ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "action TEXT NOT NULL,"
                    + "sn TEXT DEFAULT '',"
                    + "player TEXT DEFAULT '',"
                    + "detail TEXT DEFAULT '',"
                    + "created_at INTEGER DEFAULT 0,"
                    + "synced INTEGER DEFAULT 0"
                    + ")");

            // PHP -> Java 命令队列（注销 / 补发 / 报失核查）
            st.execute("CREATE TABLE IF NOT EXISTS sn_commands ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "cmd TEXT NOT NULL,"
                    + "sn TEXT DEFAULT '',"
                    + "player TEXT DEFAULT '',"
                    + "item_type TEXT DEFAULT '',"
                    + "reason TEXT DEFAULT '',"
                    + "created_at INTEGER DEFAULT 0,"
                    + "status TEXT DEFAULT 'pending',"
                    + "result TEXT DEFAULT '',"
                    + "done_at INTEGER DEFAULT 0"
                    + ")");

            st.close();
            // ★ 无SN旧品自动回收 / 作废实物回收：每 60 秒清点在线玩家
            startAutoSweepTask();
            // ★ 脱离管控计时巡检：补 detached_at + 超时丢失留痕（每 5 分钟）
            startCustodySweepTask();
        } catch (SQLException e) {
            plugin.getLogger().severe("[SN] 建表失败: " + e.getMessage());
        }
    }

    // ==================== 物品识别 ====================

    /**
     * 判断物品是否属于 4 类插件自定义物品，返回类型名；不是则 null。
     * 优先认 PDC，历史物品（无 PDC 的烈焰棒）回落到名称/lore 判定。
     */
    public String detectType(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        NamespacedKey key = new NamespacedKey(plugin, PDC_SN);
        // 已打 SN 的直接按类型段识别（remark 之外，类型存在 DB；这里用 PDC 类型标记）
        String typed = meta.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "sdf1_sn_type"),
                        PersistentDataType.STRING);
        if (typed != null && WATCH_TYPES.contains(typed)) return typed;

        if (meta.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "sdf1_echo_shard"),
                PersistentDataType.BYTE)) return TYPE_ECHO;

        if (meta.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "pvp_tool"),
                PersistentDataType.STRING)) return TYPE_PVP;

        if (meta.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "menu_trigger"),
                        PersistentDataType.STRING)
                || meta.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "custom_menu_icon"),
                        PersistentDataType.STRING)) return TYPE_MENU;

        // 历史雪球菜单：lore 带 sdf1_menu 标记
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (String line : lore) {
                    if (line != null && line.contains("sdf1_menu"))
                        return TYPE_MENU;
                }
            }
        }

        // 历史区域选择工具（烈焰棒）：无 PDC，靠显示名
        if (item.getType() == Material.BLAZE_ROD && meta.hasDisplayName()
                && meta.getDisplayName().contains("区域选择工具"))
            return TYPE_WAND;

        // 历史 PVP 圈地棒
        if (item.getType() == Material.BLAZE_ROD && meta.hasDisplayName()
                && meta.getDisplayName().contains("[PVP]"))
            return TYPE_PVP;

        return null;
    }

    /** 读物品上的 SN，没有则 null */
    public String readSn(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, PDC_SN),
                        PersistentDataType.STRING);
    }

    /** 给物品写 SN（同时补写类型标记与 lore 行） */
    public void writeSn(ItemStack item, String sn, String itemType) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, PDC_SN),
                PersistentDataType.STRING, sn);
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "sdf1_sn_type"),
                PersistentDataType.STRING, itemType);
        // 区域选择工具补 PDC 标识，便于后续识别
        if (TYPE_WAND.equals(itemType)) {
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, PDC_WAND),
                    PersistentDataType.BYTE, (byte) 1);
        }
        List<String> lore = meta.hasLore()
                ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        boolean has = false;
        for (String l : lore) {
            if (l != null && l.contains("SN: ")) { has = true; break; }
        }
        if (!has) {
            lore.add("§8SN: " + sn);
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
    }

    /** 是否为受 SN 管控的 4 类物品（已打标或属于 4 类） */
    public boolean isSnItem(ItemStack item) {
        if (item == null) return false;
        if (readSn(item) != null) return true;
        return detectType(item) != null;
    }

    // ==================== 申领 ====================

    /** 物品中文名 */
    public String typeName(String itemType) {
        return TYPE_CN.getOrDefault(itemType, itemType);
    }

    /** 玩家的 2 位专属识别码（首次申领生成并持久化） */
    public synchronized String getPlayerCode(String player) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT code FROM sn_player_code WHERE player = ?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String c = rs.getString(1);
                rs.close();
                ps.close();
                if (c != null && c.length() == 2) return c;
            }
            rs.close();
            ps.close();

            String code = randomCode();
            PreparedStatement ins = db.prepareStatement(
                    "INSERT OR REPLACE INTO sn_player_code"
                            + " (player, code, created_at) VALUES (?,?,?)");
            ins.setString(1, player);
            ins.setString(2, code);
            ins.setLong(3, System.currentTimeMillis());
            ins.executeUpdate();
            ins.close();
            return code;
        } catch (SQLException e) {
            throttleErr("code:" + e.getMessage());
            // 兜底：用玩家名哈希取 2 位，保证不阻断发放
            int h = Math.abs(player.hashCode());
            return "" + (char) ('A' + (h % 26))
                    + (char) ('A' + ((h / 26) % 26));
        }
    }

    private String randomCode() {
        final String cs = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random r = new java.util.Random();
        return "" + cs.charAt(r.nextInt(cs.length()))
                + cs.charAt(r.nextInt(cs.length()));
    }

    /** 玩家某类物品的有效 SN（占用申领名额的），没有则 null */
    public Map<String, Object> findActive(String player, String itemType) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM item_sn WHERE owner = ? AND item_type = ?"
                            + " AND status IN (?,?,?)"
                            + " ORDER BY issue_time DESC LIMIT 1");
            ps.setString(1, player);
            ps.setString(2, itemType);
            ps.setString(3, ST_ACTIVE);
            ps.setString(4, ST_LOST);
            ps.setString(5, ST_ILLEGAL);
            ResultSet rs = ps.executeQuery();
            Map<String, Object> row = rsToMap(rs);
            rs.close();
            ps.close();
            return row;
        } catch (SQLException e) {
            throttleErr("findActive:" + e.getMessage());
            return null;
        }
    }

    /** 冷静期截止时间戳，0 表示无 */
    public long getCooldownUntil(String player) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT until FROM sn_cooldown WHERE player = ?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            long until = rs.next() ? rs.getLong(1) : 0L;
            rs.close();
            ps.close();
            return until;
        } catch (SQLException e) {
            throttleErr("cd:" + e.getMessage());
            return 0L;
        }
    }

    /** 注销冷静期剩余分钟数，0 = 不在冷静期 */
    public long cooldownMinutesLeft(String player) {
        long cd = getCooldownUntil(player);
        long now = System.currentTimeMillis();
        return cd > now ? (cd - now + 59999) / 60000 : 0L;
    }

    /** 启动 1 小时冷静期 */
    public void startCooldown(String player) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO sn_cooldown (player, until)"
                            + " VALUES (?,?)");
            ps.setString(1, player);
            ps.setLong(2, System.currentTimeMillis() + COOLDOWN_MS);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("cdw:" + e.getMessage());
        }
    }

    /**
     * 申领：校验冷静期与"同种类仅 1 个"，通过则登记 SN 并返回。
     * 被拒时给玩家发原因并返回 null。
     *
     * @param p        申领玩家
     * @param itemType 物品类型
     * @param what     物品展示名（提示用）
     */
    /**
     * 是否被申领管控挡住：处于注销冷静期，或同种类已有有效登记。
     * 发放入口用它做"1 人同种类仅 1 个"的前置判断。
     *
     * @return true = 不允许再发放
     */
    public boolean isBlocked(Player p, String itemType) {
        if (p == null) return false;
        String name = p.getName();
        if (getCooldownUntil(name) > System.currentTimeMillis()) return true;
        return findActive(name, itemType) != null;
    }

    /**
     * 申领（静默版）：被拒时不给玩家发消息。
     * 用于登录自动发放等场景，避免每次上线刷"已有登记"提示。
     */
    public synchronized String applySnQuiet(Player p, String itemType,
                                            String what) {
        return applySnEx(p, itemType, what, true);
    }

    public synchronized String applySn(Player p, String itemType, String what) {
        return applySnEx(p, itemType, what, false);
    }

    /**
     * 申领（管理员强制版）：仅本次绕过冷静期，冷静期记录原样保留
     * （防滥用规则不削弱），"同种类仅 1 个"仍然生效。
     */
    public synchronized String applySnForce(Player p, String itemType,
                                            String what) {
        return applySnEx(p, itemType, what, false, true);
    }

    private synchronized String applySnEx(Player p, String itemType,
                                          String what, boolean quiet) {
        return applySnEx(p, itemType, what, quiet, false);
    }

    private synchronized String applySnEx(Player p, String itemType,
                                          String what, boolean quiet,
                                          boolean force) {
        if (p == null) return null;
        String name = p.getName();
        long now = System.currentTimeMillis();

        long cd = getCooldownUntil(name);
        if (!force && cd > now) {
            if (!quiet) {
                long mins = (cd - now + 59999) / 60000;
                p.sendMessage("§c[SN] 注销冷静期未结束，还剩约 §f" + mins
                        + " §c分钟，禁止申领新设备");
            }
            return null;
        }

        Map<String, Object> old = findActive(name, itemType);
        if (old != null) {
            String oldSn = str(old.get("sn"));
            String st = str(old.get("status"));
            if (!quiet) {
                p.sendMessage("§c[SN] 你已有 §f" + typeName(itemType)
                        + " §c的有效登记（SN: §f" + oldSn + "§c）");
                if (ST_ILLEGAL.equals(st)) {
                    p.sendMessage("§c该 SN 因非法途径处理已被永久绑定，"
                            + "请联系管理员人工核查");
                } else if (ST_LOST.equals(st)) {
                    p.sendMessage("§7该物品处于报失处理中，请等待核查结果");
                } else {
                    p.sendMessage("§7如物品确实丢失请先报失，"
                            + "或在网页端注销后再申领");
                }
            }
            return null;
        }

        String code = getPlayerCode(name);
        long ts = now / 1000L;
        String sn = null;
        for (int i = 0; i < 20; i++) {
            String cand = name + String.format("%010d", ts + i) + code;
            if (!snExists(cand)) { sn = cand; break; }
        }
        if (sn == null) sn = name + String.format("%010d", ts) + code
                + System.currentTimeMillis() % 100;

        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO item_sn (sn, item_type, owner, owner_uuid,"
                            + " player_code, issue_time, status, loc_type,"
                            + " loc_player, last_seen, remark)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
            ps.setString(1, sn);
            ps.setString(2, itemType);
            ps.setString(3, name);
            ps.setString(4, p.getUniqueId() != null
                    ? p.getUniqueId().toString() : "");
            ps.setString(5, code);
            ps.setLong(6, now);
            ps.setString(7, ST_ACTIVE);
            ps.setString(8, "player");
            ps.setString(9, name);
            ps.setLong(10, now);
            ps.setString(11, "");
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("apply:" + e.getMessage());
            if (!quiet) p.sendMessage("§c[SN] 登记失败，请联系管理员");
            return null;
        }

        logSn(sn, itemType, "issue", name, "申领 " + what);
        return sn;
    }

    private boolean snExists(String sn) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM item_sn WHERE sn = ?");
            ps.setString(1, sn);
            ResultSet rs = ps.executeQuery();
            boolean has = rs.next();
            rs.close();
            ps.close();
            return has;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 状态流转 ====================

    /** 取一条 SN 记录，没有返回 null */
    public Map<String, Object> getSn(String sn) {
        if (sn == null || sn.isEmpty()) return null;
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM item_sn WHERE sn = ?");
            ps.setString(1, sn);
            ResultSet rs = ps.executeQuery();
            Map<String, Object> row = rsToMap(rs);
            rs.close();
            ps.close();
            return row;
        } catch (SQLException e) {
            throttleErr("get:" + e.getMessage());
            return null;
        }
    }

    /**
     * 销毁解绑：物品正常消失（回收 / 掉虚空 / 管理清除）→ 解绑 SN，
     * 玩家可重新申领。
     */
    public boolean destroySn(String sn, String reason) {
        Map<String, Object> row = getSn(sn);
        if (row == null) return false;
        String st = str(row.get("status"));
        if (ST_CANCELLED.equals(st) || ST_DESTROYED.equals(st)) return true;
        int n = setStatus(sn, ST_DESTROYED,
                "destroy:" + reason, System.currentTimeMillis());
        if (n > 0) {
            logSn(sn, str(row.get("item_type")), "destroy",
                    str(row.get("owner")), reason);
            return true;
        }
        return false;
    }

    /**
     * 非法途径处理（分解 / 非法转移 / 熔炼消耗）→ 永久绑定落库，
     * 该 SN 不可再申领同类物品。
     */
    public boolean bindIllegal(String sn, String reason) {
        Map<String, Object> row = getSn(sn);
        if (row == null) return false;
        if (ST_ILLEGAL.equals(str(row.get("status")))) return true;
        int n = setStatus(sn, ST_ILLEGAL, reason, System.currentTimeMillis());
        if (n > 0) {
            logSn(sn, str(row.get("item_type")), "illegal_bind",
                    str(row.get("owner")), reason);
            enqueue("illegal_bind", sn, str(row.get("owner")), reason);
            return true;
        }
        return false;
    }

    /** 更新位置（拾取 / 出入库 / 丢弃） */
    public void updateLoc(String sn, String locType, String locPlayer,
                          Location loc, String containerType) {
        try {
            Connection db = plugin.getDb().getConnection();
            long now = System.currentTimeMillis();

            // ★ 顺带维护"是否仍在本人管辖"三条件的两个数据位：
            //   own_chest（容器是否在本人名下领地）+ detached_at（脱离管控起始时刻）。
            //   回到本人管辖 → detached_at 清零；再次脱离 → 从 0 重新起算（防反复搬运凑时长）。
            String owner = "";
            PreparedStatement qs = db.prepareStatement(
                    "SELECT owner FROM item_sn WHERE sn = ?");
            qs.setString(1, sn);
            ResultSet qrs = qs.executeQuery();
            if (qrs.next()) owner = str(qrs.getString("owner"));
            qrs.close();
            qs.close();

            boolean inBody = "player".equals(locType) && owner != null
                    && !owner.isEmpty() && locPlayer != null
                    && AreaProtection.samePlayer(owner, locPlayer);
            boolean ownChest = false;
            if ("container".equals(locType) && loc != null
                    && loc.getWorld() != null) {
                try {
                    Sdf1_login.AreaProtection.AreaConfig ac =
                            plugin.areaProtection != null
                                    ? plugin.areaProtection.getArea(
                                    loc.getWorld().getName(),
                                    loc.getBlockX(), loc.getBlockY(),
                                    loc.getBlockZ())
                                    : null;
                    ownChest = ac != null && ac.owner != null
                            && !ac.owner.isEmpty() && owner != null
                            && AreaProtection.samePlayer(owner, ac.owner);
                } catch (Throwable ignored) {
                }
            }
            boolean custody = inBody || ownChest;

            StringBuilder sb = new StringBuilder(
                    "UPDATE item_sn SET loc_type = ?, loc_player = ?,");
            List<Object> args = new ArrayList<>();
            args.add(locType);
            args.add(locPlayer == null ? "" : locPlayer);
            if (loc != null && loc.getWorld() != null) {
                sb.append(" loc_world = ?, loc_x = ?, loc_y = ?, loc_z = ?,");
                args.add(loc.getWorld().getName());
                args.add(loc.getBlockX());
                args.add(loc.getBlockY());
                args.add(loc.getBlockZ());
            }
            sb.append(" container_type = ?, own_chest = ?,")
                    .append(" detached_at = CASE WHEN ? = 1 THEN 0")
                    .append(" WHEN detached_at > 0 THEN detached_at")
                    .append(" ELSE ? END,")
                    .append(" last_seen = ? WHERE sn = ?");
            args.add(containerType == null ? "" : containerType);
            args.add(ownChest ? 1 : 0);
            args.add(custody ? 1 : 0);
            // 脱离起始时刻：dropped 记 now+10 分钟（10 分钟内捡回不算脱离），
            // trash / 非本人容器记 now（投入垃圾箱即脱离）。已有起始则保持原值，
            // 防止反复搬运 / 反复投取凑满 12 小时。
            args.add("dropped".equals(locType)
                    ? now + DETACH_GRACE_MS : now);
            args.add(now);
            args.add(sn);

            PreparedStatement ps = db.prepareStatement(sb.toString());
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof Integer) ps.setInt(i + 1, (Integer) a);
                else if (a instanceof Long) ps.setLong(i + 1, (Long) a);
                else ps.setString(i + 1, String.valueOf(a));
            }
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("loc:" + e.getMessage());
        }
    }

    /**
     * 物品进入本插件垃圾箱（扫地机收走 / 玩家投入）→ 立即脱离玩家本人管控，
     * 起算 12 小时找回期。垃圾箱里的东西玩家还能取回，取回即恢复管辖。
     *
     * <p>所有存入路径（shift 投入 / 光标放入 / 拖拽 / 扫地机清理地面）
     * 最终都汇聚在 {@code GarbageManager.saveItem}，在那里统一挂口，
     * 避免漏掉某条路径导致 detached_at 永远不起算。</p>
     *
     * @param stack 被收走的物品
     * @param where 去处（垃圾站 / 扫地机清理），写进 container_type 与日志
     */
    public void onConfiscated(ItemStack stack, String where) {
        try {
            if (stack == null) return;
            String type = detectType(stack);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(stack);
            if (sn == null) return;
            updateLoc(sn, "trash", "", null, where);
            logSn(sn, type, "trash_in", "",
                    "投入" + where + "，脱离管控起算 12 小时找回期");
        } catch (Throwable t) {
            throttleErr("trash:" + t.getMessage());
        }
    }

    /**
     * 从垃圾箱取回 → 回到本人身上，脱离计时清零（等候找回窗口关闭）。
     *
     * @param stack 取回的物品
     * @param p     取回者
     */
    public void onRecovered(ItemStack stack, Player p) {
        try {
            if (stack == null || p == null) return;
            String type = detectType(stack);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(stack);
            if (sn == null) return;
            updateLoc(sn, "player", p.getName(), p.getLocation(), "");
            logSn(sn, type, "recovered", p.getName(),
                    "从垃圾箱取回，脱离计时清零");
        } catch (Throwable t) {
            throttleErr("reco:" + t.getMessage());
        }
    }

    private int setStatus(String sn, String status, String remark, long time) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE item_sn SET status = ?, remark = ?,"
                            + " bind_reason = CASE WHEN ? = 'illegal'"
                            + " THEN ? ELSE bind_reason END,"
                            + " bind_time = CASE WHEN ? = 'illegal'"
                            + " THEN ? ELSE bind_time END,"
                            + " cancel_time = CASE WHEN ? = 'cancelled' THEN ? ELSE cancel_time END"
                            + " WHERE sn = ?");
            ps.setString(1, status);
            ps.setString(2, remark);
            ps.setString(3, status);
            ps.setString(4, remark);
            ps.setString(5, status);
            ps.setLong(6, time);
            ps.setString(7, status);
            ps.setLong(8, time);
            ps.setString(9, sn);
            int n = ps.executeUpdate();
            ps.close();
            return n;
        } catch (SQLException e) {
            throttleErr("set:" + e.getMessage());
            return 0;
        }
    }

    // ==================== 日志 / 队列 ====================

    public void logSn(String sn, String itemType, String action,
                      String player, String detail) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO sn_log (sn, item_type, action, player,"
                            + " detail, time) VALUES (?,?,?,?,?,?)");
            ps.setString(1, sn);
            ps.setString(2, itemType == null ? "" : itemType);
            ps.setString(3, action);
            ps.setString(4, player == null ? "" : player);
            ps.setString(5, detail == null ? "" : detail);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("log:" + e.getMessage());
        }
    }

    /** 出入库登记（容器坐标 + 是否在领地 + 领地名） */
    public void recordStock(String sn, String itemType, String action,
                            Location loc, String containerType,
                            String playerName) {
        String world = "";
        int x = 0, y = 0, z = 0;
        int inLand = 0;
        String landName = "";
        if (loc != null && loc.getWorld() != null) {
            world = loc.getWorld().getName();
            x = loc.getBlockX();
            y = loc.getBlockY();
            z = loc.getBlockZ();
            try {
                Sdf1_login.AreaProtection.AreaConfig ac =
                        plugin.areaProtection != null
                                ? plugin.areaProtection.getArea(world, x, y, z)
                                : null;
                if (ac != null) {
                    inLand = 1;
                    landName = ac.name != null ? ac.name : "";
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO sn_stock_log (sn, item_type, action, world,"
                            + " x, y, z, container_type, in_land, land_name,"
                            + " player, time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
            ps.setString(1, sn);
            ps.setString(2, itemType == null ? "" : itemType);
            ps.setString(3, action);
            ps.setString(4, world);
            ps.setInt(5, x);
            ps.setInt(6, y);
            ps.setInt(7, z);
            ps.setString(8, containerType == null ? "" : containerType);
            ps.setInt(9, inLand);
            ps.setString(10, landName);
            ps.setString(11, playerName == null ? "" : playerName);
            ps.setLong(12, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("stock:" + e.getMessage());
        }
    }

    /** 写入待同步队列，由定时器推给 PHP */
    public void enqueue(String action, String sn, String player, String detail) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO sn_queue (action, sn, player, detail,"
                            + " created_at, synced) VALUES (?,?,?,?,?,0)");
            ps.setString(1, action);
            ps.setString(2, sn == null ? "" : sn);
            ps.setString(3, player == null ? "" : player);
            ps.setString(4, detail == null ? "" : detail);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("queue:" + e.getMessage());
        }
    }

    // ==================== 事件：拾取探查 ====================

    /**
     * 玩家拾取 4 类物品时探查：SN 登记的主人与拾取人不符 → 写 SQL 队列
     * 等待自动同步（任务6）并记日志告警。
     *
     * <p>★ 转移物品不锁 SN（2026-10-03 用户口径）：归属转移只留痕，
     * 不再 bindIllegal——否则物品转一圈物归原主后，原主人会被永久锁定
     * 无法使用/注销/补发。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        try {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();
            ItemStack it = e.getItem().getItemStack();
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(it);
            if (sn == null) return;

            Map<String, Object> row = getSn(sn);
            if (row == null) return; // 未登记的存量物品，交清点流程处理

            // ★ 已注销/补发作废/已销毁的实物：拒绝拾取，留在原地等自然消失
            if (isDeadSnStatus(str(row.get("status")))) {
                e.setCancelled(true);
                msgDead(p, "§c[SN] 该" + typeName(str(row.get("item_type")))
                        + "已" + statusCn(str(row.get("status")))
                        + "，无法拾取");
                return;
            }

            String owner = str(row.get("owner"));
            boolean mismatch = !owner.equalsIgnoreCase(p.getName());

            // ★ loc_type 必须记 "player"（旧版误传了 detectType 的物品类型，
            //   导致登记位置恒不是 'player'，"是否在本人身上"这一条永远判不出来）
            updateLoc(sn, "player", p.getName(), p.getLocation(), "");
            if (mismatch) {
                String detail = "登记主=" + owner + " 实际拾取=" + p.getName();
                enqueue("pickup_mismatch", sn, p.getName(), detail);
                logSn(sn, type, "pickup_mismatch", p.getName(), detail);
                // ★ 转移物品不锁 SN（2026-10-03 用户口径）：只留痕告警，
                //   不再 bindIllegal——否则物归原主后原主人无法使用。
                p.sendMessage("§7[SN] 该" + typeName(type) + "的登记主是 §e"
                        + owner + " §f，转移不改变归属");
            }
        } catch (Throwable t) {
            throttleErr("pickup:" + t.getMessage());
        }
    }

    /** 丢弃 → 位置记为 dropped */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        try {
            ItemStack it = e.getItemDrop().getItemStack();
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(it);
            if (sn == null) return;
            updateLoc(sn, "dropped", e.getPlayer().getName(),
                    e.getPlayer().getLocation(), "");
        } catch (Throwable t) {
            throttleErr("drop:" + t.getMessage());
        }
    }

    /**
     * 地上的 SN 物品自然消失（到时到期 / 被扫地机清掉）→ 不再立即销毁解绑，
     * 改为「脱离本人管控」起算 12 小时找回期（2026-09-30 调整）。
     *
     * <p>原实现直接 destroySn：实物被清掉的瞬间 SN 就解绑了，DB 里却可能
     * 仍停在旧的登记位置，后台状态与实际对不上；反过来扫地机若不触发本事件，
     * loc_type 又会永远卡在 "地上"。统一口径为：</p>
     * <ul>
     *   <li>扔地上 10 分钟没人捡 → 脱离管控，开始计时；</li>
     *   <li>12 小时内可从垃圾箱找回（取回即清零）；</li>
     *   <li>超 12 小时未找回 = 彻底丢失 → 放行注销并允许补发。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        try {
            ItemStack it = e.getEntity().getItemStack();
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(it);
            if (sn == null) return;
            Map<String, Object> row = getSn(sn);
            if (row == null) return;
            if (isDeadSnStatus(str(row.get("status")))) return;
            updateLoc(sn, "dropped", "",
                    e.getEntity().getLocation(), "");
        } catch (Throwable t) {
            throttleErr("despawn:" + t.getMessage());
        }
    }

    /** SN 物品被火烧 / 岩浆销毁 → 自动解绑 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent e) {
        try {
            if (!(e.getEntity() instanceof Item)) return;
            ItemStack it = ((Item) e.getEntity()).getItemStack();
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(it);
            if (sn == null) return;
            destroySn(sn, "物品被火烧毁");
            enqueue("destroyed", sn, "", "物品被火烧毁");
        } catch (Throwable t) {
            throttleErr("combust:" + t.getMessage());
        }
    }

    // ==================== 事件：出入库登记 ====================

    /**
     * 开箱扫描（针对收藏癖）：容器里存的 4 类插件物品若没有 SN，打开的瞬间
     * 按「开箱人名下是否已有同类 SN 登记」分流：
     * <li>已有登记 → 旧的直接强制收回（全部回收，不补发）；
     * <li>没有登记 → 以旧换新：旧的全部回收，只换发 1 个带 SN 的新物品。
     * <p>
     * 铁律：先登记 + 构建新物品成功，才删箱内旧品，绝不净损失。
     * 不变量：本插件物品要么不存在、要么带 SN，绝不允许带 SN 与无 SN 共存。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent e) {
        try {
            if (!(e.getPlayer() instanceof Player)) return;
            Player p = (Player) e.getPlayer();
            if (!plugin.getLoggedIn().contains(p.getName())) return;
            Inventory inv = e.getInventory();
            if (!isBlockContainer(inv)) return;
            recycleNoSnInContainer(p, inv);
        } catch (Throwable t) {
            throttleErr("open:" + t.getMessage());
        }
    }

    /** 是否为方块容器视图（含大箱子；排除玩家背包/自定义 GUI 等） */
    private boolean isBlockContainer(Inventory inv) {
        if (inv instanceof org.bukkit.inventory.DoubleChestInventory) return true;
        InventoryHolder h = inv.getHolder();
        if (h instanceof org.bukkit.block.BlockState) return true;
        if (h instanceof org.bukkit.block.Container) return true;
        // 潜影盒物品（未放置）打开时 holder 可能为 null，按视图类型兜底
        return h == null && inv.getType() == InventoryType.SHULKER_BOX;
    }

    /**
     * 开箱回收：容器内 4 类无SN物品按「开箱人名下登记」分流。
     * 已有登记 → 直接收回（不补发）；无登记 → 以旧换新（旧的全收、只发 1 件）。
     */
    private void recycleNoSnInContainer(Player p, Inventory inv) {
        String owner = p.getName();
        long now = System.currentTimeMillis();

        Map<String, List<Integer>> noSn = new LinkedHashMap<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) continue;
            if (readSn(it) != null) continue;
            if (!noSn.containsKey(type)) {
                noSn.put(type, new ArrayList<Integer>());
            }
            noSn.get(type).add(i);
        }
        if (noSn.isEmpty()) return;

        int reclaimed = 0, resent = 0;
        for (Map.Entry<String, List<Integer>> en : noSn.entrySet()) {
            String type = en.getKey();
            List<Integer> slots = en.getValue();

            if (findActive(owner, type) != null
                    || playerHasSnItem(p, type)) {
                // 名下已有登记（或背包已有带SN实物）= 已经有了 → 直接强制收回，不补发
                clearContainerSlots(inv, slots);
                reclaimed += slots.size();
                logSn("", type, "recycle_dup", owner,
                        "开箱强制收回无SN" + typeName(type) + " x" + slots.size());
                continue;
            }
            if (getCooldownUntil(owner) > now) {
                // 冷静期换发必被拒 → 不动旧品，绝不净损失
                throttleErr("open-cd:" + owner + ":" + type
                        + " 注销冷静期内，暂缓开箱回收无SN" + typeName(type));
                continue;
            }
            String newSn = applySnQuiet(p, type, typeName(type));
            if (newSn == null) {
                throttleErr("open-apply:" + owner + ":" + type
                        + " 换发登记被拒，暂缓开箱回收无SN" + typeName(type));
                continue;
            }
            ItemStack fresh = buildSnItem(p, type, newSn);
            if (fresh == null) {
                destroySn(newSn, "开箱以旧换新构建失败回滚");
                throttleErr("open-build:" + owner + ":" + type
                        + " 物品构建失败，已回滚登记");
                continue;
            }
            clearContainerSlots(inv, slots);
            giveTo(p, fresh);
            reclaimed += slots.size();
            resent++;
            logSn(newSn, type, "recycle", owner,
                    "开箱以旧换新：收回无SN旧品 x" + slots.size() + "，换发1件");
        }
        if (reclaimed > 0) {
            if (resent > 0) {
                p.sendMessage("§a[SN] 以旧换新：收回箱内无SN物品 §f" + reclaimed
                        + " §a件，换发带SN物品 §f" + resent + " §a件");
            } else {
                p.sendMessage("§a[SN] 已收回箱内无SN物品 §f" + reclaimed
                        + " §a件（名下已有同类SN登记）");
            }
        }
    }

    /** 清空容器指定槽位 */
    private void clearContainerSlots(Inventory inv, List<Integer> slots) {
        for (int idx : slots) {
            inv.setItem(idx, null);
        }
    }

    /** 玩家背包里是否已有该类带 SN 的实物（登记行缺失时的兜底判据） */
    private boolean playerHasSnItem(Player p, String itemType) {
        ItemStack[] contents = p.getInventory().getContents();
        if (contents == null) return false;
        for (ItemStack it : contents) {
            if (it == null) continue;
            if (!itemType.equals(detectType(it))) continue;
            if (readSn(it) != null) return true;
        }
        return false;
    }

    /**
     * 容器关闭时做入库/出库登记（任务4）：
     * 记录容器坐标、容器类型、是否在领地及领地名。
     * 只处理真方块容器（箱子 / 末影箱 / 潜影盒 / 木桶 / 漏斗 / 投掷器等）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onContainerClose(InventoryCloseEvent e) {
        try {
            if (!(e.getPlayer() instanceof Player)) return;
            Player p = (Player) e.getPlayer();
            Inventory inv = e.getInventory();
            InventoryHolder holder = inv.getHolder();
            if (!(holder instanceof BlockState)) return; // 玩家背包等非方块容器
            BlockState bs = (BlockState) holder;
            Location loc = bs.getLocation();
            String ctype = inv.getType().name();

            Set<String> present = new HashSet<>();
            for (ItemStack it : inv.getContents()) {
                if (it == null) continue;
                String type = detectType(it);
                if (type == null || !WATCH_TYPES.contains(type)) continue;
                String sn = readSn(it);
                if (sn == null) continue;
                present.add(sn);
                updateLoc(sn, "container", p.getName(), loc, ctype);
                recordStock(sn, type, "in", loc, ctype, p.getName());
            }

            // 该容器之前登记过、但现在不在里面的 → 被取走
            for (String sn : querySnAtContainer(loc, ctype)) {
                if (present.contains(sn)) continue;
                Map<String, Object> row = getSn(sn);
                if (row == null) continue;
                updateLoc(sn, "player", p.getName(), p.getLocation(), "");
                recordStock(sn, str(row.get("item_type")), "out",
                        loc, ctype, p.getName());
            }
        } catch (Throwable t) {
            throttleErr("close:" + t.getMessage());
        }
    }

    /** 某容器坐标上登记过的 SN */
    private List<String> querySnAtContainer(Location loc, String ctype) {
        List<String> out = new ArrayList<>();
        if (loc == null || loc.getWorld() == null) return out;
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT sn FROM item_sn WHERE loc_type = 'container'"
                            + " AND loc_world = ? AND loc_x = ? AND loc_y = ?"
                            + " AND loc_z = ? AND container_type = ?");
            ps.setString(1, loc.getWorld().getName());
            ps.setInt(2, loc.getBlockX());
            ps.setInt(3, loc.getBlockY());
            ps.setInt(4, loc.getBlockZ());
            ps.setString(5, ctype);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(rs.getString(1));
            rs.close();
            ps.close();
        } catch (SQLException e) {
            throttleErr("qcont:" + e.getMessage());
        }
        return out;
    }

    // ==================== 事件：非法途径 ====================

    /**
     * 合成 / 分解消耗到 4 类物品 → 非法途径永久绑定（任务6）。
     * 典型：烈焰棒被分解成烈焰粉、雪球菜单被当合成材料。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        try {
            if (!(e.getWhoClicked() instanceof Player)) return;
            Player p = (Player) e.getWhoClicked();
            scanMatrix(e.getInventory().getMatrix(), p, "合成/分解消耗");
        } catch (Throwable t) {
            throttleErr("craft:" + t.getMessage());
        }
    }

    /**
     * 配方书一键合成同样要拦。
     * PlayerRecipeBookClickEvent 不暴露合成矩阵，改为
     * 「点击时快照背包里的 SN 物品 -> 下一 tick 复查」：
     * SN 消失即说明被配方消耗，永久绑定落库。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecipeBookClick(final PlayerRecipeBookClickEvent e) {
        try {
            final Player p = e.getPlayer();
            final Map<String, String> before =
                    new LinkedHashMap<String, String>();
            ItemStack[] contents = p.getInventory().getContents();
            if (contents == null) return;
            for (ItemStack it : contents) {
                if (it == null) continue;
                String type = detectType(it);
                if (type == null || !WATCH_TYPES.contains(type)) continue;
                String sn = readSn(it);
                if (sn == null) continue;
                before.put(sn, type);
            }
            if (before.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!p.isOnline()) return;
                        Set<String> now = new HashSet<String>();
                        ItemStack[] cs = p.getInventory().getContents();
                        if (cs == null) return;
                        for (ItemStack it : cs) {
                            if (it == null) continue;
                            String sn = readSn(it);
                            if (sn != null) now.add(sn);
                        }
                        for (Map.Entry<String, String> en : before.entrySet()) {
                            if (now.contains(en.getKey())) continue;
                            bindIllegal(en.getKey(),
                                    "配方书合成消耗 by " + p.getName());
                            enqueue("illegal_craft", en.getKey(),
                                    p.getName(), "配方书合成消耗");
                        }
                    } catch (Throwable t) {
                        throttleErr("recipe2:" + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            throttleErr("recipe:" + t.getMessage());
        }
    }

    /** 熔炉把 SN 物品当燃料烧掉 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        try {
            ItemStack fuel = e.getFuel();
            if (fuel == null) return;
            String type = detectType(fuel);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(fuel);
            if (sn == null) return;
            bindIllegal(sn, "熔炼消耗（燃料）");
            enqueue("illegal_burn", sn, "", "熔炉消耗");
        } catch (Throwable t) {
            throttleErr("burn:" + t.getMessage());
        }
    }

    /**
     * 发射器 / 投掷器把 SN 物品发射出去（2026-10-03）：
     * 雪球菜单等管控物品一经机器发射即视为"已使用 / 分解"——永久锁定该 SN：
     * <ul>
     *   <li>不再发放：findActive 把 illegal 计入有效登记，同种类无法再申领；</li>
     *   <li>非强制不支持注销 / 补发：doCancel、doReissue 对 illegal 仅在
     *       force=0 时拒绝；管理员强制可最终处置（解锁被卡死的名额）。</li>
     * </ul>
     * BlockDispenseEvent 在发射器与投掷器上都会触发（投掷器走同一条 dispense 逻辑）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        try {
            ItemStack out = e.getItem();
            if (out == null) return;
            String type = detectType(out);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(out);
            if (sn == null) return;
            bindIllegal(sn, "发射器/投掷器发射消耗");
            enqueue("illegal_dispense", sn, "", "发射器发射");
        } catch (Throwable t) {
            throttleErr("dispense:" + t.getMessage());
        }
    }

    private void scanMatrix(ItemStack[] matrix, Player p, String why) {
        if (matrix == null) return;
        for (ItemStack it : matrix) {
            if (it == null) continue;
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) continue;
            String sn = readSn(it);
            if (sn == null) continue;
            bindIllegal(sn, why + " by " + p.getName());
            enqueue("illegal_craft", sn, p.getName(), why);
        }
    }

    // ==================== 自动回收（任务7补充） ====================

    /**
     * 状态已"死"的 SN：其实物不允许继续存在/使用。
     * illegal（永久绑定）不算死——那是给原主继续用的。
     */
    private static boolean isDeadSnStatus(String st) {
        return ST_CANCELLED.equals(st) || ST_REISSUED.equals(st)
                || ST_DESTROYED.equals(st);
    }

    /** 作废实物相关提示（限流 3 秒/人） */
    private void msgDead(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long last = deadMsgAt.get(p.getName());
        if (last != null && now - last < 3000L) return;
        deadMsgAt.put(p.getName(), now);
        p.sendMessage(msg);
    }

    /**
     * 上线后 5 秒自动清点一次（等登录完成、背包恢复之后再动）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                autoSweep(p, true);
            }
        }, 100L);
    }

    /**
     * 定时兜底：每 60 秒清点全体在线玩家。
     * 背包里的无SN旧品在这一轮回收；箱内存放的无SN旧品由开箱扫描（onChestOpen）处理。
     */
    private void startAutoSweepTask() {
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    List<Player> online =
                            new ArrayList<>(Bukkit.getOnlinePlayers());
                    for (Player p : online) autoSweep(p, false);
                }
            }, 200L, 1200L);
        } catch (Throwable t) {
            throttleErr("sweeptimer:" + t.getMessage());
        }
    }

    /**
     * 脱离计时巡检（每 5 分钟）：
     * 1) 补齐没走过 updateLoc 的脱离起算 —— 扫地机 / 外部清理插件直接删实体
     *    时不会触发任何 SN 事件，detached_at 恒为 0 → 三条件永远不达标 →
     *    玩家自助注销被永久卡死（本次线上问题的根因）；
     * 2) 已超 12 小时的打一条"彻底丢失"日志，说明该 SN 此刻起可注销 / 补发。
     */
    private void startCustodySweepTask() {
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    sweepCustody();
                }
            }, 400L, 6000L);
        } catch (Throwable t) {
            throttleErr("custodytimer:" + t.getMessage());
        }
    }

    /** 已上报过"超时丢失"的 SN（内存限频，重启后最多重复一条） */
    private final Set<String> lostReported =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void sweepCustody() {
        try {
            Connection db = plugin.getDb().getConnection();

            // 1) 存量补起算：非"在本人身上"、还没记脱离起始的
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE item_sn SET detached_at = CASE"
                            + " WHEN loc_type = 'dropped' THEN last_seen + ?"
                            + " ELSE last_seen END"
                            + " WHERE status = 'active' AND detached_at = 0"
                            + " AND last_seen > 0"
                            + " AND loc_type IN ('dropped','trash','container')"
                            + " AND NOT (loc_type = 'container'"
                            + " AND own_chest = 1)");
            ps.setLong(1, DETACH_GRACE_MS);
            int patched = ps.executeUpdate();
            ps.close();
            if (patched > 0) {
                plugin.getLogger().info(
                        "[SN] 巡检补齐脱离起算 " + patched + " 条");
            }

            // 2) 超过 12 小时：视为彻底丢失，留痕说明可办理
            long cutoff = System.currentTimeMillis() - CUSTODY_DETACH_MS;
            PreparedStatement qs = db.prepareStatement(
                    "SELECT sn, item_type, owner FROM item_sn"
                            + " WHERE status = 'active' AND detached_at > 0"
                            + " AND detached_at < ?");
            qs.setLong(1, cutoff);
            ResultSet rs = qs.executeQuery();
            while (rs.next()) {
                String sn = rs.getString("sn");
                if (!lostReported.add(sn)) continue;
                logSn(sn, rs.getString("item_type"), "lost_timeout",
                        rs.getString("owner"),
                        "脱离管控已超 12 小时，视为彻底丢失，可注销 / 补发");
            }
            rs.close();
            qs.close();
        } catch (Throwable t) {
            throttleErr("custody:" + t.getMessage());
        }
    }

    /**
     * 自动回收单个玩家：
     * 1) 抠掉状态已死的实物（已注销/补发作废/已销毁——这些 DB 注销了但实物还能用）；
     * 2) 背包里无 SN 的 4 类旧品：名下已有同类登记 → 直接强制收回不补发；
     *    名下无登记 → 以旧换新（旧的全回收、只换发 1 件）；冷静期暂缓，绝不净损失。
     * 仅处理已登录玩家：未登录时背包正被登录流程接管。
     */
    private void autoSweep(Player p, boolean fromJoin) {
        if (p == null || !p.isOnline()) return;
        try {
            if (!plugin.getLoggedIn().contains(p.getName())) return;
            int dead = sweepDeadSn(p);
            int[] r = auditAndResend(p, false);
            if (dead > 0) {
                p.sendMessage("§c[SN] 已回收 §f" + dead
                        + " §c件已注销/作废的物品");
            }
            if (r[0] > 0) {
                p.sendMessage("§a[SN] 旧物品清点：回收无SN物品 §f" + r[0]
                        + " §a件，换发带SN物品 §f" + r[1] + " §a件"
                        + (fromJoin ? "（上线清点）" : ""));
            }
        } catch (Throwable t) {
            throttleErr("autosweep:" + t.getMessage());
        }
    }

    /**
     * 把背包里状态已死的实物抠掉。
     * 覆盖"注销时物在箱子 → 后来被取进背包"的漏网之鱼。
     *
     * @return 回收件数
     */
    public int sweepDeadSn(Player p) {
        int n = 0;
        if (p == null) return 0;
        try {
            ItemStack[] contents = p.getInventory().getContents();
            if (contents == null) return 0;
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType() == Material.AIR) continue;
                String sn = readSn(it);
                if (sn == null) continue;
                Map<String, Object> row = getSn(sn);
                if (row == null) continue;  // 无登记行，交给清点流程补登记
                String st = str(row.get("status"));
                if (!isDeadSnStatus(st)) continue;
                p.getInventory().setItem(i, null);
                n++;
                logSn(sn, str(row.get("item_type")), "reclaim", p.getName(),
                        "回收已作废实物(" + statusCn(st) + ")");
            }
        } catch (Throwable t) {
            throttleErr("sweepdead:" + t.getMessage());
        }
        return n;
    }

    /**
     * 作废实物使用拦截：已注销/补发作废/已销毁的 SN 物品，
     * 即使还留在背包里（例如注销时存箱子、后来取出来）也一律不能用。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDeadSnUse(PlayerInteractEvent e) {
        try {
            Player p = e.getPlayer();
            if (p == null) return;
            ItemStack it = e.getItem();
            if (it == null || it.getType() == Material.AIR) return;
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) return;
            String sn = readSn(it);
            if (sn == null) return;
            Map<String, Object> row = getSn(sn);
            if (row == null) return;
            String st = str(row.get("status"));
            if (!isDeadSnStatus(st)) return;
            e.setCancelled(true);
            if (removeSnFromInventory(p, sn)) {
                msgDead(p, "§c[SN] 该" + typeName(type) + "已"
                        + statusCn(st) + "，已回收");
            }
        } catch (Throwable t) {
            throttleErr("deaduse:" + t.getMessage());
        }
    }

    // ==================== 退出 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        try {
            scanPlayerInventory(e.getPlayer(), "quit");
        } catch (Throwable t) {
            throttleErr("quit:" + t.getMessage());
        }
    }

    // ==================== 扫描 ====================

    /**
     * 扫描玩家背包里的 4 类物品，返回 [sn, type, owner, holder] 列表，
     * 并刷新各自的位置登记。usedBy = quit/join/report/tick。
     */
    public List<String[]> scanPlayerInventory(Player p, String usedBy) {
        List<String[]> found = new ArrayList<>();
        if (p == null) return found;
        ItemStack[] contents = p.getInventory().getContents();
        if (contents == null) return found;
        for (ItemStack it : contents) {
            if (it == null) continue;
            String type = detectType(it);
            if (type == null || !WATCH_TYPES.contains(type)) continue;
            String sn = readSn(it);
            if (sn == null) continue;
            Map<String, Object> row = getSn(sn);
            String owner = row != null ? str(row.get("owner")) : "";
            if (row != null) {
                updateLoc(sn, "player", p.getName(), p.getLocation(), "");
            }
            found.add(new String[]{sn, type, owner, p.getName(), usedBy});
        }
        return found;
    }

    /** 收集所有在线玩家手持/背包的 SN（任务6 上报用） */
    public List<Map<String, Object>> collectOnlineSn() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                ItemStack hand = p.getInventory().getItemInMainHand();
                String handSn = hand != null ? readSn(hand) : null;
                for (String[] f : scanPlayerInventory(p, "tick")) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sn", f[0]);
                    m.put("item_type", f[1]);
                    m.put("owner", f[2]);
                    m.put("holder", f[3]);
                    m.put("held", f[0].equals(handSn) ? 1 : 0);
                    m.put("player", p.getName());
                    m.put("time", System.currentTimeMillis());
                    out.add(m);
                }
            } catch (Throwable t) {
                throttleErr("collect:" + t.getMessage());
            }
        }
        return out;
    }

    // ==================== 存量清点（任务7）====================

    /**
     * 轻点：把玩家背包里无 SN 的 4 类旧物品销毁并换发带 SN 的。
     * <p>
     * ★ 改判：名下只要有同类 SN 登记就判定「已经有了」→ 无SN旧品直接强制收回、
     * 不补发（实物可能存放在箱子里，不能因为背包没见到带SN的就暂缓）；
     * 名下无登记才走以旧换新：旧的全部回收、只换发 1 件。
     *
     * @param p     目标玩家（null = 全体在线）
     * @param dry   true 只统计不改动
     * @return [移除数, 重发数]
     */
    public int[] auditAndResend(Player p, boolean dry) {
        int removed = 0, resent = 0;
        List<Player> targets = new ArrayList<>();
        if (p != null) targets.add(p);
        else targets.addAll(Bukkit.getOnlinePlayers());

        for (Player t : targets) {
            ItemStack[] contents = t.getInventory().getContents();
            if (contents == null) continue;
            String owner = t.getName();
            // 无 SN 旧品按类型归槽位
            Map<String, List<Integer>> noSn = new LinkedHashMap<>();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null) continue;
                String type = detectType(it);
                if (type == null || !WATCH_TYPES.contains(type)) continue;
                String sn = readSn(it);
                if (sn != null) {
                    // 已有 SN：确认登记表里有记录，缺则补登记（防丢账）
                    ensureRegistered(sn, type, owner);
                    continue;
                }
                if (!noSn.containsKey(type)) {
                    noSn.put(type, new ArrayList<Integer>());
                }
                noSn.get(type).add(i);
                if (dry) removed++;   // dry 只统计"待清理"件数
            }
            if (dry || noSn.isEmpty()) continue;

            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<Integer>> en : noSn.entrySet()) {
                String type = en.getKey();
                List<Integer> slots = en.getValue();

                // ★ 铁律：已拥有 → 直接收回；要换发 → 先确认能换发出去
                //   才删旧的，绝不净损失
                if (findActive(owner, type) != null
                        || playerHasSnItem(t, type)) {
                    // ★ 改判：名下有同类 SN 登记（或背包里已有带SN实物）= 已经有了
                    //   → 无SN旧品直接强制收回、不补发。实物很可能存放在箱子里，
                    //   绝不能因此"暂缓回收"。
                    clearSlots(t, slots);
                    removed += slots.size();
                    logSn("", type, "recycle_dup", owner,
                            "名下已有SN登记/实物，强制回收无SN旧品 x"
                                    + slots.size());
                    continue;
                }
                if (getCooldownUntil(owner) > now) {
                    // 冷静期：换发必被拒 → 一个都不删，下轮再试（防净损失）
                    throttleErr("recycle-cd:" + owner + ":" + type
                            + " 注销冷静期内，暂缓回收无SN" + typeName(type));
                    continue;
                }
                String newSn = applySnQuiet(t, type, typeName(type));
                if (newSn == null) {
                    throttleErr("recycle-apply:" + owner + ":" + type
                            + " 换发登记被拒，暂缓回收无SN" + typeName(type));
                    continue;
                }
                ItemStack fresh = buildSnItem(t, type, newSn);
                if (fresh == null) {
                    destroySn(newSn, "无SN旧品换发构建失败回滚");
                    throttleErr("recycle-build:" + owner + ":" + type
                            + " 物品构建失败，已回滚登记");
                    continue;
                }
                clearSlots(t, slots);
                giveTo(t, fresh);
                removed += slots.size();
                resent++;
                logSn(newSn, type, "recycle", owner,
                        "无SN旧品换发 x" + slots.size());
            }
        }
        return new int[]{removed, resent};
    }

    /** 清空指定槽位 */
    private void clearSlots(Player p, List<Integer> slots) {
        for (int idx : slots) {
            p.getInventory().setItem(idx, null);
        }
    }

    /** 已有 SN 但登记表缺失 → 补登记（存量物品对账） */
    private void ensureRegistered(String sn, String type, String owner) {
        if (getSn(sn) != null) return;
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO item_sn (sn, item_type, owner, issue_time,"
                            + " status, loc_type, loc_player, last_seen,"
                            + " remark) VALUES (?,?,?,?,?,?,?,?,?)");
            ps.setString(1, sn);
            ps.setString(2, type);
            ps.setString(3, owner);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, ST_ACTIVE);
            ps.setString(6, "player");
            ps.setString(7, owner);
            ps.setLong(8, System.currentTimeMillis());
            ps.setString(9, "存量补登记");
            ps.executeUpdate();
            ps.close();
            logSn(sn, type, "backfill", owner, "存量物品补登记");
        } catch (SQLException e) {
            throttleErr("backfill:" + e.getMessage());
        }
    }

    /**
     * 按类型重新发放一个带 SN 的新物品。
     * 复用各发放入口，失败（申领被拒）返回 false。
     */
    public boolean giveNewSnItem(Player p, String itemType) {
        try {
            String sn = applySnForType(p, itemType);
            if (sn == null) return false;
            ItemStack it = buildSnItem(p, itemType, sn);
            if (it == null) return false;
            giveTo(p, it);
            return true;
        } catch (Throwable t) {
            throttleErr("give:" + t.getMessage());
        }
        return false;
    }

    /** 按类型申领（提示文案与各发放入口保持一致），未知类型返回 null */
    private String applySnForType(Player p, String itemType) {
        if (TYPE_MENU.equals(itemType)) return applySn(p, TYPE_MENU, "雪球菜单");
        if (TYPE_ECHO.equals(itemType)) return applySn(p, TYPE_ECHO, "回声碎片");
        if (TYPE_WAND.equals(itemType)) return applySn(p, TYPE_WAND, "区域选择工具");
        if (TYPE_PVP.equals(itemType)) return applySn(p, TYPE_PVP, "PVP圈地棒");
        return null;
    }

    /**
     * 按已登记的 SN 构造对应类型的实物（不查库、不重复登记）。
     * 补发 / 无SN旧品换发共用；未知类型返回 null。
     */
    private ItemStack buildSnItem(Player p, String itemType, String sn) {
        if (TYPE_MENU.equals(itemType)) return buildMenuItem(p, sn);
        if (TYPE_ECHO.equals(itemType)) {
            ItemStack it = plugin.landRecordManager.createEchoShard();
            writeSn(it, sn, TYPE_ECHO);
            return it;
        }
        if (TYPE_WAND.equals(itemType)) {
            ItemStack it = buildWand();
            writeSn(it, sn, TYPE_WAND);
            return it;
        }
        if (TYPE_PVP.equals(itemType)) {
            ItemStack it = buildPvpTool();
            writeSn(it, sn, TYPE_PVP);
            return it;
        }
        return null;
    }

    private void giveTo(Player p, ItemStack it) {
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(it);
        if (!left.isEmpty()) {
            p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
    }

    private ItemStack buildMenuItem(Player p, String sn) {
        ItemStack snow = new ItemStack(Material.SNOWBALL);
        ItemMeta im = snow.getItemMeta();
        if (im != null) {
            im.setDisplayName("§e§l[菜单] §f右键打开主菜单");
            List<String> lore = new ArrayList<>();
            lore.add("§7右键点击打开功能菜单");
            lore.add("§8sdf1_menu");
            im.setLore(lore);
            snow.setItemMeta(im);
        }
        writeSn(snow, sn, TYPE_MENU);
        return snow;
    }

    private ItemStack buildWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a§l区域选择工具");
            meta.setLore(java.util.Arrays.asList(
                    "§7左键点击选择第一个点",
                    "§7右键点击选择第二个点",
                    "§7手持工具输入 /protect 创建 <名称>",
                    "",
                    "§e草原探险 - 区域防护"));
            wand.setItemMeta(meta);
        }
        return wand;
    }

    private ItemStack buildPvpTool() {
        ItemStack tool = new ItemStack(Material.BLAZE_ROD);
        ItemMeta m = tool.getItemMeta();
        if (m != null) {
            m.setDisplayName("§6§l[PVP] 区域选择工具");
            m.setLore(java.util.Arrays.asList(
                    "§7右键：设置第一个角",
                    "§7左键：设置第二个角"));
            tool.setItemMeta(m);
        }
        return tool;
    }

    // ==================== 定时器（任务6）====================

    /**
     * 每 10~30 秒执行一次（由 WebManager 定时器框架调用）：
     * 1. 把在线玩家手持/背包的 SN 上报给 PHP；
     * 2. 把拾取异常等队列推给 PHP；
     * 3. 拉取 PHP 下发的注销/补发/报失核查命令并执行。
     */
    public void tickSync() {
        WebManager wm = plugin.webManager;
        if (wm == null || !wm.isEnabled()) return;

        // 1) 手持/背包 SN 上报
        try {
            List<Map<String, Object>> snList = collectOnlineSn();
            if (!snList.isEmpty()) {
                String json = toJsonArray(snList);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("action", "push_held");
                params.put("secret", wm.getSecretKey());
                wm.httpPost("api/sn.php?" + toQuery(params), json);
            }
        } catch (Throwable t) {
            throttleErr("tick-held:" + t.getMessage());
        }

        // 2) 待同步队列
        try {
            List<Map<String, Object>> pending = fetchQueue(50);
            if (!pending.isEmpty()) {
                String json = toJsonArray(pending);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("action", "push_events");
                params.put("secret", wm.getSecretKey());
                String resp = wm.httpPost("api/sn.php?" + toQuery(params), json);
                if (resp != null && resp.contains("\"ok\"")) {
                    markQueueSynced(pending);
                }
            }
        } catch (Throwable t) {
            throttleErr("tick-queue:" + t.getMessage());
        }

        // 3) 拉取 PHP 命令
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "pull_commands");
            params.put("secret", wm.getSecretKey());
            String resp = wm.httpGet("api/sn.php", params);
            if (resp != null && resp.contains("[")) {
                List<Map<String, Object>> cmds = parseJsonArray(resp);
                if (!cmds.isEmpty()) {
                    // 改背包必须回主线程。tickSync 跑在异步线程，原先 fire-and-forget
                    // 提交后立刻走到第4步 ack，此时 ackBuf 还是空的 → 回执要等
                    // 下一轮 Timer C（10~20秒，异常时1~2分钟）才发出，后台迟迟看不到结果。
                    // 这里用 CountDownLatch 等主线程执行完（超时3秒兜底），
                    // 让 pull → 执行 → ack 在同一轮内完成。
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(cmds.size());
                    for (final Map<String, Object> c : cmds) {
                        if (Bukkit.isPrimaryThread()) {
                            try {
                                execCommand(c);
                            } finally {
                                latch.countDown();
                            }
                        } else {
                            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        execCommand(c);
                                    } finally {
                                        latch.countDown();
                                    }
                                }
                            });
                        }
                    }
                    if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        // 主线程被卡住：结果仍在 ackBuf 里，下一轮重发，不丢
                        throttleErr("tick-cmd:主线程执行超时3s，回执延后到下一轮");
                    }
                }
            }
        } catch (Throwable t) {
            throttleErr("tick-cmd:" + t.getMessage());
        }

        // 4) 回传命令执行结果（含报失核查结论，PHP 据此决定是否自动补发）
        try {
            List<String> acks;
            synchronized (ackBuf) {
                if (ackBuf.isEmpty()) acks = null;
                // ★ 只做只读快照，不 clear：PHP 确认收到后才移除（见下方 ackResp 判断）。
                //   原实现先 clear 再 POST，POST 失败时这批回执被永久丢弃 →
                //   PHP 端命令一直停在 sent，180 秒后被判 timeout，状态永不回写。
                else acks = new ArrayList<String>(ackBuf);
            }
            if (acks != null) {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                for (String a : acks) {
                    int p = a.indexOf('\u0001');
                    if (p <= 0) continue;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", Long.parseLong(a.substring(0, p)));
                    m.put("result", a.substring(p + 1));
                    rows.add(m);
                }
                if (!rows.isEmpty()) {
                    Map<String, String> params = new LinkedHashMap<String, String>();
                    params.put("action", "ack_commands");
                    params.put("secret", wm.getSecretKey());
                    wm.httpPost("api/sn.php?" + toQuery(params), toJsonArray(rows));
                }
            }
        } catch (Throwable t) {
            throttleErr("tick-ack:" + t.getMessage());
        }

        // 5) 目录快照：物品全量 + 日志 / 出入库尾部（PHP 展示端数据源）
        catalogTick++;
        if (catalogTick % 3 == 1) {
            try {
                pushCatalog(wm);
            } catch (Throwable t) {
                throttleErr("tick-cat:" + t.getMessage());
            }
        }
    }

    /**
     * 目录快照：把 item_sn 全量 + sn_log / sn_stock_log 尾部推给 PHP。
     * PHP 端据此渲染"我的设备""出入库记录"等只读视图。
     */
    private void pushCatalog(WebManager wm) {
        try {
            Connection db = plugin.getDb().getConnection();

            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT sn, item_type, owner, status, issue_time,"
                            + " loc_type, loc_player, loc_world, loc_x, loc_y,"
                            + " loc_z, container_type, in_land, land_name,"
                            + " last_seen, cancel_time, lost_count, lost_state,"
                            + " remark, bind_reason, bind_time,"
                            + " detached_at, own_chest"
                            + " FROM item_sn ORDER BY issue_time DESC LIMIT 3000");
            ResultSet rs = ps.executeQuery();
            Map<String, Object> row;
            while ((row = rsToMap(rs)) != null) {
                row.put("loc_cn", locCn(str(row.get("loc_type"))));
                items.add(row);
            }
            rs.close();
            ps.close();

            List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
            PreparedStatement ps2 = db.prepareStatement(
                    "SELECT id, sn, item_type, action, player, detail, time"
                            + " FROM sn_log ORDER BY id DESC LIMIT 400");
            ResultSet rs2 = ps2.executeQuery();
            Map<String, Object> row2;
            while ((row2 = rsToMap(rs2)) != null) logs.add(row2);
            rs2.close();
            ps2.close();

            List<Map<String, Object>> stock = new ArrayList<Map<String, Object>>();
            PreparedStatement ps3 = db.prepareStatement(
                    "SELECT id, sn, item_type, action, world, x, y, z,"
                            + " container_type, in_land, land_name, player, time"
                            + " FROM sn_stock_log ORDER BY id DESC LIMIT 400");
            ResultSet rs3 = ps3.executeQuery();
            Map<String, Object> row3;
            while ((row3 = rsToMap(rs3)) != null) stock.add(row3);
            rs3.close();
            ps3.close();

            StringBuilder sb = new StringBuilder(4096);
            sb.append("{\"items\":").append(toJsonArray(items));
            sb.append(",\"logs\":").append(toJsonArray(logs));
            sb.append(",\"stock\":").append(toJsonArray(stock));
            sb.append("}");

            Map<String, String> params = new LinkedHashMap<String, String>();
            params.put("action", "push_catalog");
            params.put("secret", wm.getSecretKey());
            wm.httpPost("api/sn.php?" + toQuery(params), sb.toString());
        } catch (Throwable t) {
            throttleErr("catalog:" + t.getMessage());
        }
    }

    private List<Map<String, Object>> fetchQueue(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT id, action, sn, player, detail, created_at"
                            + " FROM sn_queue WHERE synced = 0"
                            + " ORDER BY id LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("action", rs.getString("action"));
                m.put("sn", rs.getString("sn"));
                m.put("player", rs.getString("player"));
                m.put("detail", rs.getString("detail"));
                m.put("created_at", rs.getLong("created_at"));
                out.add(m);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            throttleErr("qfetch:" + e.getMessage());
        }
        return out;
    }

    private void markQueueSynced(List<Map<String, Object>> rows) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE sn_queue SET synced = 1 WHERE id = ?");
            for (Map<String, Object> r : rows) {
                Object id = r.get("id");
                if (id == null) continue;
                ps.setLong(1, ((Number) id).longValue());
                ps.executeUpdate();
            }
            ps.close();
        } catch (SQLException e) {
            throttleErr("qack:" + e.getMessage());
        }
    }

    /**
     * 执行 PHP 下发的命令：cancel / reissue / locate / bind / report_check。
     *
     * @return 执行结论文本（同时进 ackBuf，由 tickSync 回传 PHP）
     */
    public String execCommand(Map<String, Object> c) {
        String result;
        long ackId = -1;
        try {
            long id = num(c.get("id"));
            ackId = id;
            String cmd = str(c.get("cmd"));
            String sn = str(c.get("sn"));
            String player = str(c.get("player"));
            String itemType = str(c.get("item_type"));
            String reason = str(c.get("reason"));
            // ★ 管理员强制操作（force=1）：
            //   cancel → 豁免三条件门槛，且不启动 1 小时冷静期；
            //   reissue → 豁免三条件门槛，申领时本次绕过冷静期校验（记录保留）；
            //   report_check → 回执带逐条判定（仅管理端可见）。
            //   代办（force=0）一律照常：三条件 + 1 小时冷静期。
            boolean force = num(c.get("force")) != 0;
            result = "";

            if ("cancel".equalsIgnoreCase(cmd)) {
                result = doCancel(sn, player, reason, force);
            } else if ("reissue".equalsIgnoreCase(cmd)) {
                result = doReissue(sn, player, itemType, reason, force);
            } else if ("locate".equalsIgnoreCase(cmd)) {
                Map<String, Object> row = getSn(sn);
                result = row == null ? "SN不存在" : custodyText(row);
            } else if ("bind".equalsIgnoreCase(cmd)) {
                bindIllegal(sn, reason.isEmpty() ? "管理员标记" : reason);
                result = "已永久绑定";
            } else if ("report_check".equalsIgnoreCase(cmd)) {
                result = doReportCheck(sn, player, force);
            } else if ("issue".equalsIgnoreCase(cmd)) {
                result = doIssue(sn, player, itemType, reason, force);
            } else if ("issue_new".equalsIgnoreCase(cmd)) {
                // 顶层「办理签发」：没有旧 SN，只凭 玩家ID + 设备类型 直发
                result = doIssueNew(player, itemType, reason, force);
            } else {
                result = "未知命令: " + cmd;
            }

            markCommandDone(id, result);
        } catch (Throwable t) {
            throttleErr("cmdexec:" + t.getMessage());
            result = "执行异常: " + t.getMessage();
        }
        if (ackId >= 0 && result != null) {
            synchronized (ackBuf) {
                ackBuf.add(ackId + "\u0001" + result);
            }
        }
        return result == null ? "" : result;
    }

    /** 三条件未达标时给玩家的统一回执：不说具体是哪一条没过（最小化信息透露） */
    private static final String CUSTODY_DENY = "拒绝:未达到办理条件";

    /**
     * 注销 / 报失 / 补办统一门槛的三条件判定：
     *   ① 物品不在本人身上；
     *   ② 物品不在本人名下领地内的箱子里（只认领地归属）；
     *   ③ 物品脱离自身管控已超过 12 小时（回到管辖内计时清零，再次脱离重新起算）。
     *
     * <p>三条同时满足才算达标。返回 null 表示达标；否则返回逐条判定明细。
     * 明细只允许出现在管理员可见的位置（web_sn_commands / 管理端详情），
     * 对玩家一律回 {@link #CUSTODY_DENY}——web_sn_lost.result 会展示给玩家，
     * 所以任何会写进报失单的回执都不能带明细。</p>
     *
     * @param row 该 SN 的登记行（含 detached_at / own_chest）
     */
    private String snCustodyDetail(String sn, Map<String, Object> row) {
        String owner = str(row.get("owner"));
        String locType = str(row.get("loc_type"));
        String locPlayer = str(row.get("loc_player"));

        // ① 在本人身上：在线时实盘扫背包（登记位置可能过期），离线才信登记位置
        boolean inBody;
        Player self = (owner == null || owner.isEmpty())
                ? null : Bukkit.getPlayerExact(owner);
        if (self != null) {
            inBody = snInInventory(self, sn);
        } else {
            inBody = "player".equals(locType) && !locPlayer.isEmpty()
                    && AreaProtection.samePlayer(owner, locPlayer);
        }

        // ② 在本人领地内的箱子里
        boolean inOwnChest = "container".equals(locType)
                && num(row.get("own_chest")) > 0;
        if (!inOwnChest && "container".equals(locType)) {
            // own_chest 还没被 updateLoc 写过（存量数据 / 领地易主）→ 按坐标现算
            try {
                Sdf1_login.AreaProtection.AreaConfig ac =
                        plugin.areaProtection != null
                                ? plugin.areaProtection.getArea(
                                str(row.get("loc_world")),
                                (int) num(row.get("loc_x")),
                                (int) num(row.get("loc_y")),
                                (int) num(row.get("loc_z")))
                                : null;
                inOwnChest = ac != null && ac.owner != null
                        && !ac.owner.isEmpty()
                        && AreaProtection.samePlayer(owner, ac.owner);
            } catch (Throwable ignored) {
            }
        }

        // ③ 脱离自身管控时长：detached_at 是脱离起始时刻（0 = 仍在管辖内，
        //    dropped 的起始已含 10 分钟宽限）。
        //    ★ 存量 / 漏登记数据（扫地机清走实物却没走 updateLoc 的）detached_at
        //    恒为 0，原先兜底到 last_seen 后仍可能算不出时长 → 玩家注销永远被卡。
        //    这里按登记位置推定起始：非"在本人身上"的一律视为已脱离，
        //    地上的再补 10 分钟宽限，保证计时一定在走。
        long detachedAt = num(row.get("detached_at"));
        if (detachedAt <= 0 && !"player".equals(locType)) {
            long ls = num(row.get("last_seen"));
            if (ls > 0) {
                detachedAt = ls + ("dropped".equals(locType)
                        ? DETACH_GRACE_MS : 0L);
            }
        }
        if (detachedAt <= 0) detachedAt = num(row.get("last_seen"));
        long heldMs = detachedAt > 0
                ? System.currentTimeMillis() - detachedAt : 0L;
        boolean detachedOk = !inBody && !inOwnChest
                && detachedAt > 0 && heldMs > CUSTODY_DETACH_MS;

        if (!inBody && !inOwnChest && detachedOk) return null;

        String held;
        if (inBody || inOwnChest) {
            held = "0（仍在自身管控内）";
        } else {
            long leftMs = CUSTODY_DETACH_MS - heldMs;
            held = (heldMs / 3600000L) + " 小时 "
                    + ((heldMs % 3600000L) / 60000L) + " 分"
                    + (leftMs > 0
                    ? "，还差 " + ((leftMs + 59999L) / 60000L)
                    + " 分钟才满 12 小时"
                    : "，已超 12 小时可办理");
        }
        return "在本人身上=" + (inBody ? "是" : "否")
                + " / 在本人领地箱子=" + (inOwnChest ? "是" : "否")
                + " / 脱离自身管控=" + held;
    }

    /** 登记位置的中文名（后台展示用） */
    private static String locCn(String lt) {
        if ("player".equals(lt)) return "在玩家身上";
        if ("container".equals(lt)) return "在容器内";
        if ("dropped".equals(lt)) return "在地上";
        if ("trash".equals(lt)) return "在垃圾箱";
        return lt == null || lt.isEmpty() ? "未知" : lt;
    }

    /**
     * 位置 + 脱离状态的中文描述（管理端 locate / 报失回执共用）。
     * detail == null 表示三条都已达标，此刻可注销、可补发。
     */
    private String custodyText(Map<String, Object> row) {
        String detail = snCustodyDetail(str(row.get("sn")), row);
        return "SN=" + str(row.get("sn"))
                + " 状态=" + statusCn(str(row.get("status")))
                + " 位置=" + locCn(str(row.get("loc_type")))
                + " 持有=" + str(row.get("loc_player"))
                + " 容器=" + str(row.get("container_type"))
                + " 领地=" + str(row.get("land_name"))
                + " | " + (detail == null
                ? "已脱离管控超 12 小时，可注销 / 补发"
                : detail);
    }

    /**
     * 注销：销毁对应 SN 物品（任务3）。
     *
     * <p>冷静期按操作类型分流（2026-09-29 修正）：
     * <ul>
     *   <li>管理员「强制注销」(force=true)：豁免三条件门槛，<b>且不启动 1 小时冷静期</b>
     *       ——强制操作按定义直接执行，不能把玩家锁进冷静期。</li>
     *   <li>「代办注销」(force=false)：照常走三条件门槛 + 注销成功后启动 1 小时冷静期。</li>
     * </ul>
     * 冷静期记录只新增、不清除：强制注销不会动玩家已有的冷静期记录。</p>
     */
    private String doCancel(String sn, String player, String reason,
                            boolean force) {
        Map<String, Object> row = getSn(sn);
        if (row == null) return "SN不存在";
        String owner = str(row.get("owner"));
        // ★ 永久锁定的 SN（发射器发射消耗等）：非强制不支持注销（2026-10-03）。
        //   管理员「强制注销」(force) 放行——否则被锁名额会把玩家永久卡死
        //   （无法注销 / 补发 / 同种类再签发），强制操作按定义拥有最终处置权。
        if (ST_ILLEGAL.equals(str(row.get("status"))) && !force) {
            logSn(sn, str(row.get("item_type")), "cancel_deny", player,
                    "SN已永久锁定（视为已使用/分解），不支持注销");
            return "该SN已被永久锁定（视为已使用/分解），不支持注销";
        }
        // ★ 统一门槛：不在本人身上 ∩ 不在本人领地箱子 ∩ 脱离管控 > 12 小时。
        //   管理员「强制注销」(force) 按定义豁免三项；「代办注销」照常校验。
        //   未达标对玩家只给通用回执，明细留在管理端。
        if (!force && snCustodyDetail(sn, row) != null) {
            logSn(sn, str(row.get("item_type")), "gate_deny", player,
                    "注销申请未达到办理条件");
            return CUSTODY_DENY;
        }
        // 找到并销毁实物：在线玩家背包 + 登记在案的容器
        boolean removedItem = removeSnFromWorld(sn, owner, row);
        int n = setStatus(sn, ST_CANCELLED, "注销:" + reason,
                System.currentTimeMillis());
        if (n == 0) return "注销失败";
        logSn(sn, str(row.get("item_type")), "cancel", owner, reason);
        // ★ 冷静期只在非强制时启动：强制注销跳过 1 小时冷静，代办注销照常进入。
        if (!force) {
            startCooldown(owner);
        } else {
            logSn(sn, str(row.get("item_type")), "cancel_force", owner,
                    "管理员强制注销，跳过1小时冷静期");
        }
        return (removedItem ? "已销毁实物并注销" : "已注销（未找到实物，稍后自动清理）")
                + (force ? "（强制注销，不进入冷静期）" : "，冷静期 1 小时");
    }

    /**
     * 补发：旧 SN 作废 → 生成新 SN 并发放（自动补发 + 签发新SN）。
     *
     * @param force 管理员强制操作：仅本次绕过冷静期（冷静期记录保留）
     */
    private String doReissue(String oldSn, String player, String itemType,
                             String reason, boolean force) {
        Map<String, Object> row = getSn(oldSn);
        if (row == null) return "SN不存在";
        String owner = str(row.get("owner"));
        // ★ 永久锁定的 SN（发射器发射消耗等）：非强制不支持补发（2026-10-03）；
        //   管理员强制补发放行（理由同 doCancel：强制=最终处置权，解锁死锁名额）
        if (ST_ILLEGAL.equals(str(row.get("status"))) && !force) {
            logSn(oldSn, itemType, "reissue_deny", player,
                    "SN已永久锁定（视为已使用/分解），不支持补发");
            return "该SN已被永久锁定（视为已使用/分解），不支持补发";
        }
        if (player != null && !player.isEmpty()) owner = player;
        if (itemType == null || itemType.isEmpty())
            itemType = str(row.get("item_type"));

        // ★ 统一门槛（与注销 / 报失同一套）；管理员强制补发豁免三项，
        //   自动补发、玩家申请补发均按三项校验，未达标只给通用回执。
        if (!force && snCustodyDetail(oldSn, row) != null) {
            logSn(oldSn, itemType, "gate_deny", player,
                    "补办申请未达到办理条件");
            return CUSTODY_DENY;
        }

        Player online = Bukkit.getPlayerExact(owner);
        if (online == null) return "玩家不在线，待其上线后再补发";

        // 旧 SN 作废 + 顺手回收旧实物（背包 / 登记在案的容器）
        setStatus(oldSn, ST_REISSUED, "补发->新SN:" + reason,
                System.currentTimeMillis());
        logSn(oldSn, itemType, "reissue_old", owner, reason);
        removeSnFromWorld(oldSn, owner, row);

        // ★ 只登记一次：新 SN 落库 → 据此造物 → 发放。
        // 旧版此处调 applySn 之后又调 giveNewSnItem（其内部会再 applySn 一次），
        // 被"同种类仅1个"挡下，永远返回"补发登记成功但发放失败"，物品根本没给出去。
        String newSn = force
                ? applySnForce(online, itemType, typeName(itemType))
                : applySn(online, itemType, typeName(itemType));
        if (newSn == null) return "补发申领被拒（见玩家提示）";

        ItemStack fresh = buildSnItem(online, itemType, newSn);
        if (fresh == null) {
            // 未知类型：回滚刚登记的新 SN，别白占"同种类仅1个"名额
            destroySn(newSn, "补发物品构建失败回滚");
            return "补发登记成功但物品构建失败";
        }
        giveTo(online, fresh);
        logSn(newSn, itemType, "reissue_new", owner, reason);
        return "补发成功，新SN=" + newSn;
    }

    /**
     * 办理签发：管理员在后台为玩家签发新设备（新 SN）。
     *
     * <p>前置校验在 PHP 端完成（旧 SN 已注销解绑 + 注销冷静期已结束，
     * 未达标分别提示是哪一条），Java 侧做兜底复核，防止绕过接口直接入队。
     *
     * <p>与「强制补发」(doReissue) 的区别：issue 不作废旧 SN
     * （旧 SN 本就已注销/销毁/补发了结），直接按类型登记新 SN 并发放；
     * 因此也不需要 removeSnFromWorld 回收旧实物。</p>
     *
     * @param force 管理员强制操作（PHP 侧 issue 固定传 0）：仅跳过冷静期兜底校验
     */
    private String doIssue(String sn, String player, String itemType,
                           String reason, boolean force) {
        Map<String, Object> row = getSn(sn);
        if (row == null) return "SN不存在";
        String owner = (player != null && !player.isEmpty())
                ? player : str(row.get("owner"));
        if (itemType == null || itemType.isEmpty())
            itemType = str(row.get("item_type"));

        // ★ 兜底复核①：旧 SN 必须已了结（PHP 已校验，此处防绕过）
        String st = str(row.get("status"));
        if (!ST_CANCELLED.equals(st) && !ST_DESTROYED.equals(st)
                && !ST_REISSUED.equals(st)) {
            return "旧SN尚未注销解绑，不能办理签发";
        }
        // ★ 兜底复核②：本地冷静期未结束不办理（权威值在本服 sn_cooldown）
        if (!force) {
            long cd = getCooldownUntil(owner);
            long now = System.currentTimeMillis();
            if (cd > now) {
                long mins = (cd - now + 59999) / 60000;
                return "注销冷静期未结束，还剩约 " + mins + " 分钟";
            }
        }

        Player online = Bukkit.getPlayerExact(owner);
        if (online == null) return "玩家不在线，待其上线后再办理";

        // 登记新 SN（同种类仅 1 个 仍然生效；旧 SN 已了结不会占名额）
        String newSn = force
                ? applySnForce(online, itemType, typeName(itemType))
                : applySn(online, itemType, typeName(itemType));
        if (newSn == null) return "办理申领被拒（见玩家提示）";

        ItemStack fresh = buildSnItem(online, itemType, newSn);
        if (fresh == null) {
            // 未知类型：回滚刚登记的新 SN，别白占"同种类仅1个"名额
            destroySn(newSn, "办理签发物品构建失败回滚");
            return "办理登记成功但物品构建失败";
        }
        giveTo(online, fresh);
        logSn(newSn, itemType, "issue_admin", owner, reason);
        return "办理成功，新SN=" + newSn;
    }

    /**
     * 顶层「办理签发」（issue_new）：只凭 玩家ID + 设备类型 直接签发，
     * 不依赖任何旧 SN 记录——管理端 SN 页顶层表单走的就是这条链路。
     *
     * <p>与 {@link #doIssue} 的区别：doIssue 必须先查到旧 SN 并复核它
     * "已注销/销毁/补发了结"；本方法没有旧 SN，改为直接核对
     * 「同种类仅 1 个」名额与冷静期，然后按类型登记发放。</p>
     *
     * <p>名额/冷静期查的是本服本地库（权威），不在 PHP 侧预判——
     * web_item_sn 是 Java 推送的副本，可能滞后 30 秒。</p>
     *
     * @param force 管理员强制操作（PHP 顶层入口固定传 0）：仅跳过冷静期校验，
     *              「同种类仅 1 个」不豁免
     */
    private String doIssueNew(String player, String itemType, String reason,
                              boolean force) {
        if (player == null || player.isEmpty()) return "缺少玩家ID";
        if (itemType == null || itemType.isEmpty()) return "缺少设备类型";
        if (!TYPE_MENU.equals(itemType) && !TYPE_ECHO.equals(itemType)
                && !TYPE_WAND.equals(itemType) && !TYPE_PVP.equals(itemType)) {
            return "未知设备类型: " + itemType;
        }

        Player online = Bukkit.getPlayerExact(player);
        if (online == null) return "玩家不在线，待其上线后再办理";

        String name = online.getName();
        // ★ 名额校验（findActive 看 active/lost/illegal 三种占位状态）
        Map<String, Object> old = findActive(name, itemType);
        if (old != null) {
            return "该玩家已有有效" + typeName(itemType) + "登记（SN="
                    + str(old.get("sn")) + "），请先注销/补发后再签发";
        }
        if (!force) {
            long cd = getCooldownUntil(name);
            long now = System.currentTimeMillis();
            if (cd > now) {
                long mins = (cd - now + 59999) / 60000;
                return "注销冷静期未结束，还剩约 " + mins + " 分钟";
            }
        }

        String newSn = force
                ? applySnForce(online, itemType, typeName(itemType))
                : applySn(online, itemType, typeName(itemType));
        if (newSn == null) return "办理申领被拒（见玩家提示）";

        ItemStack fresh = buildSnItem(online, itemType, newSn);
        if (fresh == null) {
            // 类型能过上面的白名单却构建失败 → 回滚，别白占"同种类仅1个"名额
            destroySn(newSn, "顶层办理签发物品构建失败回滚");
            return "办理登记成功但物品构建失败";
        }
        giveTo(online, fresh);
        logSn(newSn, itemType, "issue_admin", name,
                reason.isEmpty() ? "管理员顶层办理签发" : reason);
        return "办理成功，新SN=" + newSn;
    }

    /**
     * 报失核查：按统一三条件门槛给结论。
     * force=1（管理员手动核查，回执只进 web_sn_commands，管理端可见）时
     * 连逐条判定一起回；force=0（玩家报失，回执会写进 web_sn_lost.result
     * 并展示给玩家）时只回通用回执，不透露是哪一条没达标。
     */
    private String doReportCheck(String sn, String player, boolean force) {
        Map<String, Object> row = getSn(sn);
        if (row == null) return "SN不存在";
        String detail = snCustodyDetail(sn, row);
        if (detail != null) {
            if (force) return CUSTODY_DENY + "（" + detail + "）";
            logSn(sn, str(row.get("item_type")), "gate_deny", player,
                    "报失核查未达到办理条件");
            return CUSTODY_DENY;
        }
        return "可补发:" + custodyText(row);
    }

    /** 该 SN 的实物是否在指定玩家背包里（含主/副手兜底） */
    private boolean snInInventory(Player p, String sn) {
        try {
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType() == Material.AIR) continue;
                if (sn.equals(readSn(it))) return true;
            }
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main != null && main.getType() != Material.AIR
                    && sn.equals(readSn(main))) return true;
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off != null && off.getType() != Material.AIR
                    && sn.equals(readSn(off))) return true;
        } catch (Throwable t) {
            throttleErr("snininv:" + t.getMessage());
        }
        return false;
    }

    /** 从在线玩家背包移除该 SN 的实物（不看登记位置） */
    private boolean removeSnFromWorld(String sn, String owner) {
        return removeSnFromWorld(sn, owner, null);
    }

    /**
     * 从在线玩家背包 + 登记在案的容器移除该 SN 的实物。
     * 容器只处理"区块已加载"的（不强制加载区块）；没抠到也没关系——
     * 状态已是 cancelled/reissued，死 SN 兜底扫描会在其进背包/被使用时回收。
     *
     * @param row 该 SN 的登记行（可为 null，为 null 时不扫容器）
     */
    private boolean removeSnFromWorld(String sn, String owner,
                                      Map<String, Object> row) {
        boolean hit = false;
        if (owner != null && !owner.isEmpty()) {
            Player p = Bukkit.getPlayerExact(owner);
            if (p != null) hit |= removeSnFromInventory(p, sn);
        }
        if (!hit && row != null) {
            String locType = str(row.get("loc_type"));
            if ("container".equals(locType)) {
                hit |= removeFromContainer(sn, row);
            } else if ("player".equals(locType)) {
                // 登记在别人手上（异常转移）→ 也去那个人背包里抠掉，
                // 否则"已注销的实物还在别人手里接着用"
                String holder = str(row.get("loc_player"));
                if (!holder.isEmpty() && !holder.equals(owner)) {
                    Player hp = Bukkit.getPlayerExact(holder);
                    if (hp != null) hit |= removeSnFromInventory(hp, sn);
                }
            }
        }
        return hit;
    }

    /** 从某玩家背包（含副手）里抠掉指定 SN 的实物 */
    private boolean removeSnFromInventory(Player p, String sn) {
        boolean hit = false;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR) continue;
            if (sn.equals(readSn(it))) {
                p.getInventory().setItem(i, null);
                hit = true;
            }
        }
        // 主/副手兜底（部分版本 getContents() 不含副手槽）
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main != null && main.getType() != Material.AIR
                && sn.equals(readSn(main))) {
            p.getInventory().setItemInMainHand(null);
            hit = true;
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && off.getType() != Material.AIR
                && sn.equals(readSn(off))) {
            p.getInventory().setItemInOffHand(null);
            hit = true;
        }
        return hit;
    }

    /** 按登记坐标到容器里抠掉指定 SN 的实物（注销时物在箱子的场景） */
    private boolean removeFromContainer(String sn, Map<String, Object> row) {
        try {
            String wn = str(row.get("loc_world"));
            if (wn.isEmpty()) return false;
            World w = Bukkit.getWorld(wn);
            if (w == null) return false;
            int x = (int) num(row.get("loc_x"));
            int y = (int) num(row.get("loc_y"));
            int z = (int) num(row.get("loc_z"));
            // 区块没加载就不动（避免强制加载），交由死 SN 兜底扫描处理
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block b = w.getBlockAt(x, y, z);
            BlockState bs = b.getState();
            if (!(bs instanceof InventoryHolder)) return false;
            Inventory inv = ((InventoryHolder) bs).getInventory();
            ItemStack[] c = inv.getContents();
            boolean hit = false;
            for (int i = 0; i < c.length; i++) {
                if (c[i] == null || c[i].getType() == Material.AIR) continue;
                if (sn.equals(readSn(c[i]))) {
                    inv.setItem(i, null);
                    hit = true;
                }
            }
            return hit;
        } catch (Throwable t2) {
            throttleErr("rmcontainer:" + t2.getMessage());
            return false;
        }
    }

    private void markCommandDone(long id, String result) {
        try {
            Connection db = plugin.getDb().getConnection();
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE sn_commands SET status = 'done', result = ?,"
                            + " done_at = ? WHERE id = ?");
            ps.setString(1, result);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throttleErr("cmdack:" + e.getMessage());
        }
    }

    // ==================== 工具 ====================

    // ==================== 打印 SN 记录（/printer item）====================

    /**
     * 导出 SN 记录（/printer item [玩家名]）。异步生成 Excel(.xls) 到
     * plugins/Sdf1_login/printer/，由 BondPrinter.cleanupOldFiles 24 小时清理。
     */
    public void printSnRecords(final CommandSender sender,
                               final String player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                doPrintSnRecords(sender, player);
            }
        });
    }

    private void doPrintSnRecords(CommandSender sender, String player) {
        try {
            SimpleDateFormat df =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Connection db = plugin.getDb().getConnection();
            boolean byPlayer = player != null && !player.isEmpty();

            List<String[]> snRows = new ArrayList<String[]>();
            String sql = "SELECT sn, item_type, owner, status, issue_time,"
                    + " loc_type, loc_player, loc_world, loc_x, loc_y, loc_z,"
                    + " container_type, in_land, land_name, last_seen,"
                    + " remark, bind_reason FROM item_sn";
            if (byPlayer) sql += " WHERE owner = ?";
            sql += " ORDER BY issue_time DESC";
            PreparedStatement ps = db.prepareStatement(sql);
            if (byPlayer) ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String world = rs.getString("loc_world");
                String pos = (world == null || world.isEmpty()) ? ""
                        : world + "(" + rs.getInt("loc_x") + ","
                        + rs.getInt("loc_y") + "," + rs.getInt("loc_z") + ")";
                snRows.add(new String[]{
                        str(rs.getString("sn")),
                        typeName(rs.getString("item_type")),
                        str(rs.getString("owner")),
                        statusCn(rs.getString("status")),
                        fmtTime(rs.getLong("issue_time"), df),
                        str(rs.getString("loc_type")),
                        str(rs.getString("loc_player")),
                        pos,
                        str(rs.getString("container_type")),
                        rs.getInt("in_land") == 1 ? "是" : "否",
                        str(rs.getString("land_name")),
                        fmtTime(rs.getLong("last_seen"), df),
                        str(rs.getString("remark")),
                        str(rs.getString("bind_reason"))
                });
            }
            rs.close();
            ps.close();

            List<String[]> logRows = new ArrayList<String[]>();
            String sql2 = "SELECT sn, item_type, action, player, detail, time"
                    + " FROM sn_log";
            if (byPlayer) sql2 += " WHERE player = ?";
            sql2 += " ORDER BY time DESC LIMIT 5000";
            PreparedStatement ps2 = db.prepareStatement(sql2);
            if (byPlayer) ps2.setString(1, player);
            ResultSet rs2 = ps2.executeQuery();
            while (rs2.next()) {
                logRows.add(new String[]{
                        str(rs2.getString("sn")),
                        typeName(rs2.getString("item_type")),
                        actionCn(rs2.getString("action")),
                        str(rs2.getString("player")),
                        str(rs2.getString("detail")),
                        fmtTime(rs2.getLong("time"), df)
                });
            }
            rs2.close();
            ps2.close();

            if (snRows.isEmpty()) {
                if (sender != null) {
                    sender.sendMessage("§c没有SN记录"
                            + (byPlayer ? "（" + player + "）" : ""));
                }
                return;
            }

            File dir = new File(plugin.getDataFolder(), "printer");
            if (!dir.exists()) dir.mkdirs();
            String fileName = "SN记录_" + (byPlayer ? player + "_" : "")
                    + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                    .format(new Date()) + ".xls";
            File file = new File(dir, fileName);

            PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            try {
                pw.println("<?xml version=\"1.0\"?>");
                pw.println("<?mso-application progid=\"Excel.Sheet\"?>");
                pw.println("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"");
                pw.println("  xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">");

                pw.println("  <Worksheet ss:Name=\"SN记录\">");
                pw.println("    <Table>");
                xmlRow(pw, new String[]{"SN", "物品类型", "登记主", "状态",
                        "申领时间", "位置类型", "当前持有", "坐标", "容器类型",
                        "在领地", "领地名", "最后发现", "备注", "绑定原因"});
                for (String[] r : snRows) xmlRow(pw, r);
                pw.println("    </Table>");
                pw.println("  </Worksheet>");

                pw.println("  <Worksheet ss:Name=\"SN日志\">");
                pw.println("    <Table>");
                xmlRow(pw, new String[]{"SN", "物品类型", "动作", "玩家",
                        "详情", "时间"});
                for (String[] r : logRows) xmlRow(pw, r);
                pw.println("    </Table>");
                pw.println("  </Worksheet>");

                pw.println("</Workbook>");
                pw.flush();
            } finally {
                pw.close();
            }

            if (sender != null) {
                sender.sendMessage("§a[打印] §fSN记录 §a已导出");
                sender.sendMessage("§7文件: §f" + fileName);
                sender.sendMessage("§7SN §f" + snRows.size()
                        + " §7条，日志 §f" + logRows.size() + " §7条");
                sender.sendMessage("§724小时后自动删除");
            }
        } catch (Exception e) {
            if (sender != null) {
                sender.sendMessage("§c导出SN记录失败: " + e.getMessage());
            }
            plugin.getLogger().severe("[SN] 导出失败: " + e.getMessage());
        }
    }

    private void xmlRow(PrintWriter pw, String[] cells) {
        pw.println("      <Row>");
        for (String c : cells) {
            pw.println("        <Cell><Data ss:Type=\"String\">"
                    + escXml(c) + "</Data></Cell>");
        }
        pw.println("      </Row>");
    }

    private String escXml(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String fmtTime(long ms, SimpleDateFormat df) {
        if (ms <= 0) return "";
        return df.format(new Date(ms));
    }

    /** SN 状态中文名（管理端回执、领地选区校验提示共用） */
    public String statusCn(String s) {
        if (s == null) return "";
        if (ST_ACTIVE.equals(s)) return "有效";
        if (ST_DESTROYED.equals(s)) return "已销毁解绑";
        if (ST_CANCELLED.equals(s)) return "已注销";
        if (ST_LOST.equals(s)) return "报失处理中";
        if (ST_ILLEGAL.equals(s)) return "非法永久绑定";
        if (ST_REISSUED.equals(s)) return "已补发作废";
        return s;
    }

    private String actionCn(String a) {
        if (a == null || a.isEmpty()) return "";
        if ("issue".equals(a)) return "申领";
        if ("issue_admin".equals(a)) return "办理签发";
        if ("cancel_force".equals(a)) return "强制注销";
        if ("backfill".equals(a)) return "存量补登记";
        if ("cancel".equals(a)) return "注销";
        if ("reissue".equals(a)) return "补发";
        if ("destroy".equals(a)) return "销毁解绑";
        if ("report".equals(a)) return "报失";
        if ("pickup_mismatch".equals(a)) return "拾取归属不符";
        if ("illegal_craft".equals(a)) return "非法合成";
        if ("illegal_burn".equals(a)) return "非法熔炼";
        if ("bind".equals(a)) return "永久绑定";
        if ("locate".equals(a)) return "位置查询";
        if ("recycle".equals(a)) return "以旧换新";
        if ("recycle_dup".equals(a)) return "重复品回收";
        if ("reclaim".equals(a)) return "收回作废实物";
        if ("trash_in".equals(a)) return "投入垃圾箱";
        if ("recovered".equals(a)) return "垃圾箱取回";
        if ("lost_timeout".equals(a)) return "超时丢失";
        return a;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> rsToMap(ResultSet rs) throws SQLException {
        if (!rs.next()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return m;
    }

    private void throttleErr(String msg) {
        long now = System.currentTimeMillis();
        Long last = errThrottle.get(msg);
        if (last != null && now - last < 30000L) return;
        errThrottle.put(msg, now);
        plugin.getLogger().warning("[SN] " + msg);
    }

    /** 极简 JSON 数组序列化（仅扁平 Map，值为基本类型） */
    private String toJsonArray(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> m : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            boolean f2 = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!f2) sb.append(",");
                f2 = false;
                sb.append("\"").append(escape(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean)
                    sb.append(v);
                else sb.append("\"").append(escape(String.valueOf(v)))
                        .append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append("=")
                        .append(java.net.URLEncoder.encode(
                                e.getValue() == null ? "" : e.getValue(),
                                "UTF-8"));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /**
     * 极简 JSON 数组解析（仅解析 api/sn.php 返回的命令数组，
     * 形如 [{"id":1,"cmd":"cancel","sn":"..."}]）。
     * 不引入 gson 依赖，按字符串字面量与数字切分。
     */
    private List<Map<String, Object>> parseJsonArray(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null) return out;
        int i = json.indexOf('[');
        if (i < 0) return out;
        i++;
        int n = json.length();
        while (i < n) {
            while (i < n && json.charAt(i) != '{') {
                if (json.charAt(i) == ']') return out;
                i++;
            }
            if (i >= n) break;
            int start = i;
            int depth = 0;
            boolean inStr = false, esc = false;
            while (i < n) {
                char c = json.charAt(i);
                if (inStr) {
                    if (esc) esc = false;
                    else if (c == '\\') esc = true;
                    else if (c == '"') inStr = false;
                } else {
                    if (c == '"') inStr = true;
                    else if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) { i++; break; }
                    }
                }
                i++;
            }
            Map<String, Object> m = parseObject(json.substring(start, i));
            if (!m.isEmpty()) out.add(m);
            while (i < n && json.charAt(i) != '{' && json.charAt(i) != ']') i++;
            if (i < n && json.charAt(i) == ']') break;
        }
        return out;
    }

    private Map<String, Object> parseObject(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        int i = 1, n = s.length() - 1;
        while (i < n) {
            while (i < n && s.charAt(i) != '"') i++;
            if (i >= n) break;
            i++;
            StringBuilder key = new StringBuilder();
            boolean esc = false;
            while (i < n) {
                char c = s.charAt(i);
                if (esc) { key.append(c); esc = false; }
                else if (c == '\\') esc = true;
                else if (c == '"') { i++; break; }
                else key.append(c);
                i++;
            }
            while (i < n && s.charAt(i) != '"'
                    && s.charAt(i) != '-' && !Character.isDigit(s.charAt(i))
                    && s.charAt(i) != 't' && s.charAt(i) != 'f'
                    && s.charAt(i) != 'n') i++;
            if (i >= n) break;
            Object val;
            if (s.charAt(i) == '"') {
                i++;
                StringBuilder v = new StringBuilder();
                esc = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (esc) { v.append(c); esc = false; }
                    else if (c == '\\') esc = true;
                    else if (c == '"') { i++; break; }
                    else v.append(c);
                    i++;
                }
                val = v.toString();
            } else if (s.startsWith("true", i)) { val = Boolean.TRUE; i += 4; }
            else if (s.startsWith("false", i)) { val = Boolean.FALSE; i += 5; }
            else if (s.startsWith("null", i)) { val = null; i += 4; }
            else {
                int st = i;
                while (i < n && (Character.isDigit(s.charAt(i))
                        || s.charAt(i) == '-' || s.charAt(i) == '.'
                        || s.charAt(i) == 'e' || s.charAt(i) == 'E'
                        || s.charAt(i) == '+')) i++;
                String numStr = s.substring(st, i);
                try {
                    if (numStr.contains(".") || numStr.contains("e")
                            || numStr.contains("E"))
                        val = Double.parseDouble(numStr);
                    else val = Long.parseLong(numStr);
                } catch (Exception ex) { val = numStr; }
            }
            m.put(key.toString(), val);
            while (i < n && s.charAt(i) != '"') i++;
        }
        return m;
    }
}
