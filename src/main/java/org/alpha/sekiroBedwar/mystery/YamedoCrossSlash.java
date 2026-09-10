package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

/**
 * 秘传第二式·苇名十字斩（{@link Mystery} 实现）：空手换刀的凌厉二连。
 *
 * <p><b>起手条件</b>：主手空手持续在 [{@code min-empty-hand-seconds}(0.5),
 * {@code max-empty-hand-seconds}(1.0)) 秒窗口内（≥上限属龙闪窗口，两式互斥），
 * 快捷栏切换到近战武器（剑 / 斧 / 矛）→ <b>即时武装</b>（{@code PlayerItemHeldEvent}
 * 驱动记武装时刻；空手起始由「持物→空」切换簿记，从开局即空手视为满足时长）。</p>
 *
 * <p><b>衔接窗口</b>：武装后第一击必须在换刀后的衔接窗（{@code mystery.arm-connect-ticks}，
 * 默认 4 拍）内打出——超时按<b>脱拍</b>处理（武装作废 + 打磨音，须重新空手换刀）；
 * 段成功 / 脱拍音遵循「领先者发声」（并行多式时只有领先者出声）。</p>
 *
 * <p><b>节奏</b>：第一击（主手持武器的近战命中，含被完美弹反——仍算打出的一击）起计时，
 * 第二击与第一击的<b>命中拍距（服务器 tick 计数差）</b>须在 {@code interval-ticks}(4)
 * ± {@code tolerance-ticks}（ceil 取拍）内，与飞渡浮舟同口径。
 * 空手挥拳不消耗武装态（衔接窗内）。</p>
 *
 * <p><b>第二段为有效攻击（未被完美弹反）时</b>：击退受击方（{@code knockback-level}(2) 级
 * 击退效果）+ 受击方架势 −{@code victim-stance-penalty}(7) + 自身架势
 * +{@code self-stance-recovery}(3)。音效语言：接上段 = 铁砧落地、脱拍 = 铁砧打磨（无文字）。</p>
 *
 * <p>第二击脱拍或被弹反 → 连段终结，须重新「空手窗口 + 换刀 + 衔接窗内起手」才能再起。
 * 死亡 / 退出 / 离局 / 决斗结束清状态（{@link MysteryManager} 统一驱动）。</p>
 */
public final class YamedoCrossSlash implements Mystery {

    private final MysteryConfig config;
    private final StanceManager stanceManager;
    private final IntSupplier tick;
    private final BiFunction<Mystery, UUID, Integer> rivalTop;

    /** 连段进度（武装 / 第一击后待第二击）。 */
    private final Map<UUID, CrossProgress> progress = new HashMap<>();
    /** 玩家 → 本轮空手起始时刻（「持物→空」切换时写入；无记录 = 开局即空手）。 */
    private final Map<UUID, Long> emptySince = new HashMap<>();

    public YamedoCrossSlash(MysteryConfig config, StanceManager stanceManager, IntSupplier tick,
                            BiFunction<Mystery, UUID, Integer> rivalTop) {
        this.config = config;
        this.stanceManager = stanceManager;
        this.tick = tick;
        this.rivalTop = rivalTop;
    }

    @Override
    public String id() {
        return "yamedo-cross-slash";
    }

    /**
     * 快捷栏切换（宿主即时转发）：持物→空 = 空手起表；空→近战武器且空手时长在
     * [min, max) 窗口 = 即时武装（记 tick，第一击须在衔接窗内）。
     */
    @Override
    public void onSlotSwitch(Player player, ItemStack previous, ItemStack current) {
        boolean prevEmpty = previous == null || previous.getType().isAir();
        boolean curEmpty = current == null || current.getType().isAir();
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime() / 1_000_000L; // 空手时长是"人类按住时间"，保留墙钟毫秒
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
            progress.put(uuid, CrossProgress.armed(tick.getAsInt())); // 空手窗口达标：武装（衔接判定按 tick）
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
        UUID uuid = attacker.getUniqueId();
        int nowTick = tick.getAsInt(); // 按拍判定的 tick 时基
        if (p.armed) {
            if (nowTick - p.armedTick > (int) Math.ceil(config.armConnectTicks())) {
                // 换刀后未在衔接窗内打出第一击：算脱拍，武装作废（领先者才播提示音）
                boolean leader = rivalTop.apply(this, uuid) <= 1;
                progress.remove(uuid);
                if (leader) {
                    attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
                }
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
            p.firstHitTick = nowTick;
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.YAMEDO_CROSS_SLASH, 1, parried, victim.getUniqueId());
            return;
        }
        // 第二击：命中拍距判定（round(4t) ± ceil(0.5t)）——成功段=铁砧落地、脱拍=铁砧打磨
        int target = (int) Math.round(config.yameIntervalTicks());
        int tolBeats = (int) Math.ceil(config.yameToleranceTicks());
        int delta = nowTick - p.firstHitTick;
        boolean leader = rivalTop.apply(this, uuid) <= 2;
        progress.remove(uuid); // 无论成败，二连到此终结
        if (Math.abs(delta - target) > tolBeats) {
            if (leader) {
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f); // 脱拍
            }
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

    @Override
    public int comboProgress(UUID player) {
        CrossProgress p = progress.get(player);
        return p == null ? 0 : (p.armed ? 1 : 2);
    }

    /** 连段进度：armed = 已武装（记 tick）；否则 firstHitTick = 第一击 tick 序号。 */
    private static final class CrossProgress {
        boolean armed;
        int armedTick;
        int firstHitTick;

        static CrossProgress armed(int atTick) {
            CrossProgress p = new CrossProgress();
            p.armed = true;
            p.armedTick = atTick;
            return p;
        }
    }
}
