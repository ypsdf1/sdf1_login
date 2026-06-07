package Sdf1_login;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class IronBlockElevator implements Listener {

    private static final int MAX_RANGE = 64;
    private static final int MIN_RANGE = 5; // 屏蔽±5格以内的铁块
    private final Set<UUID> cooldown = new HashSet<>();

    /**
     * 检查指定位置脚下是否是铁块
     */
    private boolean isIronBlockBelow(Location loc) {
        Block block = loc.getWorld().getBlockAt(
                loc.getBlockX(), loc.getBlockY() - 1,
                loc.getBlockZ());
        return block.getType() == Material.IRON_BLOCK;
    }

    /**
     * 从起始位置向上或向下搜索铁块并传送
     * @param dir 1=向上 -1=向下
     */
    private boolean tryTeleport(Player p, int dir) {
        Location loc = p.getLocation();
        int baseY = loc.getBlockY();

        for (int i = MIN_RANGE; i < MAX_RANGE; i++) {
            int y = baseY + i * dir;
            Block block = loc.getWorld().getBlockAt(
                    loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType() == Material.IRON_BLOCK) {
                cooldown.add(p.getUniqueId());
                Location dest = new Location(
                        loc.getWorld(),
                        loc.getX(),
                        block.getY() + 1,
                        loc.getZ());
                p.teleport(dest);
                org.bukkit.Bukkit.getScheduler()
                        .runTaskLater(
                                org.bukkit.Bukkit.getPluginManager()
                                        .getPlugin("Sdf1_login"),
                                () -> cooldown.remove(
                                        p.getUniqueId()),
                                15L);
                return true;
            }
        }
        return false;
    }

    // ===== 下蹲 → 向下传送 =====
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (cooldown.contains(p.getUniqueId())) return;
        // 必须站在铁块上
        if (!isIronBlockBelow(p.getLocation())) return;
        tryTeleport(p, -1);
    }

    // ===== 跳跃 → 向上传送 =====
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (cooldown.contains(p.getUniqueId())) return;
        if (p.isSneaking()) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (to.getY() <= from.getY()) return;
        if (p.getVelocity().getY() <= 0) return;

        // 关键：检查跳跃前的位置（from）脚下是否是铁块
        // 跳跃后玩家已离开铁块上方，检查 to 会失败
        if (!isIronBlockBelow(from)) return;

        tryTeleport(p, 1);
    }
}
