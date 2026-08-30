package org.alpha.sekiroBedwar.stance;

/**
 * 单个玩家的架势状态对象。
 *
 * <p>与 Minecraft 原版生命值完全解耦。包含：
 * <ul>
 *   <li>{@link #current}：当前架势值；</li>
 *   <li>{@link #max}：最大架势值（由背包资源按配置公式计算，进入决斗时固定，可重算）；</li>
 *   <li>{@link #brokenUntil}：崩条状态截止时间戳（崩条期间 {@link #isBroken()} 为真）；</li>
 *   <li>{@link #guardDisabledUntil}：无法正常格挡（处决窗口）截止时间戳。</li>
 * </ul></p>
 *
 * <p>字段 {@code volatile} 保证可见性；组合操作（钳制 / 崩条）用 {@code synchronized} 保证原子性。</p>
 */
public final class PlayerStance {
    private volatile double current;
    private volatile double max;
    private volatile long brokenUntil;
    private volatile long guardDisabledUntil;
    /** 最近一次“外部架势变化”时间戳（自然恢复不刷新它），用于 idle 触发判断。 */
    private volatile long lastChangedAt;

    PlayerStance(double initial) {
        this.current = initial;
        this.max = initial;
        this.lastChangedAt = System.currentTimeMillis();
    }

    /** 最近一次外部架势变化时间戳（毫秒）。 */
    public long getLastChangedAt() {
        return lastChangedAt;
    }

    /** 标记一次外部架势变化（刷新 idle 计时）。 */
    private void touch() {
        this.lastChangedAt = System.currentTimeMillis();
    }

    /**
     * 标记一次“战斗活跃”（产生攻击 / 受到攻击）：刷新 idle 计时，但<b>不改动架势数值</b>。
     * 攻击方成功出手时架势值可能不变（无格挡/普通格挡只扣防守方），仍需借此让自然恢复保持暂停。
     */
    public void markActive() {
        touch();
    }

    public double getCurrent() {
        return current;
    }

    public double getMax() {
        return max;
    }

    /** 是否处于崩条状态（处决窗口开启）。 */
    public boolean isBroken() {
        return System.currentTimeMillis() < brokenUntil;
    }

    /**
     * 处决窗口是否已到期：曾崩条（设置过 {@code brokenUntil}）且当前时刻已越过截止时间。
     * 供结算模块判断“窗口到期未击杀 → 半额结算”。
     */
    public boolean isExecutionWindowExpired() {
        return brokenUntil > 0L && System.currentTimeMillis() >= brokenUntil;
    }

    /** 当前能否正常格挡（未处于无法格挡的处决窗口）。 */
    public boolean canBlock() {
        return System.currentTimeMillis() >= guardDisabledUntil;
    }

    /** 无法格挡窗口的截止时间戳（毫秒；0 = 从未崩条 / 已清除）。供强制冷却任务计算剩余时长。 */
    public long getGuardDisabledUntil() {
        return guardDisabledUntil;
    }

    /** 处决窗口（崩条状态）的截止时间戳（毫秒；0 = 未崩条 / 已清除）。 */
    public long getBrokenUntil() {
        return brokenUntil;
    }

    /**
     * 是否处于受击状态：当前时刻在无法格挡窗口内
     * （now &lt; {@link #guardDisabledUntil}，与 {@link #canBlock()} 相反）。
     * 受击状态期间无法正常格挡（可配置 disable-blocking 强制盾牌冷却），
     * 但仍可移动、可攻击；到期自动恢复。
     */
    public boolean isStaggered() {
        return System.currentTimeMillis() < guardDisabledUntil;
    }

    /** 当前架势 / 最大架势（0.0 ~ 1.0）。 */
    public double getPercentage() {
        return max <= 0.0 ? 0.0 : Math.min(1.0, Math.max(0.0, current / max));
    }

    /** 是否满架势。 */
    public boolean isFull() {
        return current >= max;
    }

    /** 设置当前架势并钳制到 [0, max]。 */
    public synchronized void setCurrent(double value) {
        this.current = clamp(value);
        touch();
    }

    /** 增加当前架势（不超过 max）。 */
    public synchronized void add(double amount) {
        this.current = clamp(this.current + amount);
        touch();
    }

    /** 减少当前架势（不低于 0）。 */
    public synchronized void reduce(double amount) {
        this.current = clamp(this.current - amount);
        touch();
    }

    /**
     * 自然恢复：增加当前架势（不超过 max），但<b>不刷新</b> {@link #lastChangedAt}
     * （自然恢复本身不算“架势变化”，否则会永远重置 idle 计时导致无法持续恢复）。
     */
    public synchronized void recover(double amount) {
        this.current = clamp(this.current + amount);
    }

    /** 将当前架势拉满至最大架势。 */
    public synchronized void setFull() {
        this.current = this.max;
        touch();
    }

    /** 设置最大架势并重新钳制当前架势。 */
    public synchronized void setMax(double max) {
        this.max = Math.max(0.0, max);
        this.current = clamp(this.current);
        touch();
    }

    /**
     * 触发崩条：当前架势清零，进入直到 {@code brokenUntil} 的结算 / 逃离窗口
     * （期间 {@link #isBroken()} 为真）与直到 {@code guardDisabledUntil} 的无法格挡窗口
     * （期间 {@link #canBlock()} 为假）。两窗口可分离配置（处决窗口通常更长）。
     */
    public synchronized void breakStance(long brokenUntil, long guardDisabledUntil) {
        this.current = 0.0;
        this.brokenUntil = brokenUntil;
        this.guardDisabledUntil = guardDisabledUntil;
        touch();
    }

    /** 清除崩条与无法格挡状态（窗口提前结束 / 决斗清理）。 */
    public synchronized void clearBreak() {
        this.brokenUntil = 0L;
        this.guardDisabledUntil = 0L;
        touch();
    }

    /**
     * 仅禁用格挡：把无法格挡窗口置为 {@code until}（不动当前架势 / 崩条状态）。
     * 供「破盾」（斧击普通格挡）等短暂无法格挡机制使用——不触发崩条，但直到窗口到期
     * {@code canBlock()} 为假、{@link #isStaggered()} 为真。
     */
    public synchronized void disableBlocking(long until) {
        this.guardDisabledUntil = until;
        touch();
    }

    /**
     * 立即清除无法格挡窗口（{@code guardDisabledUntil = 0}，不动当前架势 / 崩条状态）。
     * 供「完美弹反成功立刻恢复格挡能力」（消除破盾效果）使用——窗口清除后
     * {@code canBlock()} 立即为真、{@link #isStaggered()} 立即为假，
     * 强制冷却任务 {@code enforceGuard} 也随之停止刷新盾牌冷却。
     */
    public synchronized void clearBlockingDisable() {
        this.guardDisabledUntil = 0L;
        touch();
    }

    private double clamp(double value) {
        return Math.min(max, Math.max(0.0, value));
    }
}
