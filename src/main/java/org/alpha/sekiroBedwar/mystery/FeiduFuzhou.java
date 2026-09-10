package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

/**
 * 第一秘传·飞渡浮舟（{@link Mystery} 实现）：特定节奏的七连击。
 *
 * <p><b>识别</b>：连续 <b>近战命中</b>（含被完美弹反的命中——仍算打出的一击，只是第 6 击
 * 被弹反则不吃加成）依次满足相邻间隔序列（默认 7 / 10 / 6 / 5 / 6 / 16 拍，
 * 共 6 段、第 1→7 击）。<b>判定按服务器 tick 数拍距</b>：
 * {@code |Δtick − 目标拍| ≤ ceil(容差)}——容差向上取整为拍：0 = 必须踩准，
 * 0.2~0.5 = 允许 ±1 拍。段成功 / 脱拍音遵循「领先者发声」：同一玩家并行多式时，
 * 只有进度领先的式出声。间隔窗口中的【长空隙】（如 2→3 与 6→7 之间）允许玩家
 * 右键格挡 / 投掷投掷物——这些动作不产生近战命中、不进序列也不打断。</p>
 *
 * <p><b>第 6 击加成</b>：走到第 6 击且该击有效命中（未被完美弹反）→ 受击方架势在普通
 * 换算之外<b>额外 −{@code sixth-bonus-stance}(10)</b>。</p>
 *
 * <p><b>完成奖励</b>：七击全部满足间隔 → 攻击方得 {@code reward-paper-dolls}(2) 纸人
 * （可超持有上限，同漂流纸人发放口径）+ 架势 +{@code reward-stance}(5) +
 * 生命 +{@code reward-health}(1)（不超过上限；死亡中不发放）+ 完成音效（无文字，
 * 沉浸口径）。任何一击间隔超出容差 → 该击成为新的一式（从头重连）。</p>
 *
 * <p>钩子由 {@link MysteryManager} 统一转发（仅 ACTIVE 决斗内近战，与巴之雷 / 属性两系
 * 同一注入点）；死亡 / 退出 / 离局 / 决斗结束清连击状态。</p>
 */
public final class FeiduFuzhou implements Mystery {

    private final MysteryConfig config;
    private final StanceManager stanceManager;
    private final PaperDollManager paperDollManager;
    private final KnockbackGuard knockbackGuard;
    private final IntSupplier tick;
    private final BiFunction<Mystery, UUID, Integer> rivalTop;

    /** 玩家 → 连击进度。 */
    private final Map<UUID, ComboProgress> progress = new HashMap<>();

    public FeiduFuzhou(MysteryConfig config, StanceManager stanceManager,
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
        return "fei-du-fu-zhou";
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        double[] intervals = config.fdfzIntervals();
        int totalHits = intervals.length + 1;   // 6 段间隔 → 7 击
        int tolBeats = (int) Math.ceil(config.fdfzToleranceTicks()); // 容差按拍（ceil：非零容差至少 ±1 拍）
        int nowTick = tick.getAsInt();  // 按拍判定的 tick 时基
        UUID uuid = attacker.getUniqueId();
        ComboProgress p = progress.get(uuid);

        if (p == null) {
            progress.put(uuid, new ComboProgress(nowTick));
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techStart(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.FEIDU_FUZU); // 公共 API：第一击 = 连段启动
            return;
        }
        if (p.hits >= totalHits) {
            p.restart(nowTick); // 理论不可达（完成即移除），防御性重开
            return;
        }
        int target = (int) Math.round(intervals[p.hits - 1]); // 已完成 p.hits 击 → 第 p.hits-1 号间隔（0 基）
        int delta = nowTick - p.lastHitTick;
        if (Math.abs(delta - target) > tolBeats) {
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.FEIDU_FUZU,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.OUT_OF_RHYTHM, p.hits);
            // 脱拍音：连续脱拍链只播一次 + 领先者才播（并行别式在推进时本式落后 = 静默重开）
            if (!p.breakNotified && rivalTop.apply(this, uuid) <= p.hits) {
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            }
            p.breakNotified = true;
            p.restart(nowTick); // 本击脱拍：作为新的一式
            return;
        }
        p.hits++;
        p.lastHitTick = nowTick;
        p.breakNotified = false; // 成功接段：脱拍提示复位
        if (!parried) {
            p.validHits++; // 公共完成事件的有效击统计
        }
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(uuid,
                org.alpha.sekiroBedwar.api.TechniqueId.FEIDU_FUZU, p.hits, parried, victim.getUniqueId());
        // 第 3 击起每段成功 = 铁砧落地音（领先者才播）+ 刷新 1s 防击退；第 7 击由完成音统一播
        if (p.hits >= 3 && p.hits < totalHits) {
            if (rivalTop.apply(this, uuid) <= p.hits) {
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
            }
            knockbackGuard.refresh(uuid);
        }

        if (p.hits == totalHits - 1 && !parried) {
            // 第 6 击：有效命中（未被完美弹反）额外追加架势伤害
            stanceManager.reduceStance(victim.getUniqueId(), config.fdfzSixthBonusStance());
        }
        if (p.hits == totalHits) {
            progress.remove(uuid);
            complete(attacker);
            knockbackGuard.refresh(uuid); // 末段成功同样刷新防击退（完成音已在 complete 内播）
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techComplete(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.FEIDU_FUZU, totalHits, p.validHits);
        }
    }

    /** 完成：纸人 + 架势 + 生命 + 音效（无文字）。 */
    private void complete(Player attacker) {
        if (attacker == null || !attacker.isOnline() || attacker.isDead()) {
            return;
        }
        if (config.fdfzRewardPaperDolls() > 0) {
            paperDollManager.givePaperDolls(attacker, config.fdfzRewardPaperDolls());
        }
        if (config.fdfzRewardStance() > 0) {
            stanceManager.addStance(attacker.getUniqueId(), config.fdfzRewardStance());
        }
        if (config.fdfzRewardHealth() > 0 && attacker.getHealth() > 0) {
            double healed = Math.min(attacker.getMaxHealth(), attacker.getHealth() + config.fdfzRewardHealth());
            if (healed > attacker.getHealth()) {
                attacker.setHealth(healed);
            }
        }
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
    }

    @Override
    public void clear(UUID player, org.alpha.sekiroBedwar.api.TechniqueCancelReason reason) {
        ComboProgress p = progress.remove(player);
        if (p != null) { // 进行中的连段被生命周期中止：公共 API Cancel（Fail 之外的另一种终结）
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techCancel(player,
                    org.alpha.sekiroBedwar.api.TechniqueId.FEIDU_FUZU, reason, p.hits);
        }
    }

    @Override
    public void clearAll() {
        progress.clear();
    }

    @Override
    public int comboProgress(UUID player) {
        ComboProgress p = progress.get(player);
        return p == null ? 0 : p.hits;
    }

    /** 连击进度：已完成击数 + 上一击 tick 序号 + 本轮脱拍是否已提示 + 有效击计数。 */
    private static final class ComboProgress {
        int hits;
        int lastHitTick;
        boolean breakNotified;
        int validHits;

        ComboProgress(int firstHitTick) {
            this.hits = 1;
            this.lastHitTick = firstHitTick;
        }

        void restart(int nowTick) {
            this.hits = 1;
            this.lastHitTick = nowTick;
            this.validHits = 0; // 新一式重新计数
        }
    }
}
