package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.DuelInfo;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.UUID;

/**
 * 决斗结算广播（公共 API，只读通知）：击杀 / 处决超时的<b>资源转移</b>，
 * 或第三方中断的<b>快照回滚</b>。
 *
 * <p>死亡类结算先于 {@link DuelDeathEvent} 应用；本事件是"结算数据"的正式口径
 * （统计 / 排位插件应消费本事件而非自行猜测背包变化）。外部插件<b>不得</b>绕过
 * 核心规则改动结算——本事件不可取消。</p>
 */
public class DuelSettlementEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    /** 结算类型。 */
    public enum Outcome {
        /** 资源按比例从败者转移给胜者（死亡 / 处决超时）。 */
        TRANSFER,
        /** 无转移，双方资源回滚到决斗开始快照（第三方中断）。 */
        ROLLBACK
    }

    private final DuelInfo duel;
    private final Outcome outcome;
    private final UUID winner;
    private final UUID loser;
    private final double ratio;
    /** 实际到手资源：玩家 → 资源类型 → 数量（ROLLBACK 时为空表）。 */
    private final Map<UUID, Map<Material, Integer>> received;

    public DuelSettlementEvent(DuelInfo duel, Outcome outcome, UUID winner, UUID loser,
                               double ratio, Map<UUID, Map<Material, Integer>> received) {
        this.duel = duel;
        this.outcome = outcome;
        this.winner = winner;
        this.loser = loser;
        this.ratio = ratio;
        this.received = received;
    }

    public DuelInfo duel() {
        return duel;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** 胜者；ROLLBACK 为 null。 */
    public UUID winner() {
        return winner;
    }

    /** 败者；ROLLBACK 为 null。 */
    public UUID loser() {
        return loser;
    }

    /** 转移比例（ROLLBACK 固定 0）。 */
    public double ratio() {
        return ratio;
    }

    /** 不可变映射：接收方玩家 → 各资源类型实际到手数量（溢出未到手部分不计）。 */
    public Map<UUID, Map<Material, Integer>> received() {
        return received;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
