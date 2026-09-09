package org.alpha.sekiroBedwar.api.events;

import org.alpha.sekiroBedwar.api.TechniqueId;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 秘传连段中<b>一段成功接上</b>时广播（公共 API，只读通知）。
 *
 * <p>{@code hitIndex} 语义（自第二起算，Start 已占第一段）：
 * 飞渡浮舟 / 一心七连 = 第 hitIndex+1 击落地的连击序号（2..6；完成段用
 * {@link SecretTechniqueCompleteEvent}）；苇名十字斩 = 第一击(1) / 第二击(2)；
 * 龙闪 = 波次命中（hitIndex=波号 1..2，{@code target} 为被波命中的玩家）。
 * 秘传的成功 / 脱拍音效由核心统一播放（外部插件不得改动，只消费事件）。</p>
 */
public class SecretTechniqueHitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TechniqueId technique;
    private final UUID duelId;
    private final int hitIndex;
    private final boolean parried;
    private final UUID target;

    public SecretTechniqueHitEvent(UUID player, TechniqueId technique, UUID duelId,
                                   int hitIndex, boolean parried, UUID target) {
        this.player = player;
        this.technique = technique;
        this.duelId = duelId;
        this.hitIndex = hitIndex;
        this.parried = parried;
        this.target = target;
    }

    public UUID player() {
        return player;
    }

    public TechniqueId technique() {
        return technique;
    }

    /** 所属决斗（可空：龙闪波命中在决斗外亦可发生）。 */
    public UUID duelId() {
        return duelId;
    }

    /** 本击在连段中的序号（各武技语义见类注释）。 */
    public int hitIndex() {
        return hitIndex;
    }

    /** 本击是否被对方完美弹反（被弹反仍算打出的一击，连段继续）。 */
    public boolean parried() {
        return parried;
    }

    /** 受击 / 作用目标（近战连段 = 被打中的对手；龙闪 = 被波命中玩家；可空）。 */
    public UUID target() {
        return target;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
