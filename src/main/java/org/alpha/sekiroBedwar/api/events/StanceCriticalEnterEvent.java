package org.alpha.sekiroBedwar.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 玩家架势<b>进入临界状态</b>（已消耗比例达临界线，默认 = 架势条空）时广播一次
 * （公共 API，只读通知）。
 *
 * <p>临界不自动崩条；下一次未弹反的近战命中 / 被完美弹反将触发
 * {@link StanceBreakEvent}。持续处于临界期间的数值波动不会重复广播本事件
 * （仅在 NORMAL → CRITICAL 跃迁时触发一次）。</p>
 */
public class StanceCriticalEnterEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final UUID duelId;
    private final double max;

    public StanceCriticalEnterEvent(UUID player, UUID duelId, double max) {
        this.player = player;
        this.duelId = duelId;
        this.max = max;
    }

    public UUID player() {
        return player;
    }

    public UUID duelId() {
        return duelId;
    }

    /** 该玩家最大架势（用于计算"距临界点"的历史量）。 */
    public double max() {
        return max;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
