package org.alpha.sekiroBedwar.stance;

/**
 * 单个玩家架势状态的不可变快照（供本插件内其他模块查询，{@link StanceManager#getState}）。
 *
 * <p>对崩条玩家，{@link #isBroken()}（处决窗口）与 {@link #isStaggered()}（受击状态 /
 * 无法格挡窗口）是两个独立但可并存的子状态——受击状态通常更短
 * （{@code stance.break.stagger.duration-seconds}），是崩条后短暂失去格挡能力的窗口；
 * 处决窗口（{@code execution-seconds}）更长，是结算 / 逃离的战术窗口。</p>
 *
 * <p>字段在取快照的瞬间从 {@link PlayerStance} 读出（其内 {@code volatile}），
 * 多字段组合读取<b>非原子</b>，为点时刻近似值，仅用于检测 / 展示。</p>
 */
public final class StanceStateInfo {
    private final boolean inDuel;
    private final double stance;
    private final double maxStance;
    private final boolean critical;
    private final boolean broken;
    private final boolean staggered;
    private final long brokenUntil;
    private final long staggerUntil;

    public StanceStateInfo(boolean inDuel, double stance, double maxStance,
                           boolean critical, boolean broken, boolean staggered,
                           long brokenUntil, long staggerUntil) {
        this.inDuel = inDuel;
        this.stance = stance;
        this.maxStance = maxStance;
        this.critical = critical;
        this.broken = broken;
        this.staggered = staggered;
        this.brokenUntil = brokenUntil;
        this.staggerUntil = staggerUntil;
    }

    /** 是否持有架势状态（在决斗中）。 */
    public boolean isInDuel() {
        return inDuel;
    }

    /** 是否达到临界状态（已消耗架势比例达到临界比例，且未崩条）。 */
    public boolean isCritical() {
        return critical;
    }

    /** 是否处于崩条状态（处决窗口开启）。 */
    public boolean isBroken() {
        return broken;
    }

    /** 是否处于受击状态（无法正常格挡窗口，通常伴随盾牌强制冷却）。 */
    public boolean isStaggered() {
        return staggered;
    }

    /** 别名：当前无法正常格挡（= {@link #isStaggered()}）。 */
    public boolean isGuardDisabled() {
        return staggered;
    }

    /** 当前架势值；不在决斗为 0。 */
    public double getStance() {
        return stance;
    }

    /** 最大架势值；不在决斗为 0。 */
    public double getMaxStance() {
        return maxStance;
    }

    /** 当前架势百分比（0.0 ~ 1.0，剩余制：满为 1.0）。 */
    public double getPercentage() {
        return maxStance <= 0.0 ? 0.0 : Math.min(1.0, Math.max(0.0, stance / maxStance));
    }

    /** 已消耗架势比例（1 - 剩余百分比，0.0 ~ 1.0；临界判断用）。 */
    public double getConsumedRatio() {
        return 1.0 - getPercentage();
    }

    /** 处决窗口截止时间戳（毫秒；未崩条为 0）。 */
    public long getBrokenUntil() {
        return brokenUntil;
    }

    /** 受击状态（无法格挡）窗口截止时间戳（毫秒；未进入为 0）。 */
    public long getStaggerUntil() {
        return staggerUntil;
    }

    /** 处决窗口剩余毫秒（≥0；未崩条为 0）。 */
    public long getBrokenRemainingMillis() {
        return Math.max(0L, brokenUntil - System.currentTimeMillis());
    }

    /** 受击状态剩余毫秒（≥0；未进入为 0）。 */
    public long getStaggerRemainingMillis() {
        return Math.max(0L, staggerUntil - System.currentTimeMillis());
    }

    /** 处决窗口剩余 tick（1/20 秒，向上取整）。 */
    public long getBrokenRemainingTicks() {
        return (long) Math.ceil(getBrokenRemainingMillis() / 50.0);
    }

    /** 受击状态剩余 tick（1/20 秒，向上取整）。 */
    public long getStaggerRemainingTicks() {
        return (long) Math.ceil(getStaggerRemainingMillis() / 50.0);
    }

    /** 合并阶段：崩条 &gt; 临界 &gt; 正常（不在决斗为 NORMAL）。 */
    public StanceStatus getStatus() {
        if (broken) {
            return StanceStatus.BROKEN;
        }
        if (critical) {
            return StanceStatus.CRITICAL;
        }
        return StanceStatus.NORMAL;
    }
}
