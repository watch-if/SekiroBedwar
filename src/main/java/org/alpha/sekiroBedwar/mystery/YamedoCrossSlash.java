package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 秘传第二式·苇名十字斩（{@link Mystery} 实现）：空手换刀的凌厉二连。
 *
 * <p><b>起手条件</b>：玩家主手<b>空手状态须持续 ≥ {@code min-empty-hand-seconds}(0.5) 秒</b>，
 * 随后主手持上近战武器（剑 / 斧 / 矛，按材质后缀判定）→ 进入「已武装」态等待第一击。
 * 空手时长由 1 tick 巡检跟踪主手（空 → 非空）转换测得——快捷栏切换、背包换持等任何
 * 让主手变持物的路径统一覆盖；空手不足时长直接持刀（或换持非武器）不武装。</p>
 *
 * <p><b>节奏</b>：第一击（主手持武器的近战命中，含被完美弹反——仍算打出的一击）起计时，
 * 第二击与第一击的<b>命时间隔</b>须落在 {@code interval-ticks}(4) ± {@code tolerance-ticks}(0.5)
 * tick 内（毫秒 = tick×50 ± 容差×50，服务器单调时钟，与飞渡浮舟同一口径）。
 * 空手挥拳不消耗武装态。</p>
 *
 * <p><b>第二段为有效攻击（未被完美弹反）时</b>：击退受击方（{@code knockback-level}(2) 级
 * 击退效果：水平速度 + 轻微上抬）+ 受击方架势 −{@code victim-stance-penalty}(7) +
 * 自身架势 +{@code self-stance-recovery}(3) + 铁砧落地音效（与飞渡浮舟完成音同款，无文字）。</p>
 *
 * <p>第二击脱拍或被弹反 → 连段终结，须重新满足「空手 ≥0.5s 再换刀」才能再起
 * （第一击的空手硬约束不因脱拍继承）。死亡 / 退出 / 离局 / 决斗结束清状态
 * （由 {@link MysteryManager} 统一驱动）。</p>
 */
public final class YamedoCrossSlash implements Mystery {

    private final MysteryConfig config;
    private final StanceManager stanceManager;
    private final BukkitTask scanTask;

    /** 连段进度（武装 / 第一击后待第二击）。 */
    private final Map<UUID, CrossProgress> progress = new HashMap<>();
    /** 玩家 → 上一轮主手是否空手（null = 首轮观测，仅记基线）。 */
    private final Map<UUID, Boolean> lastEmpty = new HashMap<>();
    /** 玩家 → 本轮空手起始时刻（单调毫秒）。 */
    private final Map<UUID, Long> emptySince = new HashMap<>();

    public YamedoCrossSlash(SekiroBedwar plugin, MysteryConfig config, StanceManager stanceManager) {
        this.config = config;
        this.stanceManager = stanceManager;
        this.scanTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::scanMainHand, 1L, 1L);
    }

    @Override
    public String id() {
        return "yamedo-cross-slash";
    }

    /**
     * 1 tick 巡检：跟踪主手空 / 持物转换。
     * 非空 → 空：记空手起始；空 → 近战武器且空手时长 ≥ min-empty-hand-ms：武装。
     */
    private void scanMainHand() {
        long now = System.nanoTime() / 1_000_000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            ItemStack main = player.getInventory().getItemInMainHand();
            boolean empty = main == null || main.getType().isAir();
            Boolean wasEmpty = lastEmpty.get(uuid);
            if (empty) {
                if (wasEmpty == null || !wasEmpty) {
                    emptySince.put(uuid, now); // 进入空手：起表
                }
                lastEmpty.put(uuid, Boolean.TRUE);
                continue;
            }
            if (Boolean.TRUE.equals(wasEmpty)) {
                Long since = emptySince.remove(uuid);
                long heldEmptyMs = since == null ? 0L : now - since;
                if (heldEmptyMs >= config.yameMinEmptyMs() && isMeleeWeapon(main)) {
                    progress.put(uuid, CrossProgress.armed()); // 空手达标后换刀：武装
                }
            } else {
                emptySince.remove(uuid);
            }
            lastEmpty.put(uuid, Boolean.FALSE);
        }
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        CrossProgress p = progress.get(attacker.getUniqueId());
        if (p == null) {
            return;
        }
        long now = System.nanoTime() / 1_000_000L;
        if (p.armed) {
            // 第一击：必须持近战武器打出（空手挥拳不消耗武装态）
            if (!isMeleeWeapon(attacker.getInventory().getItemInMainHand())) {
                return;
            }
            p.armed = false;
            p.firstHitMs = now;
            return;
        }
        // 第二击：命中间隔判定（4t ± 0.5t 毫秒口径）
        long expected = Math.round(config.yameIntervalTicks() * 50.0);
        long window = Math.round(config.yameToleranceTicks() * 50.0);
        long delta = now - p.firstHitMs;
        progress.remove(attacker.getUniqueId()); // 无论成败，二连到此终结
        if (delta < expected - window || delta > expected + window) {
            return; // 脱拍：须重新空手 0.5s + 换刀
        }
        if (parried) {
            return; // 第二击被完美弹反：不触发效果
        }
        execute(attacker, victim);
    }

    /** 效果：击退 II 级 + 对方 −7 架势 + 自身 +3 架势 + 铁砧落地音效。 */
    private void execute(Player attacker, Player victim) {
        double level = config.yameKnockbackLevel();
        Vector dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        dir.setY(0.0);
        if (dir.lengthSquared() < 1.0e-4) {
            dir = attacker.getLocation().getDirection().setY(0.0); // 同坐标兜底：沿攻击方面朝
        }
        dir.normalize().multiply(level * 0.45); // 近似原版附魔级别：水平速度 0.45/级
        dir.setY(level * 0.1);                  // 轻微上抬（原版 KB 垂直分量近似）
        victim.setVelocity(dir);
        stanceManager.reduceStance(victim.getUniqueId(), config.yameVictimStancePenalty());
        stanceManager.addStance(attacker.getUniqueId(), config.yameSelfStanceRecovery());
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
    }

    /** 近战武器：剑 / 斧 / 矛（1.21.11 材质后缀口径）。 */
    private static boolean isMeleeWeapon(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_SPEAR");
    }

    @Override
    public void clear(UUID player) {
        progress.remove(player);
        lastEmpty.remove(player);
        emptySince.remove(player);
    }

    @Override
    public void clearAll() {
        progress.clear();
        lastEmpty.clear();
        emptySince.clear();
    }

    @Override
    public void shutdown() {
        scanTask.cancel();
    }

    /** 连段进度：armed = 已武装待第一击；否则 firstHitMs = 第一击时刻。 */
    private static final class CrossProgress {
        boolean armed;
        long firstHitMs;

        static CrossProgress armed() {
            CrossProgress p = new CrossProgress();
            p.armed = true;
            return p;
        }
    }
}
