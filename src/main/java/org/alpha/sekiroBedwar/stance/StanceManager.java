package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 架势系统核心管理器（独立模块，与触发 / 决斗生命周期解耦）。
 *
 * <p>为每个玩家维护 {@link PlayerStance}（当前架势 / 最大架势 / 崩条 / 无法格挡状态），
 * 与 Minecraft 原版生命值完全解耦。提供：
 * <ul>
 *   <li>增 / 减 / 设当前架势、获取百分比、满架势检测；</li>
 *   <li>临界判断、崩条（清零 + 处决窗口）、无法格挡状态；</li>
 *   <li>最大架势按背包资源计算（只读背包，不修改物品）：{@code Smax = S0 + k·Σ coeffᵢ·ln(1+countᵢ)}。</li>
 * </ul></p>
 *
 * <p><b>线程安全</b>：状态容器 {@link ConcurrentHashMap}，读操作任意线程；
 * 所有写操作经 {@link #ensureMainThread} 守卫强制在 Bukkit 主线程执行（事件回调与调度任务天然主线程）。
 * {@link PlayerStance} 内 volatile + synchronized 保证原子性。</p>
 *
 * <p><b>内存泄漏防护</b>：状态只在决斗期间创建（{@link #beginDuel}），
 * 决斗结束 / 玩家下线经 {@link #endDuel} / {@link #purgePlayer} 移除。</p>
 */
public final class StanceManager {
    private final SekiroBedwar plugin;
    private final StanceConfig config;

    /** 玩家 UUID → 架势状态。 */
    private final ConcurrentMap<UUID, PlayerStance> stances = new ConcurrentHashMap<>();

    public StanceManager(SekiroBedwar plugin, StanceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** 玩家进入决斗：初始化架势状态（current = max，按当前背包资源计算），保留原有崩条状态由处决窗口管理。 */
    public void beginDuel(Player a, Player b) {
        ensureMainThread("beginDuel");
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
            return;
        }
        initPlayer(a);
        initPlayer(b);
    }

    /** 决斗结束：移除双方架势状态。 */
    public void endDuel(UUID a, UUID b) {
        ensureMainThread("endDuel");
        if (a != null) {
            stances.remove(a);
        }
        if (b != null) {
            stances.remove(b);
        }
    }

    /** 玩家下线：移除其架势状态。 */
    public void purgePlayer(UUID uuid) {
        ensureMainThread("purgePlayer");
        stances.remove(uuid);
    }

    /** 插件禁用：清空全部架势状态。 */
    public void disable() {
        ensureMainThread("disable");
        stances.clear();
    }

    private void initPlayer(Player player) {
        double max = computeMaxStance(player);
        PlayerStance stance = new PlayerStance(max);
        stances.put(player.getUniqueId(), stance);
    }

    /** 当前架势值；不在决斗中返回 0。 */
    public double getStance(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0.0 : stance.getCurrent();
    }

    /** 当前最大架势值；不在决斗中返回 0。 */
    public double getMaxStance(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0.0 : stance.getMax();
    }

    /** 当前架势百分比（0.0 ~ 1.0）；不在决斗中返回 0。 */
    public double getPercentage(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0.0 : stance.getPercentage();
    }

    /** 增加当前架势（不超最大架势）。 */
    public void addStance(UUID uuid, double amount) {
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.add(Math.max(0.0, amount));
        }
    }

    /** 减少当前架势（不低于 0）。 */
    public void reduceStance(UUID uuid, double amount) {
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.reduce(Math.max(0.0, amount));
        }
    }

    /**
     * 自然恢复：增加当前架势（不超最大架势），但不刷新该玩家的“架势变化”计时
     * （自然恢复本身不算变化，否则会持续重置 idle 计时导致永远无法恢复）。
     * 供 {@link StanceRecoveryTask} 使用。
     */
    public void recoverStance(UUID uuid, double amount) {
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.recover(Math.max(0.0, amount));
        }
    }

    /** 最近一次外部架势变化时间戳（毫秒）；不在决斗中返回 0。 */
    public long getLastStanceChangeAt(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0L : stance.getLastChangedAt();
    }

    /**
     * 标记玩家“战斗活跃”（产生攻击 / 受到攻击）：刷新自然下降的 idle 计时，
     * 但<b>不改变架势数值</b>。
     *
     * <p>攻击方成功出手时架势值可能不变（无格挡 / 普通格挡只扣防守方架势），
     * 若不标记则持续进攻也会被自然恢复；配合 {@code stance.natural-recovery.idle-seconds}，
     * 自然恢复只在“既没产生攻击、也没受到攻击”的持续空闲后触发。</p>
     *
     * <p>不在决斗中为无操作。</p>
     */
    public void markActive(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.markActive();
        }
    }

    /** 直接设置当前架势（钳制到 [0, max]）。 */
    public void setStance(UUID uuid, double value) {
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.setCurrent(value);
        }
    }

    /** 是否满架势。 */
    public boolean isFull(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance != null && stance.isFull();
    }

    /**
     * 是否达到临界状态：<b>已消耗</b>架势比例（1 - current/max）≥ 配置的临界比例
     * （{@code stance.break.critical-ratio}，默认 1.0 = 架势条空 / current≈0），且<b>未处于崩条状态</b>。
     *
     * <p>到达临界<b>不会自动崩条</b>，只作为 StanceBreakManager 崩条触发的前置条件
     * （临界中未弹反命中 / 被对方弹反才崩）。</p>
     */
    public boolean isCritical(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        if (stance == null) {
            return false;
        }
        return !stance.isBroken() && stance.getPercentage() <= (1.0 - config.breakCriticalRatio()) + 1e-6;
    }

    /** 是否处于崩条状态（处决窗口）。 */
    public boolean isBroken(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance != null && stance.isBroken();
    }

    /** 处决窗口是否已到期（曾崩条且当前时刻已越过截止时间），供结算模块判断半额结算。 */
    public boolean isExecutionWindowExpired(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance != null && stance.isExecutionWindowExpired();
    }

    /** 当前持有架势状态的玩家 UUID 快照（供自然下降任务枚举，只读）。 */
    public Set<UUID> getActiveUuids() {
        return Set.copyOf(stances.keySet());
    }

    /** 当前能否正常格挡。 */
    public boolean canBlock(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance != null && stance.canBlock();
    }

    /** 无法格挡窗口的截止时间戳（毫秒；不在决斗中返回 0）。供强制冷却任务计算剩余时长。 */
    public long getGuardDisabledUntil(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0L : stance.getGuardDisabledUntil();
    }

    // ==================== 状态查询 API（供本插件内其他模块检测崩条 / 受击状态） ====================

    /** 是否处于崩条状态（处决窗口开启）；{@link Player} 重载。 */
    public boolean isBroken(Player player) {
        return player != null && isBroken(player.getUniqueId());
    }

    /** 当前能否正常格挡；{@link Player} 重载。 */
    public boolean canBlock(Player player) {
        return player != null && canBlock(player.getUniqueId());
    }

    /**
     * 是否处于<b>受击状态</b>（无法正常格挡窗口：now &lt; guardDisabledUntil）。
     *
     * <p>受击状态是崩条后的短暂限制窗口：期间无法正常格挡（配合
     * {@code stagger.disable-blocking} 强制盾牌冷却），但仍可移动、可攻击；
     * 到期自动恢复。与处决窗口（{@link #isBroken}）独立——通常受击状态更短。</p>
     */
    public boolean isStaggered(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance != null && stance.isStaggered();
    }

    /** 是否处于受击状态；{@link Player} 重载。 */
    public boolean isStaggered(Player player) {
        return player != null && isStaggered(player.getUniqueId());
    }

    /** 处决窗口（崩条状态）截止时间戳（毫秒；不在决斗中返回 0）。 */
    public long getBrokenUntil(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        return stance == null ? 0L : stance.getBrokenUntil();
    }

    /** 处决窗口剩余毫秒（≥0；不在决斗中返回 0）。 */
    public long getBrokenRemainingMillis(UUID uuid) {
        return Math.max(0L, getBrokenUntil(uuid) - System.currentTimeMillis());
    }

    /** 受击状态（无法格挡）窗口剩余毫秒（≥0；不在决斗中返回 0）。 */
    public long getStaggerRemainingMillis(UUID uuid) {
        return Math.max(0L, getGuardDisabledUntil(uuid) - System.currentTimeMillis());
    }

    /** 目标架势阶段：崩条 &gt; 临界 &gt; 正常；不在决斗恒为 {@link StanceStatus#NORMAL}。 */
    public StanceStatus getStatus(UUID uuid) {
        if (isBroken(uuid)) {
            return StanceStatus.BROKEN;
        }
        if (isCritical(uuid)) {
            return StanceStatus.CRITICAL;
        }
        return StanceStatus.NORMAL;
    }

    /** 目标架势阶段；{@link Player} 重载。 */
    public StanceStatus getStatus(Player player) {
        return player == null ? StanceStatus.NORMAL : getStatus(player.getUniqueId());
    }

    /** 目标架势状态的不可变快照（不在决斗返回空快照，不创建任何状态）。 */
    public StanceStateInfo getState(UUID uuid) {
        PlayerStance stance = stances.get(uuid);
        if (stance == null) {
            return new StanceStateInfo(false, 0.0, 0.0, false, false, false, 0L, 0L);
        }
        return new StanceStateInfo(true, stance.getCurrent(), stance.getMax(),
                isCritical(uuid), stance.isBroken(), stance.isStaggered(),
                stance.getBrokenUntil(), stance.getGuardDisabledUntil());
    }

    /** 目标架势状态的不可变快照；{@link Player} 重载。 */
    public StanceStateInfo getState(Player player) {
        return player == null
                ? new StanceStateInfo(false, 0.0, 0.0, false, false, false, 0L, 0L)
                : getState(player.getUniqueId());
    }

    /**
     * 触发崩条：当前架势清零 + 进入两个配置窗口——
     * 结算 / 逃离窗口（{@code execution-seconds}，期间 {@code isBroken()} 为真）与
     * 受击状态 / 无法正常格挡窗口（{@code stagger.duration-seconds}，期间 {@code canBlock()} 为假、
     * {@link #isStaggered} 为真，可分别配置时长）。
     */
    public void breakStance(UUID uuid) {
        ensureMainThread("breakStance");
        PlayerStance stance = stances.get(uuid);
        if (stance == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long brokenUntil = now + (long) (config.executionSeconds() * 1000.0);
        long guardUntil = now + (long) (config.staggerDurationSeconds() * 1000.0);
        stance.breakStance(brokenUntil, guardUntil);
    }

    /**
     * 仅禁用格挡（不触发崩条）：把目标玩家的无法格挡窗口延长到 {@code seconds} 秒后。
     * 供「破盾」（斧击普通格挡）等机制使用；窗口内 {@code canBlock()} 为假、
     * {@link #isStaggered()} 为真。已存在更晚的窗口（如崩条受击状态）不会被提前。
     */
    public void disableBlocking(UUID uuid, double seconds) {
        ensureMainThread("disableBlocking");
        PlayerStance stance = stances.get(uuid);
        if (stance == null || seconds <= 0.0) {
            return;
        }
        long until = System.currentTimeMillis() + (long) (seconds * 1000.0);
        if (until > stance.getGuardDisabledUntil()) {
            stance.disableBlocking(until);
        }
    }

    /**
     * 立即清除无法格挡窗口（不触发崩条、不动当前架势 / 崩条状态）。
     * 供「完美弹反成功立刻恢复格挡能力」（消除破盾效果）使用——清除后
     * {@code canBlock()} 立即为真，强制冷却任务 {@code enforceGuard} 停止刷新盾牌冷却。
     */
    public void clearBlockingDisable(UUID uuid) {
        ensureMainThread("clearBlockingDisable");
        PlayerStance stance = stances.get(uuid);
        if (stance != null) {
            stance.clearBlockingDisable();
        }
    }

    /** 按当前背包资源重算最大架势（进入决斗 / 崩条后重算的扩展点），当前架势随之钳制。 */
    public void recalculateMax(UUID uuid) {
        ensureMainThread("recalculateMax");
        PlayerStance stance = stances.get(uuid);
        if (stance == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        stance.setMax(computeMaxStance(player));
    }

    /**
     * 按玩家背包携带的配置资源计算最大架势：
     * {@code Smax = base + multiplier · Σ coeffᵢ·ln(1 + countᵢ)}。
     * 只读取背包物品数量，绝不修改任何物品。
     */
    private double computeMaxStance(Player player) {
        Map<Material, Double> coefficients = config.resourceCoefficients();
        double weighted = 0.0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Double coeff = coefficients.get(item.getType());
            if (coeff != null && coeff > 0.0) {
                weighted += coeff * Math.log(1.0 + item.getAmount());
            }
        }
        return config.maxStanceBase() + config.maxStanceMultiplier() * weighted;
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败，防止异步线程篡改状态）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("StanceManager." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
