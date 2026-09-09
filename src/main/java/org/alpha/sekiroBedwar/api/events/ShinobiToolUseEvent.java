package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.ToolId;
import org.alpha.sekiroBedwar.api.ToolUseResult;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次忍具 / 道具<b>使用</b>（成功尝试或资源不足被拒）时广播（公共 API，只读通知）。
 *
 * <p>覆盖：纸人（投掷抵扣 / 传送消耗）、漂流纸人、雾璃鸦、风弹、巴之雷（落雷 / 雷反）、
 * 盾牌弹反、锈丸、炎上、僵尸头颅——完整 id 见 {@link ToolId}，新增忍具只扩枚举。
 * 购买行为不广播本事件（属商店域）。失败也广播（{@code result} = 拒绝原因），
 * 统计插件可算"失败率"。</p>
 */
public class ShinobiToolUseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final ToolId tool;
    private final UUID target;
    private final UUID duelId;
    private final ToolUseResult result;

    public ShinobiToolUseEvent(UUID player, ToolId tool, UUID target, UUID duelId,
                               ToolUseResult result) {
        this.player = player;
        this.tool = tool;
        this.target = target;
        this.duelId = duelId;
        this.result = result;
    }

    public UUID player() {
        return player;
    }

    /** 忍具稳定 id（新增忍具 = 新枚举值，消费方按 id 匹配并对未知值保持兼容）。 */
    public ToolId tool() {
        return tool;
    }

    /** 作用目标（风弹触碰者 / 落雷受击者 / 传送目标等；被动开启类可空）。 */
    public UUID target() {
        return target;
    }

    /** 所属决斗（可空：忍具多在决斗外也可使用）。 */
    public UUID duelId() {
        return duelId;
    }

    public ToolUseResult result() {
        return result;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
