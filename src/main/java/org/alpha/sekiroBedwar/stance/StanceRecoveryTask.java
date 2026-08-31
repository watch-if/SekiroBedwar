package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * 架势自然恢复任务（独立模块，与架势状态管理解耦）。
 *
 * <p>对每个持有架势状态的玩家（即正在决斗中的玩家），按公式逐 tick 应用自然恢复：
 * <pre>
 *   dS/dt = +r · Smax · (1/2 + x/2)，x = S/Smax = current/max
 * </pre>
 * 其中 r 为配置速率（{@code stance.natural-recovery.rate}，默认 0.16）。每 tick 增量 = 整秒公式值 ÷ 20。
 * 架势越接近满恢复越快（封顶于 max，满则停），
 * 越接近崩条恢复越慢（被打到低谷后仍需一段时间才能爬回，保留崩条压力）；崩条（处决窗口）期间不恢复。</p>
 *
 * <p><b>idle 触发</b>：距上次“战斗活跃”（{@code stance.natural-recovery.idle-seconds}，默认 5s）
 * 超过阈值才恢复。战斗活跃包括：任意外部架势变化（被攻击扣架势 / 崩条等），以及
 * {@link StanceManager#markActive} 标记——攻击方产生攻击、受击方受到攻击都会重置计时并暂停恢复
 * （攻击方成功出手时架势值可能不变，但持续作战不应被自然恢复）。即自然恢复只在
 * <b>既没产生攻击、也没受到攻击</b>的持续空闲后触发。
 * 自然恢复经 {@link StanceManager#recoverStance} 增加（不刷新变化计时），故一旦 idle 会持续恢复
 * 直至满架势或再次发生战斗活跃。</p>
 *
 * <p><b>线程安全</b>：任务由 {@code runTaskTimer} 驱动（主线程），遍历 {@link StanceManager#getActiveUuids()}
 * 只读快照；{@link StanceManager#recoverStance} 操作 volatile + synchronized 状态。</p>
 */
public final class StanceRecoveryTask {
    private final SekiroBedwar plugin;
    private final StanceConfig config;
    private final StanceManager stanceManager;

    private BukkitTask task;

    public StanceRecoveryTask(SekiroBedwar plugin, StanceConfig config, StanceManager stanceManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
    }

    /** 若配置启用自然恢复则启动任务（1 秒后每 tick 一次），否则不启动。 */
    public void enable() {
        ensureMainThread("enable");
        if (!config.naturalRecoveryEnabled()) {
            plugin.getLogger().info("StanceRecoveryTask 已禁用（stance.natural-recovery.enabled=false）");
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    /** 插件禁用：取消任务。 */
    public void disable() {
        ensureMainThread("disable");
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 逐 tick 应用自然恢复（仅对超过 idle 阈值无外部变化的玩家生效）。 */
    private void tick() {
        double rate = config.naturalRecoveryRate();
        if (rate <= 0.0) {
            return;
        }
        long idleMillis = (long) (config.naturalRecoveryIdleSeconds() * 1000.0);
        long now = System.currentTimeMillis();
        for (UUID uuid : stanceManager.getActiveUuids()) {
            // idle 触发：5 秒内有过战斗活跃（产生/受到攻击、架势变化）则暂停自然恢复
            if (now - stanceManager.getLastStanceChangeAt(uuid) < idleMillis) {
                continue;
            }
            // 崩条（处决窗口）是惩罚期，期间不恢复；崩条中当前架势恒为 0
            if (stanceManager.isBroken(uuid)) {
                continue;
            }
            double max = stanceManager.getMaxStance(uuid);
            double current = stanceManager.getStance(uuid);
            if (max <= 0.0 || current >= max) {
                continue;
            }
            double x = current / max;
            double deltaPerTick = rate * max * (0.5 + 0.5 * x) / 20.0;
            stanceManager.recoverStance(uuid, deltaPerTick);
        }
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败，防止异步线程篡改状态）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("StanceRecoveryTask." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
