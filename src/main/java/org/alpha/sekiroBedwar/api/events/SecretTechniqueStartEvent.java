package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次秘传武技<b>启动</b>时广播（公共 API，只读通知）。
 *
 * <p>启动点语义（按武技）：节奏连式（飞渡浮舟 / 一心七连）= 第一击近战命中落地；
 * 起手式（苇名十字斩 / 龙闪）= 空手换刀达成武装。同一玩家未 Complete / Fail / Cancel
 * 前不会重复 Start（单实例连段）。</p>
 */
public class SecretTechniqueStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;

    public SecretTechniqueStartEvent(UUID player, TechniqueId technique, UUID duelId) {
        this.player = player;
        this.technique = technique;
        this.duelId = duelId;
    }

    public UUID player() {
        return player;
    }

    /** 武技稳定 id（新增秘传 = 新枚举值；外部按 id 消费并对未知值保持兼容）。 */
    public TechniqueId technique() {
        return technique;
    }

    /** 所属决斗（秘传命中钩子仅 ACTIVE 决斗内生效；龙闪武装可在决斗外：可空）。 */
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
