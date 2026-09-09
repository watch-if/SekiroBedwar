package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.StancePhase;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 玩家架势数值发生一次变化后广播（公共 API，只读通知）。
 *
 * <p>覆盖一切来源（命中扣减 / 弹反惩罚 / 秘传增伤 / 完美弹反成功 / 自然恢复 /
 * 完成奖励恢复等——来源不区分，外部需要归因时结合时刻相关的战斗事件自行关联）。
 * 变化幅度 {@code before - after} 即增减量。不在决斗中的玩家无架势状态，不产生本事件。</p>
 */
public class StanceChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final UUID duelId;
    private final double before;
    private final double after;
    private final double max;
    private final StancePhase phaseAfter;

    public StanceChangeEvent(UUID player, UUID duelId, double before, double after,
                             double max, StancePhase phaseAfter) {
        this.player = player;
        this.duelId = duelId;
        this.before = before;
        this.after = after;
        this.max = max;
        this.phaseAfter = phaseAfter;
    }

    public UUID player() {
        return player;
    }

    /** 所属决斗 id（广播时必在决斗中；防御性可空）。 */
    public UUID duelId() {
        return duelId;
    }

    public double before() {
        return before;
    }

    public double after() {
        return after;
    }

    public double max() {
        return max;
    }

    /** 变化后的阶段（含本次变化引发的临界 / 清零判定）。 */
    public StancePhase phaseAfter() {
        return phaseAfter;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
