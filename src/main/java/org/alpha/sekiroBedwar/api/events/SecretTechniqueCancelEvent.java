package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueCancelReason;
import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 秘传<b>已有进度被外部生命周期强制中止</b>时广播（公共 API，只读通知）。
 *
 * <p>死亡 / 决斗结束 / 离局 / 插件禁用清除了玩家的进行中连段（或武装态）时触发；
 * 节奏性失败走 {@link SecretTechniqueFailEvent}。"主动收手率"一类统计 = Cancel
 * 计数 / Start 计数。</p>
 */
public class SecretTechniqueCancelEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;
    private final TechniqueCancelReason reason;
    private final int reachedHits;

    public SecretTechniqueCancelEvent(UUID player, TechniqueId technique, UUID duelId,
                                      TechniqueCancelReason reason, int reachedHits) {
        this.player = player;
        this.technique = technique;
        this.duelId = duelId;
        this.reason = reason;
        this.reachedHits = reachedHits;
    }

    public UUID player() {
        return player;
    }

    public TechniqueId technique() {
        return technique;
    }

    /** 所属决斗（可空）。 */
    public UUID duelId() {
        return duelId;
    }

    public TechniqueCancelReason reason() {
        return reason;
    }

    /** 中止时已完成击数（0 = 仅武装 / 第一击前）。 */
    public int reachedHits() {
        return reachedHits;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
