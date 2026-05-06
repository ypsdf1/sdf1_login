package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public class VerificationManager {

    private final Main plugin;

    public VerificationManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (uuid.toString().startsWith("00000000-0000-0000-0009-")) {
            return true;
        }
        if (player.hasMetadata("isgeyserplayer")) {
            return true;
        }
        if (player.hasPermission("geyser.player")) {
            return true;
        }
        try {
            Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
            if (floodgate == null) {
                floodgate = Bukkit.getPluginManager().getPlugin("Floodgate");
            }
            if (floodgate != null && floodgate.isEnabled()) {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                boolean isBedrock = (boolean) apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
                if (isBedrock) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public boolean isOnlineMode() {
        return Bukkit.getOnlineMode();
    }

    public void verifyPremiumAsync(Player player, Consumer<Boolean> callback) {
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean result = checkMojangApi(name);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    private boolean checkMojangApi(String name) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Sdf1_login");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Mojang API failed: " + name, e);
            return false;
        }
    }
}
