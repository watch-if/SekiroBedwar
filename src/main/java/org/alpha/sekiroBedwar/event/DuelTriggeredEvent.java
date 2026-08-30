package org.alpha.sekiroBedwar.event;

import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.screamingsandals.bedwars.api.game.LocalGame;

/**
 * 决斗触发成功事件：两名敌对玩家在同一对局、同一实心岛屿、无第三方、
 * 且双方近期互相有效命中后由 {@link org.alpha.sekiroBedwar.duel.DuelTriggerManager} 广播。
 *
 * <p>后续只狼系统（架势、血量、阶段规则等）通过 {@code Bukkit.getPluginManager().registerEvents(...)}
 * 监听本事件进入决斗逻辑。</p>
 */
public class DuelTriggeredEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player playerA;
    private final Player playerB;
    private final LocalGame game;
    private final DuelIsland island;

    public DuelTriggeredEvent(Player playerA, Player playerB, LocalGame game, DuelIsland island) {
        super();
        this.playerA = playerA;
        this.playerB = playerB;
        this.game = game;
        this.island = island;
    }

    /** 决斗玩家 A。 */
    public Player getPlayerA() {
        return playerA;
    }

    /** 决斗玩家 B。 */
    public Player getPlayerB() {
        return playerB;
    }

    /** 两名玩家所属的同一对局。 */
    public LocalGame getGame() {
        return game;
    }

    /** 触发时推导出的决斗岛屿（圆心 = 两人中点，半径 = 配置 radius）。 */
    public DuelIsland getIsland() {
        return island;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
