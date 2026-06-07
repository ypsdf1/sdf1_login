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
    private final Set<UUID> cooldown = new HashSet<>();

    private boolean tryTeleport(Player p, int dir) {
        Location loc = p.getLocation();
        int start = loc.getBlockY() + dir * 2;

        for (int i = 0; i < MAX_RANGE; i++) {
            int y = start + i * dir;
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
                                () -> cooldown.remove(p.getUniqueId()),
                                10L);
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (cooldown.contains(p.getUniqueId())) return;
        tryTeleport(p, -1);
    }

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

        tryTeleport(p, 1);
    }
}
