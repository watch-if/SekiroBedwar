package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.DuelInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 决斗内出现死亡（击杀对手 / 被击落虚空）时广播（公共 API，只读通知）。
 *
 * <p>广播时刻：死亡结算已应用（资源转移已执行）、决斗随之结束前。
 * {@code ratio} 为本次死亡的资源转移比例（崩条 / 普通 / 虚空三情形之一，见核心配置）。</p>
 */
public class DuelDeathEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DuelInfo duel;
    private final UUID winner;
    private final UUID loser;
    private final double transferRatio;
    private final boolean voidDeath;

    public DuelDeathEvent(DuelInfo duel, UUID winner, UUID loser,
                          double transferRatio, boolean voidDeath) {
        this.duel = duel;
        this.winner = winner;
        this.loser = loser;
        this.transferRatio = transferRatio;
        this.voidDeath = voidDeath;
    }

    /** 决斗快照。 */
    public DuelInfo duel() {
        return duel;
    }

    /** 胜者（决斗对手方）。 */
    public UUID winner() {
        return winner;
    }

    /** 败者（死亡方）。 */
    public UUID loser() {
        return loser;
    }

    /** 资源转移比例（0.0 ~ 1.0：崩条死亡默认 0.5，普通 / 虚空死亡 1.0）。 */
    public double transferRatio() {
        return transferRatio;
    }

    /** 是否为被击落虚空死亡（视同击杀）。 */
    public boolean voidDeath() {
        return voidDeath;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
