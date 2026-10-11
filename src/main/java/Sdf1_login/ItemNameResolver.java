package Sdf1_login;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * 物品名翻译中间人（2026-10-11，v2 改用官方 zh_cn.json）。
 * <p>
 * 玩家在聊天栏输入物品 ID 时，不是人人都能背下 MC 每个物品的英文 ID。
 * 本类把中文名（如「钻石」）翻译为标准英文 Material 名（如 diamond），
 * 入库统一用英文标准名，回收/匹配时按英文名比对。
 * <p>
 * 数据来源：{@link OfficialTranslations#reverseMap()} —— 官方原版 zh_cn.json
 * （plugins/Sdf1_login/zh_cn.json，启动时自动加载/下载，领地容器查询等已在用）。
 * 不再维护手工中文映射表。
 * <p>
 * 解析顺序：
 * 1. 英文直通（Material.matchMaterial，支持 minecraft: 前缀与 legacy 名）
 * 2. 官方中文精确（zh_cn.json 反向索引）
 * 3. 归一化后精确（去空格、去 minecraft: 前缀）
 * 4. 唯一前缀匹配（输入「钻」时，若全表只有一个候选以它开头则放行并回填提示）
 */
public final class ItemNameResolver {

    private ItemNameResolver() {}

    /**
     * 解析玩家输入为 Material。
     *
     * @param input 玩家输入（英文 ID / minecraft: 前缀 / 官方中文名）
     * @return Material；无法解析返回 null
     */
    public static Material resolve(String input) {
        return resolveWithHint(input, null);
    }

    /**
     * 解析并附带「实际匹配到的中文名」用于提示（如输入「钻」且唯一命中「钻石剑」时回填）。
     */
    public static Material resolveWithHint(String input, StringBuilder hintOut) {
        if (input == null) return null;
        String raw = input.trim();
        if (raw.isEmpty()) return null;

        // 1. 英文直通（含 minecraft: 前缀与 legacy 名）
        Material m = Material.matchMaterial(raw);
        if (m != null) return m;

        Map<String, List<String>> rev = OfficialTranslations.reverseMap();
        if (rev.isEmpty()) return null; // zh_cn.json 未加载，仅支持英文

        // 2. 官方中文精确
        Material hit = byChinese(rev, raw);
        if (hit != null) return hit;

        // 3. 归一化后再查（去空格、去 minecraft: 前缀、小写）
        String norm = raw.replace(" ", "");
        if (!norm.equals(raw)) {
            m = Material.matchMaterial(norm);
            if (m != null) return m;
            hit = byChinese(rev, norm);
            if (hit != null) return hit;
        } else {
            norm = raw;
        }

        // 4. 唯一前缀匹配（全表只有一个候选以它开头才放行）
        String firstKey = null;
        int hits = 0;
        for (String zh : rev.keySet()) {
            if (zh.startsWith(norm) && !zh.equals(norm)) {
                hits++;
                if (hits == 1) firstKey = zh;
                if (hits > 1) break;
            }
        }
        if (hits == 1 && firstKey != null) {
            Material cand = byChinese(rev, firstKey);
            if (cand != null) {
                if (hintOut != null) hintOut.append(firstKey);
                return cand;
            }
        }
        return null;
    }

    /** 用中文名查反向索引并转 Material；未命中或非法返回 null。 */
    private static Material byChinese(Map<String, List<String>> rev, String zh) {
        List<String> names = rev.get(zh);
        if (names == null) return null;
        for (String en : names) {
            Material m = Material.matchMaterial(en);
            if (m != null) return m;
        }
        return null;
    }

    /**
     * 英文标准名 → 中文显示名。
     * 优先官方 zh_cn.json（OfficialTranslations），未命中回退 Title Case。
     */
    public static String toChinese(Material mat) {
        if (mat == null) return "";
        String official = OfficialTranslations.lookUp(mat.name());
        if (official != null && !official.isEmpty()) return official;
        // 回退：下划线转空格的 Title Case
        String[] parts = mat.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase())
              .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
