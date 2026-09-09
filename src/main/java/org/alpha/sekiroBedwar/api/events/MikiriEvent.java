package org.alpha.sekiroBedwar.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>识破</b>（mikiri：受害者在下蹲窗口内接住危攻击）成功时广播
 * （公共 API，只读通知）。
 *
 * <p>效果由核心应用：危伤害取消 + 攻击方架势反扣。</p>
 */
public class MikiriEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID performer;
    private final UUID attacker;
    private final UUID duelId;

    public MikiriEvent(UUID performer, UUID attacker, UUID duelId) {
        this.performer = performer;
        this.attacker = attacker;
        this.duelId = duelId;
    }

    /** 识破成功方（受击玩家）。 */
    public UUID performer() {
        return performer;
    }

    /** 危攻击方。 */
    public UUID attacker() {
        return attacker;
    }

    /** 所属决斗（识破可在决斗外发生：可空）。 */
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
