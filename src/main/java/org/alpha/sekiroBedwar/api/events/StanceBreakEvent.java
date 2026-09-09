package org.alpha.sekiroBedwar.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 架势崩溃（崩条）发生时广播（公共 API，只读通知）。
 *
 * <p>崩条 = 当前架势清零 + 进入处决 / 逃离窗口 + 短暂无法格挡（受击状态）。
 * 触发方式：临界中被对方近战未弹反命中，或自己的近战攻击被对方完美弹反
 * （两种都会广播本事件，{@code opponent} 为对方玩家）。</p>
 */
public class StanceBreakEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final UUID opponent;
    private final UUID duelId;

    public StanceBreakEvent(UUID player, UUID opponent, UUID duelId) {
        this.player = player;
        this.opponent = opponent;
        this.duelId = duelId;
    }

    /** 崩条玩家。 */
    public UUID player() {
        return player;
    }

    /** 对方玩家（可空：防御性场景）。 */
    public UUID opponent() {
        return opponent;
    }

    public UUID duelId() {
        return duelId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
