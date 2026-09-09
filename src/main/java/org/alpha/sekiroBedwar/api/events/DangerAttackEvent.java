package org.alpha.sekiroBedwar.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>危攻击</b>（主手矛 + 突进 LUNGE 附魔 + 疾跑）命中判定时广播
 * （公共 API，只读通知；决斗内外均可发生——危是 1.21.11 原生矛机制）。
 *
 * <p>危攻击不可被完美弹反；被格挡时触发破盾（{@link ShieldBreakEvent}，cause DANGER）；
 * 被识破时同时广播 {@link MikiriEvent}。本事件在伤害分支应用前发出，
 * 结果字段（blocked / mikiri）取自核心同一轮判定。</p>
 */
public class DangerAttackEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID attacker;
    private final UUID victim;
    private final UUID duelId;
    private final boolean blocked;
    private final boolean mikiri;

    public DangerAttackEvent(UUID attacker, UUID victim, UUID duelId, boolean blocked, boolean mikiri) {
        this.attacker = attacker;
        this.victim = victim;
        this.duelId = duelId;
        this.blocked = blocked;
        this.mikiri = mikiri;
    }

    public UUID attacker() {
        return attacker;
    }

    public UUID victim() {
        return victim;
    }

    /** 所属决斗（危攻击可在决斗外发生：可空）。 */
    public UUID duelId() {
        return duelId;
    }

    /** 是否处于格挡状态（格挡危攻击将被破盾）。 */
    public boolean blocked() {
        return blocked;
    }

    /** 是否被识破（下蹲窗口内接危：免疫伤害并反扣攻击方架势）。 */
    public boolean mikiri() {
        return mikiri;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
