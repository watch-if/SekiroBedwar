package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 秘传<b>派生 / 分支选择</b>时广播（公共 API，只读通知；为未来带分支选择的武技预留）。
 *
 * <p>现有四式均为线性连段、暂不广播本事件；未来武技在"同一起手可派生多段走向"时，
 * 于玩家实际踏入某分支的那一刻广播（{@code branchId} 由该武技定义、保持稳定），
 * 统计插件的"派生选择率"即消费本事件——<b>无需改动 API 架构</b>。</p>
 */
public class SecretTechniqueBranchEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;
    private final String branchId;

    public SecretTechniqueBranchEvent(UUID player, TechniqueId technique, UUID duelId, String branchId) {
        this.player = player;
        this.technique = technique;
        this.duelId = duelId;
        this.branchId = branchId;
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

    /** 分支稳定标识（武技内自定义，如 {@code "finisher"} / {@code "keep-combo"}）。 */
    public String branchId() {
        return branchId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
