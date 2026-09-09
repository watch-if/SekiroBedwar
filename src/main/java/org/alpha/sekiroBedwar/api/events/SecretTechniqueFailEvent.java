package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueFailReason;
import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 秘传连段<b>失败</b>（节奏脱拍 / 终结条件不满足 / 衔接超时 / 资源不足）时广播
 * （公共 API，只读通知）。
 *
 * <p>与 {@link SecretTechniqueCancelEvent}（生命周期强制中止）区分。
 * {@code reachedHits} = 失败时已完成的击数（衡量"差几段"）。脱拍后在节奏类武技中
 * 本击即作为新一式第一段（随后会有新 Start），统计插件可据此区分"重试"。</p>
 */
public class SecretTechniqueFailEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;
    private final TechniqueFailReason reason;
    private final int reachedHits;

    public SecretTechniqueFailEvent(UUID player, TechniqueId technique, UUID duelId,
                                    TechniqueFailReason reason, int reachedHits) {
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

    public TechniqueFailReason reason() {
        return reason;
    }

    /** 失败时已完成击数（0 = 武装 / 起手即失败）。 */
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
