package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.DuelEndReason;
import org.alpha.sekiroBedwar.api.DuelInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 一场决斗结束（任意原因）时广播（公共 API，只读通知、不可取消）。
 *
 * <p>第三方中断会<b>同时</b>广播 {@link DuelInterruptEvent}；死亡 / 处决结算在结束前
 * 已广播 {@link DuelDeathEvent} / {@link DuelSettlementEvent}。</p>
 */
public class DuelEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DuelInfo duel;
    private final DuelEndReason reason;

    public DuelEndEvent(DuelInfo duel, DuelEndReason reason) {
        this.duel = duel;
        this.reason = reason;
    }

    /** 决斗快照（phase = ENDING，endReason 同步可读）。 */
    public DuelInfo duel() {
        return duel;
    }

    /** 结束原因。 */
    public DuelEndReason reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
