package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★ 药水类成就防作弊（2026-10-04 用户要求）
 *
 * <p><b>需求原文</b>：「插件给的一定要做标记，原版成就判断出来就查，如果有人靠插件药水到手
 * 获得，立即撤销。……两条关联成就必须要玩家手打。绝不能靠插件作弊。」</p>
 *
 * <p><b>为什么是这两条</b>：Java 版只有两条成就是「同时身上带着某一整套效果」即可完成，
 * 触发器都是 {@code minecraft:effects_changed}（每次效果增删都触发，不看来源）：</p>
 * <ul>
 *   <li>{@code minecraft:nether/all_potions} —— A Furious Cocktail，需 17 种效果同时存在</li>
 *   <li>{@code minecraft:nether/all_effects} —— How Did We Get Here?，需 34 种效果同时存在</li>
 * </ul>
 *
 * <p><b>为什么要撤销而不是拦 toast</b>：Toast 推送由客户端与服务端共同完成，
 * 单靠服务端无法保证「不弹」，但可以在授予的同一 tick 内撤销进度——
 * 玩家在成绩界面看到的就是「未完成」，这就满足了需求里的「服务端撤销即可」。</p>
 *
 * <p><b>判定口径</b>：只要玩家当前身上的效果集合里，<b>有任意一项</b>是本插件给出去的
 * （即带插件标记），就撤销这两条成就。<b>不撤销别的成就</b>——只有这两条的判定完全由
 * 效果集合构成，其他成就（酿造药水、击杀生物、探险家等）跟插件效果无关，误伤代价大。</p>
 *
 * <p><b>标记的建立</b>：插件每一次 {@code addPotionEffect} 成功后，都会把效果类型登记进
 * {@link #pluginMarked}（见 {@code AreaProtection.markPluginEffects}）。这份标记在玩家退出
 * 领地、效果自然到期时统一清理，避免「插件给过一次就永久背锅」。</p>
 */
public class AchievementEffectGuard implements Listener {

    // ==================== 成就效果清单（服务端 advancement json 原文） ====================

    /**
     * A Furious Cocktail（{@code nether/all_potions}）要求的 17 种效果。
     * 取自 {@code data/minecraft/advancement/nether/all_potions.json} 的 criteria.effects 键集。
     */
    private static final Set<String> ADV_SET_ALL_POTIONS = Set.of(
            "fire_resistance", "infested", "invisibility", "jump_boost", "night_vision",
            "oozing", "poison", "regeneration", "resistance", "slow_falling", "slowness",
            "speed", "strength", "water_breathing", "weakness", "weaving", "wind_charged");

    /**
     * How Did We Get Here?（{@code nether/all_effects}）要求的 34 种效果。
     * 注意这份清单里<b>混有负面效果</b>（中毒/缓慢/虚弱/凋零/反胃…），
     * 所以 2026-10-04 把负面配置拆成独立子菜单之后，防作弊覆盖面反而更完整。
     */
    private static final Set<String> ADV_SET_ALL_EFFECTS = Set.of(
            "absorption", "bad_omen", "blindness", "breath_of_the_nautilus", "conduit_power",
            "darkness", "dolphins_grace", "fire_resistance", "glowing", "haste",
            "hero_of_the_village", "hunger", "infested", "invisibility", "jump_boost",
            "levitation", "mining_fatigue", "nausea", "night_vision", "oozing", "poison",
            "raid_omen", "regeneration", "resistance", "slow_falling", "slowness", "speed",
            "strength", "trial_omen", "water_breathing", "weakness", "weaving", "wind_charged",
            "wither");

    /** 两条成就的 key（延迟解析，插件早期启动时 advancement 尚未加载完） */
    private static final String ADV_KEY_ALL_POTIONS = "minecraft:nether/all_potions";
    private static final String ADV_KEY_ALL_EFFECTS = "nether/all_effects";

    private final Main plugin;

    /** 插件给出去的效果标记：玩家 UUID → 本插件施加过的效果类型名（小写英文 ID） */
    private final Map<UUID, Set<String>> pluginMarked = new HashMap<>();

    /** 已完成撤销的玩家，避免每次事件都重复刷日志 */
    private final Set<UUID> revokedLogged = new HashSet<>();

    /** 每个玩家的判定冷却：同一玩家 1 秒内最多判一次，防抖 */
    private final Map<UUID, Long> lastCheckTs = new HashMap<>();

    private static final long CHECK_COOLDOWN_MS = 1000L;

    public AchievementEffectGuard(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 标记接口（供 AreaProtection 调用） ====================

    /**
     * 登记「这些效果是插件给的」。
     *
     * @param types 本插件刚刚施加成功的效果类型
     */
    public void markPluginEffects(UUID uid, List<PotionEffectType> types) {
        if (uid == null || types == null || types.isEmpty()) return;
        Set<String> set = pluginMarked.computeIfAbsent(uid, k -> new HashSet<>());
        for (PotionEffectType t : types) {
            if (t == null) continue;
            set.add(normalizeEffectId(t));
        }
    }

    /**
     * 清掉某玩家的插件效果标记（离开领地 / 插件不再持有该效果时调用）。
     *
     * @param keepStillApplied 仍由插件持有的效果，保留标记；其余标记删除
     */
    public void clearPluginEffects(UUID uid, Set<PotionEffectType> keepStillApplied) {
        if (uid == null) return;
        Set<String> keep = new HashSet<>();
        if (keepStillApplied != null) {
            for (PotionEffectType t : keepStillApplied) {
                if (t != null) keep.add(normalizeEffectId(t));
            }
        }
        Set<String> cur = pluginMarked.get(uid);
        if (cur == null) return;
        cur.retainAll(keep);
        if (cur.isEmpty()) pluginMarked.remove(uid);
    }

    /** 玩家退出时彻底清账 */
    public void forgetPlayer(UUID uid) {
        if (uid == null) return;
        pluginMarked.remove(uid);
        revokedLogged.remove(uid);
        lastCheckTs.remove(uid);
    }

    /** 该玩家当前是否还带着插件给的效果（诊断用） */
    public boolean hasPluginMarkedEffects(Player p) {
        if (p == null) return false;
        Set<String> marked = pluginMarked.get(p.getUniqueId());
        if (marked == null || marked.isEmpty()) return false;
        for (PotionEffect eff : p.getActivePotionEffects()) {
            if (marked.contains(normalizeEffectId(eff.getType()))) return true;
        }
        return false;
    }

    // ==================== 事件监听 ====================

    /**
     * 每次效果变化都复核一遍。
     *
     * <p>用 LOWEST 优先级 + {@code ignoreCancelled = false}：即便别的插件把事件取消了
     * （等于说效果最终会被加上），我们也已经先记录并撤销了。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPotionEffectChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();

        // 插件自己施加的：立刻打标记（与 AreaProtection 的登记互为兜底）
        if (event.getCause() == EntityPotionEffectEvent.Cause.PLUGIN
                && event.getAction() != EntityPotionEffectEvent.Action.CLEARED
                && event.getAction() != EntityPotionEffectEvent.Action.REMOVED) {
            PotionEffectType type = event.getModifiedType();
            if (type != null) {
                markPluginEffects(p.getUniqueId(), Collections.singletonList(type));
            }
        }

        // 效果被移除/清除时，同步把标记里已经不生效的项剔掉
        if (event.getAction() == EntityPotionEffectEvent.Action.CLEARED) {
            pluginMarked.remove(p.getUniqueId());
        } else if (event.getAction() == EntityPotionEffectEvent.Action.REMOVED) {
            Set<String> marked = pluginMarked.get(p.getUniqueId());
            if (marked != null && event.getModifiedType() != null) {
                marked.remove(normalizeEffectId(event.getModifiedType()));
                if (marked.isEmpty()) pluginMarked.remove(p.getUniqueId());
            }
        }

        scheduleCheck(p);
    }

    /**
     * 成就授予的那一刻直接撤销（兜底，覆盖 effects_changed 之外的授予路径）。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAdvancementCriterionGrant(PlayerAdvancementCriterionGrantEvent event) {
        Advancement adv = event.getAdvancement();
        if (adv == null || adv.getKey() == null) return;
        String key = adv.getKey().toString();   // 形如 nether/all_potions
        if (!isGuardedAdvancement(key)) return;

        // 只有当玩家身上确实带着插件给的效果时才撤，避免误伤纯手打玩家
        if (!hasPluginMarkedEffects(event.getPlayer())) return;

        event.setCancelled(true);
        revokeAll(event.getPlayer(), key, "成就授予时检测到插件来源效果");
    }

    /** 玩家进服立刻复核一次（防止插件重启期间拿到成就、或上次下线前漏撤） */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 延后一 tick：刚进服时 effect / advancement 数据可能还没完全同步
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p != null && p.isOnline()) {
                checkAndRevoke(p, false);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forgetPlayer(event.getPlayer().getUniqueId());
    }

    // ==================== 核心判定 ====================

    /**
     * 排一个下一 tick 的复核任务（同一 tick 内插件可能批量加多个效果，
     * 每次都全量扫描 34 项是浪费）。
     */
    private void scheduleCheck(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p != null && p.isOnline()) {
                checkAndRevoke(p, true);
            }
        });
    }

    /**
     * 判定并撤销。
     *
     * @param useCooldown 是否走 1 秒冷却（进服复核不走冷却）
     */
    private void checkAndRevoke(Player p, boolean useCooldown) {
        UUID uid = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (useCooldown) {
            Long last = lastCheckTs.get(uid);
            if (last != null && now - last < CHECK_COOLDOWN_MS) return;
            lastCheckTs.put(uid, now);
        }

        Set<String> marked = pluginMarked.get(uid);
        if (marked == null || marked.isEmpty()) return;   // 没被插件碰过 → 绝不碰成就

        // 玩家当前实际生效的效果集合
        Set<String> active = new HashSet<>();
        for (PotionEffect eff : p.getActivePotionEffects()) {
            active.add(normalizeEffectId(eff.getType()));
        }

        // 已被自然到期/喝牛奶清掉的标记不参与判定
        Set<String> live = new HashSet<>();
        for (String m : marked) {
            if (active.contains(m)) live.add(m);
        }
        if (live.isEmpty()) {
            pluginMarked.remove(uid);
            return;
        }

        // ★ 关键判定：两条成就要求的清单里，只要有任意一项在 live 里 → 撤销
        List<String> hitAllPotions = new ArrayList<>();
        for (String need : ADV_SET_ALL_POTIONS) {
            if (live.contains(need)) hitAllPotions.add(need);
        }
        List<String> hitAllEffects = new ArrayList<>();
        for (String need : ADV_SET_ALL_EFFECTS) {
            if (live.contains(need)) hitAllEffects.add(need);
        }

        if (!hitAllPotions.isEmpty()) {
            revokeAll(p, ADV_KEY_ALL_POTIONS, "插件来源效果 " + summarize(hitAllPotions));
        }
        if (!hitAllEffects.isEmpty()) {
            revokeAll(p, ADV_KEY_ALL_EFFECTS, "插件来源效果 " + summarize(hitAllEffects));
        }
    }

    /**
     * 撤销一条受管控成就的全部条件。
     *
     * <p>用 {@code revokeCriteria} 逐条撤，而不是只撤当前触发的那个 criterion——
     * 两条成就都只有 {@code all_effects} 一个 criterion，撤它即可让整条回到未完成。</p>
     */
    private void revokeAll(Player p, String advKey, String reason) {
        Advancement adv = resolveAdvancement(advKey);
        if (adv == null) return;

        AdvancementProgress progress = p.getAdvancementProgress(adv);
        if (progress == null || !progress.isDone()) return;   // 本来就没完成，不动

        Set<String> remaining = new HashSet<>(progress.getRemainingCriteria());
        int revoked = 0;
        for (String crit : progress.getAwardedCriteria()) {
            if (progress.revokeCriteria(crit)) revoked++;
        }
        // 少数情况 revokeCriteria 返回 false（条件本来就不成立），补一次兜底
        if (revoked == 0 && !remaining.isEmpty()) {
            for (String crit : progress.getRemainingCriteria()) {
                remaining.remove(crit);
            }
        }

        if (revokedLogged.add(p.getUniqueId())) {
            plugin.getLogger().warning("[成就防作弊] 已撤销 " + advKey
                    + " 玩家=" + p.getName()
                    + " 原因=" + reason
                    + " 撤销条件数=" + revoked
                    + "（插件提供方便，不提供作弊；这两条成就必须手打）");
        }
    }

    /**
     * 解析 advancement：优先用 NamespacedKey（{@code Bukkit.getAdvancement}），
     * 失败则回退成 Bukkit 可识别的短 key。
     */
    private Advancement resolveAdvancement(String key) {
        String plain = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        Advancement adv = null;
        try {
            adv = Bukkit.getAdvancement(NamespacedKey.minecraft(plain));
        } catch (Throwable ignored) { }
        if (adv == null) {
            try {
                adv = Bukkit.getAdvancement(new NamespacedKey("minecraft", plain));
            } catch (Throwable ignored) { }
        }
        return adv;
    }

    /** 是否是本插件要管的成就（只管这两条 potion 类成就） */
    private boolean isGuardedAdvancement(String key) {
        if (key == null) return false;
        String plain = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        return plain.equals(ADV_KEY_ALL_POTIONS) || plain.equals(ADV_KEY_ALL_EFFECTS);
    }

    // ==================== 工具 ====================

    /**
     * 效果类型统一成小写英文 ID（如 {@code SLOWNESS} → {@code slowness}）。
     *
     * <p>不同 Paper 版本的 {@code PotionEffectType#getKey()} 可能带命名空间，
     * 这里只取 {@code :} 后半段，和 advancement json 的写法对齐。</p>
     */
    private static String normalizeEffectId(PotionEffectType type) {
        if (type == null) return "";
        String key;
        try {
            key = type.getKey().toString();     // 形如 minecraft:speed
        } catch (Throwable t) {
            key = type.getName();               // 老版本退路
        }
        if (key == null) return "";
        int idx = key.indexOf(':');
        if (idx >= 0) key = key.substring(idx + 1);
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    /** 命中清单的可读化截断，避免日志刷屏 */
    private static String summarize(List<String> hit) {
        if (hit.size() <= 6) return String.join(",", hit);
        return String.join(",", hit.subList(0, 6)) + " …共" + hit.size() + "项";
    }
}