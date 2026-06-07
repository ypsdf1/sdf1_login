package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sdf1_game寻宝系统桥接类
 * 用于Sdf1_login调用Sdf1_game的clearPlayerAllClaims方法
 */
public class TreasureBridge {

    private Object treasureManager;
    private Method clearPlayerAllClaimsMethod;
    private boolean hooked = false;

    /**
     * 桥接Sdf1_game插件
     * @return 是否桥接成功
     */
    public boolean hook() {
        Plugin sdf1_game = Bukkit.getPluginManager()
                .getPlugin("Sdf1_game");

        if (sdf1_game == null || !sdf1_game.isEnabled()) {
            return false;
        }

        try {
            // 获取Sdf1_game的Main实例
            Object mainInstance = sdf1_game;
            
            // 获取TreasureManager实例
            treasureManager = findTreasureManager(mainInstance);
            if (treasureManager == null) {
                return false;
            }

            // 获取clearPlayerAllClaims方法
            clearPlayerAllClaimsMethod = treasureManager.getClass()
                    .getMethod("clearPlayerAllClaims", String.class);

            hooked = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清空指定玩家的所有领取记录
     * @param playerName 玩家名
     * @return 清除的记录数量，失败返回-1
     */
    public int clearPlayerAllClaims(String playerName) {
        if (!isHooked()) return -1;
        try {
            int result = (int) clearPlayerAllClaimsMethod.invoke(
                    treasureManager, playerName);
            return result;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 查找TreasureManager实例
     * @param mainInstance Sdf1_game的Main实例
     * @return TreasureManager实例
     */
    private Object findTreasureManager(Object mainInstance) {
        Class<?> c = mainInstance.getClass();

        // 策略1: getTreasureManager() 方法
        try {
            Method m = c.getMethod("getTreasureManager");
            Object r = m.invoke(mainInstance);
            if (r != null) return r;
        } catch (Exception ignored) {
        }

        // 策略2: treasureManager 字段
        try {
            Field f = c.getDeclaredField("treasureManager");
            f.setAccessible(true);
            Object r = f.get(mainInstance);
            if (r != null) return r;
        } catch (Exception ignored) {
        }

        // 策略3: 遍历字段按类型名查找
        try {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName()
                        .equals("TreasureManager")) {
                    f.setAccessible(true);
                    Object r = f.get(mainInstance);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * 是否已桥接
     * @return 桥接状态
     */
    public boolean isHooked() {
        return hooked;
    }
}