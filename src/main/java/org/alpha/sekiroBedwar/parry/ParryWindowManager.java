package org.alpha.sekiroBedwar.parry;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 弹反窗口管理器（独立模块）：追踪每位决斗玩家的<b>格挡开始时刻</b>。
 *
 * <p>通过逐 {@code parry.poll-ticks} tick 轮询 {@code Player.isBlocking()}（false→true 转换）
 * 记录服务器单调时刻 {@code blockStart}；格挡结束 / 玩家下线 / 决斗结束 时清除。
 * 判定是否处于弹反窗口由 {@link ParryManager} 完成（本类只维护原始窗口状态）。</p>
 *
 * <p><b>0 延时（举盾即刻生效）</b>：轮询粒度为 1 tick（50ms）；若命中发生在轮询尚未记录
 * 格挡开始的同 tick 内（{@code blockStart} 为空但 {@code Player.isBlocking()} 为真），
 * 判定处经 {@link #eagerStart} 立即从命中时刻起算格挡开始——右键举盾即刻生效，无需等下一次轮询。</p>
 *
 * <p><b>一次按住只弹反一击</b>：{@link #consumeBlockStart} 置 {@code holdConsumed} 标记
 * （保留 {@code blockStart}，不删除），同一按住中的后续命中不再判为完美弹反；
 * 松开（轮询读到非格挡）清除标记与开始时刻，下一次重新举盾可再次弹反。</p>
 *
 * <p><b>线程安全</b>：{@code blockStart} / {@code holdConsumed} 用 {@link ConcurrentHashMap}，
 * 事件侧（主线程）读取；轮询任务（主线程）写入；{@code wasBlocking} 仅轮询任务访问。</p>
 */
public final class ParryWindowManager {
    private final SekiroBedwar plugin;
    private final ParryConfig config;
    private final LatencyCompensationManager latency;
    private final DuelManager duelManager;

    /** 玩家 UUID → 最近一次开始格挡的服务器单调时刻（毫秒）。 */
    private final ConcurrentMap<UUID, Long> blockStart = new ConcurrentHashMap<>();
    /** 玩家 UUID → 本次格挡按住是否已被弹反消耗（一次按住只弹反一击）。 */
    private final ConcurrentMap<UUID, Boolean> holdConsumed = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上一轮询是否在格挡（仅轮询任务访问）。 */
    private final Map<UUID, Boolean> wasBlocking = new HashMap<>();

    private BukkitTask pollTask;

    public ParryWindowManager(SekiroBedwar plugin, ParryConfig config,
                              LatencyCompensationManager latency, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.latency = latency;
        this.duelManager = duelManager;
    }

    /** 启动格挡状态轮询任务（由 {@link ParryManager#enable} 调用）。 */
    public void enable() {
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 1L, Math.max(1, config.pollTicks()));
    }

    /** 取消轮询任务并清空全部窗口状态（插件禁用）。 */
    public void disable() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        blockStart.clear();
        holdConsumed.clear();
        wasBlocking.clear();
    }

    /** 玩家当前是否正在格挡（隔离 {@code isBlocking()}，便于版本差异时单点替换）。 */
    public boolean isBlocking(Player player) {
        return player != null && player.isBlocking();
    }

    /** 玩家最近一次开始格挡的服务器单调时刻；未记录返回 null。 */
    public Long getBlockStartMs(UUID uuid) {
        return blockStart.get(uuid);
    }

    /**
     * 玩家当前格挡按住是否已被弹反消耗（一次按住只弹反一击）。
     * 该标记在 {@link #consumeBlockStart} 时置位，玩家松开盾牌（轮询读到非格挡）时清除。
     */
    public boolean isHoldConsumed(UUID uuid) {
        return Boolean.TRUE.equals(holdConsumed.get(uuid));
    }

    /**
     * 0 延时起算格挡开始：玩家已处于格挡（{@code Player.isBlocking()} 为真）但轮询
     * 尚未记录开始时刻（同 tick 内刚举盾）时，从判定时刻起算并返回。
     * 用 {@code putIfAbsent}，不覆盖既有记录；未格挡 / 失败返回 null。
     */
    public Long eagerStart(UUID uuid, long now) {
        blockStart.putIfAbsent(uuid, now);
        return blockStart.get(uuid);
    }

    /**
     * 消耗玩家一次“格挡按住”（完美弹反成功后调用）。
     *
     * <p>用于<b>一次按下只弹反一击</b>：置位 {@code holdConsumed}（保留 {@code blockStart}），
     * 同一按住中的后续命中经 {@link #isHoldConsumed} 判为非完美弹反，按普通格挡处理
     * （防止连续 / 快速攻击被连续判为完美弹反）。玩家放下盾牌（false）由轮询清除标记，
     * 下一次重新举盾可再次弹反。</p>
     */
    public void consumeBlockStart(UUID uuid) {
        holdConsumed.put(uuid, true);
    }

    /** 玩家下线 / 决斗结束：清除其格挡窗口状态。 */
    public void purge(UUID uuid) {
        blockStart.remove(uuid);
        holdConsumed.remove(uuid);
        wasBlocking.remove(uuid);
    }

    /** 逐 tick 轮询所有进行中决斗的玩家，记录格挡开始时刻。 */
    private void poll() {
        for (Duel duel : duelManager.getDuels()) {
            pollPlayer(duel.getPlayerA());
            pollPlayer(duel.getPlayerB());
        }
    }

    private void pollPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline()) {
            purge(player.getUniqueId());
            return;
        }
        UUID id = player.getUniqueId();
        boolean blocking = isBlocking(player);
        Boolean prev = wasBlocking.get(id);
        if (blocking) {
            if (prev == null || !prev) {
                // false → true：记录格挡开始时刻（服务器单调时钟）。
                // putIfAbsent：damage 事件可能已 eager 记录（同 tick 内举盾 0 延时），不覆盖。
                Long existing = blockStart.putIfAbsent(id, latency.monotonicMillis());
                if (existing == null) {
                    // 真正的新按住（此前无开始记录）→ 重置"已消耗"标记，本按住可弹反一击
                    holdConsumed.remove(id);
                }
            }
        } else {
            blockStart.remove(id);
            holdConsumed.remove(id);
        }
        wasBlocking.put(id, blocking);
    }
}
