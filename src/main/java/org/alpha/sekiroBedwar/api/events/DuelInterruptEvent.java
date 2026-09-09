package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.DuelInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 决斗因<b>第三方进入排除区域</b>被强制中断时广播（与 {@link DuelEndEvent} 同时出现，
 * 结束原因为 THIRD_PARTY_INTERRUPTED；公共 API，只读通知）。
 *
 * <p>核心行为：双方资源回滚到决斗开始快照（见 {@link DuelSettlementEvent} 的 ROLLBACK
 * 结果），不产生击杀与转移。中断者的具体身份核心不定位（周期巡检发现圈内第三方即中断），
 * 需要精确身份的外部系统可自行采样决斗圈内玩家。</p>
 */
public class DuelInterruptEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DuelInfo duel;

    public DuelInterruptEvent(DuelInfo duel) {
        this.duel = duel;
    }

    /** 被中断的决斗快照。 */
    public DuelInfo duel() {
        return duel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
