package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 秘传连段<b>全段完成</b>时广播（公共 API，只读通知）。
 *
 * <p>奖励（纸人 / 架势 / 生命等）由核心在同一时刻应用后才广播本事件——
 * 外部插件据事件计数即可，与奖励数值解耦。</p>
 */
public class SecretTechniqueCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;
    private final int totalHits;
    private final int validHits;

    public SecretTechniqueCompleteEvent(UUID player, TechniqueId technique, UUID duelId,
                                        int totalHits, int validHits) {
        this.player = player;
        this.technique = technique;
        this.duelId = duelId;
        this.totalHits = totalHits;
        this.validHits = validHits;
    }

    public UUID player() {
        return player;
    }

    public TechniqueId technique() {
        return technique;
    }

    /** 所属决斗（可空：龙闪完成可在决斗外）。 */
    public UUID duelId() {
        return duelId;
    }

    /** 本技总击数（飞渡浮舟 / 一心七连 = 7；苇名 = 2；龙闪 = 2 波）。 */
    public int totalHits() {
        return totalHits;
    }

    /** 其中有效命中（未被完美弹反）的击数（供成功率类统计）。 */
    public int validHits() {
        return validHits;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
