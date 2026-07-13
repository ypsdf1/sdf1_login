package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;

/**
 * 背包滚动自动备份。
 * <p>
 * ★ 功能：
 * 1. 由 Main 的 15 分钟定时任务驱动，遍历所有在线玩家。
 * 2. 在 PVP 竞技场 / 练习场（测试场）的玩家不备份 —— 避免竞技场装备覆盖真实背包。
 * 3. 序列化 主背包 / 护甲 / 副手(extra) / 等级 / 经验，写入 inv_backup_auto 表。
 * 4. 每玩家保留最近 3 份（滚动覆盖），由 DatabaseManager.saveAutoBackup 负责清理。
 * <p>
 * 与登录时的 saveInventoryBackup 共用同一套存储语义（level=玩家等级，
 * experience=当前等级进度 p.getExp()），便于后续统一还原。
 */
public class InventoryAutoBackup {

    private final Main plugin;

    /** 备份周期：15 分钟（单位 tick） */
    public static final long INTERVAL_TICKS = 15L * 60L * 20L;

    public InventoryAutoBackup(Main plugin) {
        this.plugin = plugin;
    }

    /** 对所有在线玩家执行一次滚动备份 */
    public void runBackup() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                backupPlayer(p);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[自动备份] 玩家 " + p.getName()
                                + " 备份失败: " + e.getMessage());
            }
        }
    }

    private void backupPlayer(Player p) {
        String name = p.getName();

        // ★ 跳过 PVP 竞技场：竞技场内不备份，避免 PVP 装备覆盖真实背包
        if (plugin.getPVPArenaManager().isInPVPArena(name)) {
            return;
        }
        // ★ 跳过练习场 / 测试世界
        if (plugin.getPVPTestManager().isInTestWorld(p)) {
            return;
        }

        ItemStack[] contents = p.getInventory().getContents();
        ItemStack[] armor = p.getInventory().getArmorContents();
        ItemStack[] extra = p.getInventory().getExtraContents();

        String contentsB64, armorB64, extraB64;
        try {
            contentsB64 = InventorySerializer.toBase64(contents);
            armorB64 = InventorySerializer.toBase64(armor);
            extraB64 = InventorySerializer.toBase64(extra);
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "[自动备份] 序列化失败 (" + name + "): "
                            + e.getMessage());
            return;
        }

        int level = p.getLevel();
        double experience = p.getExp(); // 当前等级进度 0.0~1.0
        plugin.getDb().saveAutoBackup(
                name, contentsB64, armorB64, extraB64,
                level, experience);
    }
}
