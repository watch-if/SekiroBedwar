package org.alpha.sekiroBedwar.api;

import java.util.UUID;

/**
 * 玩家架势的只读快照（公共 API；不可变数据类）。
 *
 * <p>不在决斗中的玩家：{@link #phase()} 为 {@link StancePhase#NONE}，
 * {@link #stance()} / {@link #max()} 均为 0。</p>
 */
public final class StanceSnapshot {
    private final UUID player;
    private final double stance;
    private final double max;
    private final StancePhase phase;
    private final boolean guardDisabled;
    private final long epochMillis;

    public StanceSnapshot(UUID player, double stance, double max, StancePhase phase,
                          boolean guardDisabled, long epochMillis) {
        this.player = player;
        this.stance = stance;
        this.max = max;
        this.phase = phase;
        this.guardDisabled = guardDisabled;
        this.epochMillis = epochMillis;
    }

    public UUID player() {
        return player;
    }

    /** 当前架势值。 */
    public double stance() {
        return stance;
    }

    /** 最大架势值（进入决斗时按资源公式确定）。 */
    public double max() {
        return max;
    }

    /** 剩余比例（0.0 ~ 1.0；max &lt;= 0 时为 0）。 */
    public double percentage() {
        return max <= 0.0 ? 0.0 : Math.min(1.0, Math.max(0.0, stance / max));
    }

    public StancePhase phase() {
        return phase;
    }

    /** 是否临界（已消耗比例达临界线且未崩条）。 */
    public boolean critical() {
        return phase == StancePhase.CRITICAL;
    }

    /** 是否崩条（处决窗口中）。 */
    public boolean broken() {
        return phase == StancePhase.BROKEN;
    }

    /** 是否处于「无法格挡」窗口（崩条受击状态 / 破盾 / 封印类禁格挡窗口）。 */
    public boolean guardDisabled() {
        return guardDisabled;
    }

    /** 快照生成时刻（epoch 毫秒，用于事件排序核对）。 */
    public long epochMillis() {
        return epochMillis;
    }
}
