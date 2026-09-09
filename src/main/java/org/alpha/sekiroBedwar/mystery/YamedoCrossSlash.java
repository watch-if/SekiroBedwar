package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 秘传第二式·苇名十字斩（{@link Mystery} 实现）：空手换刀的凌厉二连。
 *
 * <p><b>起手条件</b>：主手空手持续在 [{@code min-empty-hand-seconds}(0.5),
 * {@code max-empty-hand-seconds}(1.0)) 秒窗口内（≥上限属龙闪窗口，两式互斥），
 * 快捷栏切换到近战武器（剑 / 斧 / 矛）→ <b>即时武装</b>（{@code PlayerItemHeldEvent}
 * 驱动记武装时刻；空手起始由「持物→空」切换簿记，从开局即空手视为满足时长）。</p>
 *
 * <p><b>衔接窗口</b>：武装后第一击必须在 <b>1 tick</b> 内打出（换刀即拔刀斩）——
 * 超时打出的第一击按<b>脱拍</b>处理（武装作废 + 铁砧打磨音，须重新空手换刀）。</p>
 *
 * <p><b>节奏</b>：第一击（主手持武器的近战命中，含被完美弹反——仍算打出的一击）起计时，
 * 第二击与第一击的<b>命时间隔</b>须落在 {@code interval-ticks}(4) ± {@code tolerance-ticks}(0.5)
 * tick 内（毫秒 = tick×50 ± 容差×50，服务器单调时钟，与飞渡浮舟同一口径）。
 * 空手挥拳不消耗武装态（衔接窗内）。</p>
 *
 * <p><b>第二段为有效攻击（未被完美弹反）时</b>：击退受击方（{@code knockback-level}(2) 级
 * 击退效果）+ 受击方架势 −{@code victim-stance-penalty}(7) + 自身架势
 * +{@code self-stance-recovery}(3)。音效语言：接上段 = 铁砧落地、脱拍 = 铁砧打磨（无文字）。</p>
 *
 * <p>第二击脱拍或被弹反 → 连段终结，须重新「空手窗口 + 换刀 + 1t 衔接」才能再起。
 * 死亡 / 退出 / 离局 / 决斗结束清状态（{@link MysteryManager} 统一驱动）。</p>
 */
public final class YamedoCrossSlash implements Mystery {

    private final MysteryConfig config;
    private final StanceManager stanceManager;

    /** 连段进度（武装 / 第一击后待第二击）。 */
    private final Map<UUID, CrossProgress> progress = new HashMap<>();
    /** 玩家 → 本轮空手起始时刻（「持物→空」切换时写入；无记录 = 开局即空手）。 */
    private final Map<UUID, Long> emptySince = new HashMap<>();

    public YamedoCrossSlash(MysteryConfig config, StanceManager stanceManager) {
        this.config = config;
        this.stanceManager = stanceManager;
    }

    @Override
    public String id() {
        return "yamedo-cross-slash";
    }

    /**
     * 快捷栏切换（宿主即时转发）：持物→空 = 空手起表；空→近战武器且空手时长在
     * [min, max) 窗口 = 即时武装（记时刻，第一击须 1 tick 内衔接）。
     */
    @Override
    public void onSlotSwitch(Player player, ItemStack previous, ItemStack current) {
        boolean prevEmpty = previous == null || previous.getType().isAir();
        boolean curEmpty = current == null || current.getType().isAir();
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime() / 1_000_000L;
        if (!prevEmpty && curEmpty) {
            emptySince.put(uuid, now); // 进入空手：起表
            return;
        }
        if (!prevEmpty || curEmpty || !isMeleeWeapon(current)) {
            return; // 武器→武器等与起手无关的切换：不动武装 / 簿记
        }
        Long since = emptySince.remove(uuid);
        long heldEmptyMs = since == null ? Long.MAX_VALUE : now - since;
        if (heldEmptyMs >= config.yameMinEmptyMs() && heldEmptyMs < config.yameMaxEmptyMs()) {
            progress.put(uuid, CrossProgress.armed(now)); // 空手窗口达标：武装
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techStart(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH); // 公共 API：武装即启动
        }
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        CrossProgress p = progress.get(attacker.getUniqueId());
        if (p == null) {
            return;
        }
        long now = System.nanoTime() / 1_000_000L;
        UUID uuid = attacker.getUniqueId();
        if (p.armed) {
            if (now - p.armedAtMs > config.armConnectMs()) {
                // 换刀后未在 1 tick 内衔接第一击：算脱拍，武装作废
                progress.remove(uuid);
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                        org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH,
                        org.alpha.sekiroBedwar.api.TechniqueFailReason.CONNECT_TIMEOUT, 0);
                return;
            }
            // 第一击：必须持近战武器打出（空手挥拳在衔接窗内不消耗武装态）
            if (!isMeleeWeapon(attacker.getInventory().getItemInMainHand())) {
                return;
            }
            p.armed = false;
            p.firstHitMs = now;
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH, 1, parried, victim.getUniqueId());
            return;
        }
        // 第二击：命中间隔判定（4t ± 0.5t 毫秒口径）——成功段=铁砧落地、脱拍=铁砧打磨
        long expected = Math.round(config.yameIntervalTicks() * 50.0);
        long window = Math.round(config.yameToleranceTicks() * 50.0);
        long delta = now - p.firstHitMs;
        progress.remove(uuid); // 无论成败，二连到此终结
        if (delta < expected - window || delta > expected + window) {
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f); // 脱拍
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.OUT_OF_RHYTHM, 1);
            return; // 须重新空手窗口 + 换刀
        }
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f); // 段成功
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(uuid,
                org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH, 2, parried, victim.getUniqueId());
        if (parried) {
            // 第二击被完美弹反：接上但效果不触发——本式以「有效终结」为完成，记 REJECTED 失败
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.REJECTED, 2);
            return;
        }
        execute(attacker, victim);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techComplete(uuid,
                org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH, 2, 2);
    }

    /** 效果：击退 II 级 + 对方 −7 架势 + 自身 +3 架势（音效已在段成功处统一播放）。 */
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
    public void clear(UUID player, org.alpha.sekiroBedwar.api.TechniqueCancelReason reason) {
        CrossProgress p = progress.remove(player);
        if (p != null) {
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techCancel(player,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH, reason, p.armed ? 0 : 1);
        }
        emptySince.remove(player);
    }

    @Override
    public void clearAll() {
        progress.clear();
        emptySince.clear();
    }

    /** 连段进度：armed = 已武装（记时刻）；否则 firstHitMs = 第一击时刻。 */
    private static final class CrossProgress {
        boolean armed;
        long armedAtMs;
        long firstHitMs;

        static CrossProgress armed(long atMs) {
            CrossProgress p = new CrossProgress();
            p.armed = true;
            p.armedAtMs = atMs;
            return p;
        }
    }
}
