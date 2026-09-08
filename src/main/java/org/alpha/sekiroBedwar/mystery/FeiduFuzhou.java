package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 第一秘传·飞渡浮舟（{@link Mystery} 实现）：特定节奏的七连击。
 *
 * <p><b>识别</b>：连续 <b>近战命中</b>（含被完美弹反的命中——仍算打出的一击，只是第 6 击
 * 被弹反则不吃加成）依次满足相邻间隔序列（默认 7.3 / 10 / 5.7 / 5.3 / 5.7 / 16 tick，
 * 共 6 段、第 1→7 击；毫秒口径 = tick×50 ± 容差 0.2tick×50），以服务器单调时钟计时。
 * 间隔窗口中的【长空隙】（如 2→3 与 6→7 之间）允许玩家右键格挡 / 投掷投掷物——这些动作
 * 不产生近战命中、不进序列也不打断序列，纯按命中间隔判定。</p>
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

    /** 玩家 → 连击进度。 */
    private final Map<UUID, ComboProgress> progress = new HashMap<>();

    public FeiduFuzhou(MysteryConfig config, StanceManager stanceManager,
                       PaperDollManager paperDollManager) {
        this.config = config;
        this.stanceManager = stanceManager;
        this.paperDollManager = paperDollManager;
    }

    @Override
    public String id() {
        return "fei-du-fu-zhou";
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        double[] intervals = config.fdfzIntervals();
        int totalHits = intervals.length + 1;   // 6 段间隔 → 7 击
        long targetWindow = Math.round(config.fdfzToleranceTicks() * 50.0);
        long now = System.nanoTime() / 1_000_000L;
        UUID uuid = attacker.getUniqueId();
        ComboProgress p = progress.get(uuid);

        if (p == null) {
            progress.put(uuid, new ComboProgress(now));
            return;
        }
        if (p.hits >= totalHits) {
            p.restart(now); // 理论不可达（完成即移除），防御性重开
            return;
        }
        long expected = Math.round(intervals[p.hits] * 50.0); // p.hits = 已完成击数 = 间隔下标
        long delta = now - p.lastHitMs;
        if (delta < expected - targetWindow || delta > expected + targetWindow) {
            p.restart(now); // 本击脱拍：作为新的一式
            return;
        }
        p.hits++;
        p.lastHitMs = now;

        if (p.hits == totalHits - 1 && !parried) {
            // 第 6 击：有效命中（未被完美弹反）额外追加架势伤害
            stanceManager.reduceStance(victim.getUniqueId(), config.fdfzSixthBonusStance());
        }
        if (p.hits == totalHits) {
            progress.remove(uuid);
            complete(attacker);
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
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.6f);
    }

    @Override
    public void clear(UUID player) {
        progress.remove(player);
    }

    @Override
    public void clearAll() {
        progress.clear();
    }

    /** 连击进度：已完成击数 + 上一击时刻（单调毫秒）。 */
    private static final class ComboProgress {
        int hits;
        long lastHitMs;

        ComboProgress(long firstHitMs) {
            this.hits = 1;
            this.lastHitMs = firstHitMs;
        }

        void restart(long now) {
            this.hits = 1;
            this.lastHitMs = now;
        }
    }
}
