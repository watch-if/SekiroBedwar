package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.DuelInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 一场决斗创建成功（进入 PENDING 缓冲）时广播（公共 API，只读通知、不可取消）。
 *
 * <p>触发条件由核心判定（敌对双方互相有效命中 + 同岛屿 + 无第三方）；外部插件
 * （统计 / 录像）以本事件为决斗时间线起点。</p>
 */
public class DuelStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DuelInfo duel;

    public DuelStartEvent(DuelInfo duel) {
        this.duel = duel;
    }

    /** 决斗快照（phase = PENDING）。 */
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
