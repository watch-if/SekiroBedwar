package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

/**
 * 秘传第四式·一心七连（{@link Mystery} 实现）：伊势路的七段流刀连。
 *
 * <p><b>识别</b>（与飞渡浮舟同口径：近战命中钩子、<b>服务器 tick 计数差判定</b>
 * {@code |Δtick − 目标拍| ≤ ceil(容差)}、被完美弹反仍算打出的一击）：七击六间隔默认
 * <b>7 / 5 / 5 / 6 / 8 / 10 tick</b> 各 ±{@code tolerance-ticks}；任何一击脱拍 →
 * 打磨音（一次连续脱拍链只播一次）+ 本击作为新的一式重连。第 6→7 段（10 tick）等长窗口
 * 允许右键格挡 / 投掷投掷物——这些动作不产生近战命中，不进序列也不打断。</p>
 *
 * <p><b>音效</b>：自第 3 击起每段成功播铁砧落地音（领先者才播）；第 7 击完成音同为落地。
 * 段成功 / 脱拍音遵循「领先者发声」：与飞渡浮舟并行推进时，只有进度领先的式出声。</p>
 *
 * <p><b>逐段架势增伤（叠加）</b>：第 3 段起，每段成功<b>且该击为有效攻击（未被完美弹反）</b>
 * → 受击方在普通换算外额外扣 {@code bonus-per-stage-stance}(3) × <b>已叠有效段数</b>：
 * 第 3 段有效 −3、第 4 段也有效 −6、第 5 段 −9、第 6 段 −12（该段被弹反则不计入叠加数、
 * 无增伤但连段继续）。</p>
 *
 * <p><b>终结技约束</b>：第 7 击必须是<b>危攻击</b>（主手矛 + LUNGE 突进附魔 + 疾跑，
 * 与 {@code danger} 模块同判定口径；危本身不可被完美弹反，故必为有效击）——
 * 非危的第七击<b>不算完成全段</b>：无奖励、无增伤，打磨音提示后本击作为新一式重连。</p>
 *
 * <p><b>完成奖励</b>：第 3 段起（第 4 击）至第 7 击共 4 段，第 7 击有效 → 额外 −12；
 * 奖励 {@code reward-paper-dolls}(2) 纸人（可超上限）+ 自身架势
 * +{@code reward-stance}(4) + 铁砧落地音（无文字）。</p>
 *
 * <p>死亡 / 退出 / 离局 / 决斗结束清连段（{@link MysteryManager} 统一驱动）。
 * 与飞渡浮舟共享命中钩子：同一次挥砍可同时推进两条节奏判定，互不隔离（各持独立状态）。</p>
 */
public final class IsshinSevenStrike implements Mystery {

    private final MysteryConfig config;
    private final StanceManager stanceManager;
    private final PaperDollManager paperDollManager;
    private final KnockbackGuard knockbackGuard;
    private final IntSupplier tick;
    private final BiFunction<Mystery, UUID, Integer> rivalTop;

    /** 玩家 → 连段进度。 */
    private final Map<UUID, Combo> combo = new HashMap<>();

    public IsshinSevenStrike(MysteryConfig config, StanceManager stanceManager,
                             PaperDollManager paperDollManager, KnockbackGuard knockbackGuard,
                             IntSupplier tick, BiFunction<Mystery, UUID, Integer> rivalTop) {
        this.config = config;
        this.stanceManager = stanceManager;
        this.paperDollManager = paperDollManager;
        this.knockbackGuard = knockbackGuard;
        this.tick = tick;
        this.rivalTop = rivalTop;
    }

    @Override
    public String id() {
        return "isshin-seven-strike";
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        double[] intervals = config.isshinIntervals();
        int totalHits = intervals.length + 1; // 6 段间隔 → 7 击
        int tolBeats = (int) Math.ceil(config.isshinToleranceTicks()); // 容差按拍（ceil）
        int nowTick = tick.getAsInt();  // 按拍判定的 tick 时基
        UUID uuid = attacker.getUniqueId();
        Combo p = combo.get(uuid);
        if (p == null) {
            combo.put(uuid, new Combo(nowTick)); // 第一击：无音、无增伤
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techStart(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE);
            return;
        }
        if (p.hits >= totalHits) {
            p.restart(nowTick); // 防御性（完成即移除，理论不可达）
            return;
        }
        int target = (int) Math.round(intervals[p.hits - 1]); // 已完成 p.hits 击 → 第 p.hits-1 号间隔（0 基）
        int delta = nowTick - p.lastHitTick;
        if (Math.abs(delta - target) > tolBeats) {
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.OUT_OF_RHYTHM, p.hits);
            if (!p.breakNotified && rivalTop.apply(this, uuid) <= p.hits) { // 脱拍音：链内一次 + 领先者才播
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            }
            p.breakNotified = true;
            p.restart(nowTick); // 本击作为新的一式
            return;
        }
        p.hits++;
        p.lastHitTick = nowTick;
        p.breakNotified = false; // 成功接段：脱拍提示复位
        int hits = p.hits;
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(uuid,
                org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE, hits, parried, victim.getUniqueId());

        if (hits == totalHits) {
            knockbackGuard.refresh(uuid); // 末段接上即刷新防击退（非危终结也保留此前段收益，仅不算完成）
            // 第 7 击：必须是危攻击（矛+LUNGE+疾跑），否则不算完成全段
            if (!isDangerStrike(attacker)) {
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                        org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE,
                        org.alpha.sekiroBedwar.api.TechniqueFailReason.FINISHER_NOT_MET, hits - 1);
                if (!p.breakNotified && rivalTop.apply(this, uuid) <= hits) {
                    attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
                }
                p.breakNotified = true;
                p.restart(nowTick); // 本击作为新一式重连
                return;
            }
            combo.remove(uuid);
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f); // 完成音
            int valid = p.validStages;
            if (!parried) { // 第 6 段增伤（危击必为有效击；parried 仅为防御性分支）
                valid++;
                double bonus = config.isshinBonusPerStageStance() * valid;
                if (bonus > 0) {
                    stanceManager.reduceStance(victim.getUniqueId(), bonus);
                }
            }
            complete(attacker);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techComplete(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE, totalHits, valid);
            return;
        }
        if (hits >= 3) { // 第 3 击起每段成功：铁砧落地音（领先者才播）+ 刷新 1s 防击退（不叠加）
            if (rivalTop.apply(this, uuid) <= hits) {
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
            }
            knockbackGuard.refresh(uuid);
        }
        if (hits >= 4) { // 第 3~5 段（第 4/5/6 击）：有效击逐段叠加架势增伤
            applyStageBonus(attacker, victim, parried);
        }
        // 第 2 击接上：静默、无增伤
    }

    /** 段成功且该击有效（未被完美弹反）→ 有效段数 +1，受击方额外扣 3×段数 架势（叠加）。 */
    private void applyStageBonus(Player attacker, Player victim, boolean parried) {
        if (parried) {
            return;
        }
        Combo p = combo.get(attacker.getUniqueId());
        if (p == null) {
            return;
        }
        p.validStages++;
        double bonus = config.isshinBonusPerStageStance() * p.validStages;
        if (bonus > 0) {
            stanceManager.reduceStance(victim.getUniqueId(), bonus);
        }
    }

    /** 完成奖励：纸人（可超上限）+ 自身架势恢复。 */
    private void complete(Player attacker) {
        if (attacker == null || !attacker.isOnline() || attacker.isDead()) {
            return;
        }
        if (config.isshinRewardPaperDolls() > 0) {
            paperDollManager.givePaperDolls(attacker, config.isshinRewardPaperDolls());
        }
        if (config.isshinRewardStance() > 0) {
            stanceManager.addStance(attacker.getUniqueId(), config.isshinRewardStance());
        }
    }

    /** 危攻击判定（与 DangerManager.isDangerAttack 同口径，命中时刻的攻击者状态）。 */
    private static boolean isDangerStrike(Player attacker) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Material type = weapon == null ? null : weapon.getType();
        if (type == null || !type.name().endsWith("_SPEAR")) {
            return false;
        }
        if (weapon.getEnchantmentLevel(Enchantment.LUNGE) <= 0) {
            return false;
        }
        return attacker.isSprinting();
    }

    @Override
    public void clear(UUID player, org.alpha.sekiroBedwar.api.TechniqueCancelReason reason) {
        Combo p = combo.remove(player);
        if (p != null) {
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techCancel(player,
                    org.alpha.sekiroBedwar.api.TechniqueId.ISSHIN_SEVEN_STRIKE, reason, p.hits);
        }
    }

    @Override
    public void clearAll() {
        combo.clear();
    }

    @Override
    public int comboProgress(UUID player) {
        Combo p = combo.get(player);
        return p == null ? 0 : p.hits;
    }

    /** 连段进度：已完成击数 / 上一击 tick 序号 / 脱拍链是否已提示 / 已叠有效段数。 */
    private static final class Combo {
        int hits;
        int lastHitTick;
        boolean breakNotified;
        int validStages;

        Combo(int firstHitTick) {
            this.hits = 1;
            this.lastHitTick = firstHitTick;
        }

        void restart(int nowTick) {
            this.hits = 1;
            this.lastHitTick = nowTick;
            this.validStages = 0;
        }
    }
}
