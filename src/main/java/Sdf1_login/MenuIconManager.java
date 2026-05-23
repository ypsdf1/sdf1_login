package Sdf1_login;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public class MenuIconManager {

    private final Main plugin;
    private final NamespacedKey iconKey;

    public MenuIconManager(Main plugin) {
        this.plugin = plugin;
        this.iconKey = new NamespacedKey(
                plugin, "custom_menu_icon");
    }

    // ========== GUIManager 调用的方法 ==========

    /**
     * Base64 → ItemStack
     * 对应 GUIManager 中的
     * plugin.getMenuIconMgr().deserializeItem(...)
     */
    public ItemStack deserializeItem(String b64) {
        if (b64 == null || b64.isEmpty())
            return null;
        try {
            byte[] data =
                    Base64.getDecoder().decode(b64);
            ByteArrayInputStream bais =
                    new ByteArrayInputStream(data);
            BukkitObjectInputStream is =
                    new BukkitObjectInputStream(bais);
            Object obj = is.readObject();
            is.close();
            if (obj instanceof ItemStack)
                return (ItemStack) obj;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * ItemStack → Base64
     * 对应 GUIManager 中的
     * plugin.getMenuIconMgr().serializeItem(...)
     */
    public String serializeItem(ItemStack item) {
        if (item == null) return null;
        try {
            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream();
            BukkitObjectOutputStream os =
                    new BukkitObjectOutputStream(baos);
            os.writeObject(item);
            os.close();
            return Base64.getEncoder()
                    .encodeToString(
                            baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 给物品打上菜单图标标记
     * 对应 GUIManager 中的
     * plugin.getMenuIconMgr().ensureMenuIconTag(...)
     */
    public void ensureMenuIconTag(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(
                iconKey,
                PersistentDataType.STRING,
                "menu");
        item.setItemMeta(meta);
    }

    /**
     * 获取图标Key
     * 对应 GUIManager 中的
     * plugin.getMenuIconMgr().getIconKey()
     */
    public NamespacedKey getIconKey() {
        return iconKey;
    }

    // ========== LoginManager / Main 调用的方法 ==========

    /**
     * 获取玩家自定义图标物品
     * 有返回物品，无返回null
     */
    public ItemStack getIcon(Player player) {
        String b64 = plugin.getDb()
                .getMenuIcon(player.getName());
        if (b64 == null || b64.isEmpty())
            return null;
        ItemStack item = deserializeItem(b64);
        if (item == null) return null;
        ensureMenuIconTag(item);
        return item;
    }

    /**
     * 保存图标到DB
     */
    public void saveIcon(Player player,
                         ItemStack item) {
        String b64 = serializeItem(item);
        if (b64 != null) {
            plugin.getDb().saveMenuIcon(
                    player.getName(), b64,
                    item.getType().name());
        }
    }

    /**
     * 删除图标
     */
    public void removeIcon(Player player) {
        plugin.getDb().deleteMenuIcon(
                player.getName());
    }

    /**
     * 检查物品是否是自定义菜单图标
     */
    public boolean isCustomMenuIcon(
            ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer()
                .has(iconKey,
                        PersistentDataType.STRING);
    }
}
