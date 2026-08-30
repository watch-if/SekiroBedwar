package org.alpha.sekiroBedwar.parry;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 弹反窗口网络补偿 + 单调时钟（独立模块）。
 *
 * <p>对每个玩家维护 {@code getPing()} 的 EWMA 平滑值（α 可配置），并据此计算网络补偿，
 * <b>只在 base-window-ms 上放大窗口</b>（不区分攻击前/后、不做 RTT/2 回溯）：
 * <pre>
 *   comp            = min(comp-max-ms, max(0, (smoothRTT − comp-floor-ms) / comp-divisor))
 *   effectiveWindow = base-window-ms + comp   // 举盾后窗口，默认 170→高延迟最多 +40
 * </pre>
 * 完美弹反窗口 =「举盾后 effectiveWindow 内」命中的攻击（见 {@link ParryManager}）；
 * 补偿封顶（{@code comp-max-ms}），高延迟不会无限扩大窗口。时序一律用服务器单调时钟
 * {@link #monotonicMillis()}（{@link System#nanoTime()} 归一化，只用于差值，避免墙钟跳变）。</p>
 *
 * <p><b>线程安全</b>：状态容器 {@link ConcurrentHashMap}，读操作任意线程；
 * 写操作由事件回调 / 调度任务（Bukkit 主线程）执行。</p>
 */
public final class LatencyCompensationManager {
    private final ParryConfig config;

    /** 玩家 UUID → EWMA 平滑 RTT（毫秒）。 */
    private final ConcurrentMap<UUID, Double> smoothedRtt = new ConcurrentHashMap<>();

    public LatencyCompensationManager(ParryConfig config) {
        this.config = config;
    }

    /** 服务器单调时钟（毫秒，仅用于差值比较，避免墙钟跳变）。 */
    public long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * 采样玩家当前 ping 并更新 EWMA 平滑值，返回平滑后的 RTT（毫秒）。
     * 首次采样即用当前 ping 初始化。
     */
    public double smoothRtt(Player player) {
        if (player == null) {
            return 0.0;
        }
        UUID id = player.getUniqueId();
        double ping = Math.max(0.0, player.getPing());
        Double prev = smoothedRtt.get(id);
        double smooth = (prev == null)
                ? ping
                : config.alpha() * ping + (1.0 - config.alpha()) * prev;
        smoothedRtt.put(id, smooth);
        return smooth;
    }

    /** 网络补偿：{@code min(comp-max, max(0, (smoothRTT − floor) / divisor))}。 */
    public double compensation(double smoothRtt) {
        double over = Math.max(0.0, smoothRtt - config.compFloorMs());
        return Math.min(config.compMaxMs(), over / config.compDivisor());
    }

    /** 举盾后弹反窗口（毫秒）= 基础窗口 + 补偿。 */
    public double effectiveWindow(double smoothRtt) {
        return config.baseWindowMs() + compensation(smoothRtt);
    }

    /** 玩家下线 / 决斗结束：移除其平滑 RTT 状态。 */
    public void purge(UUID uuid) {
        smoothedRtt.remove(uuid);
    }

    /** 插件禁用：清空全部状态。 */
    public void clear() {
        smoothedRtt.clear();
    }
}
