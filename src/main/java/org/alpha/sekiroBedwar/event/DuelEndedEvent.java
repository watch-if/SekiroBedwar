package org.alpha.sekiroBedwar.event;

import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.duel.EndReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.screamingsandals.bedwars.api.game.LocalGame;

/**
 * 决斗结束事件：由 {@link org.alpha.sekiroBedwar.duel.DuelManager} 在收尾时广播，
 * 此时决斗状态为 {@link DuelState#ENDING}（可观察）。
 *
 * <p>结束原因见 {@link EndReason}（第三方进入 / 玩家下线 / 离局 / 对局结束 / 主动结束 / 插件禁用）。</p>
 */
public class DuelEndedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Duel duel;

    public DuelEndedEvent(Duel duel) {
        super();
        this.duel = duel;
    }

    /** 结束的决斗对象。 */
    public Duel getDuel() {
        return duel;
    }

    /** 决斗玩家 A（可能已离线返回 null）。 */
    public Player getPlayerA() {
        return duel.getPlayerA();
    }

    /** 决斗玩家 B（可能已离线返回 null）。 */
    public Player getPlayerB() {
        return duel.getPlayerB();
    }

    /** 两名玩家所属的同一对局。 */
    public LocalGame getGame() {
        return duel.getGame();
    }

    /** 决斗岛屿（圆心 / 半径）。 */
    public DuelIsland getIsland() {
        return duel.getIsland();
    }

    /** 结束原因。 */
    public EndReason getReason() {
        return duel.getEndReason();
    }

    /** 决斗状态（广播时恒为 {@link DuelState#ENDING}）。 */
    public DuelState getState() {
        return duel.getState();
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
