package Sdf1_login;

import org.bukkit.Material;
import java.util.UUID;

public class RefundRecord {
    public final UUID playerUUID;
    public final String playerName;
    public final String itemId;
    public final String itemName;
    public final Material material;
    public final Material shulkerMaterial;
    public int amount;
    public int totalPaid;
    public final long timestamp;

    // 普通购买
    public RefundRecord(UUID uuid, String name,
                        String itemId, String itemName,
                        Material material, int amount,
                        int totalPaid, long timestamp) {
        this.playerUUID = uuid;
        this.playerName = name;
        this.itemId = itemId;
        this.itemName = itemName;
        this.material = material;
        this.shulkerMaterial = null;
        this.amount = amount;
        this.totalPaid = totalPaid;
        this.timestamp = timestamp;
    }

    // 盒装购买
    public RefundRecord(UUID uuid, String name,
                        String itemId, String itemName,
                        Material material,
                        Material shulkerMat,
                        int amount, int totalPaid,
                        long timestamp) {
        this.playerUUID = uuid;
        this.playerName = name;
        this.itemId = itemId;
        this.itemName = itemName;
        this.material = material;
        this.shulkerMaterial = shulkerMat;
        this.amount = amount;
        this.totalPaid = totalPaid;
        this.timestamp = timestamp;
    }

    public boolean isExpired() {
        return System.currentTimeMillis()
                - timestamp > 300000L;
    }

    public int getSecondsLeft() {
        long left = 300000L
                - (System.currentTimeMillis()
                - timestamp);
        return (int) Math.max(0, left / 1000);
    }
}
