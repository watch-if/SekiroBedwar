package org.alpha.sekiroBedwar.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>破盾</b>（防守方在格挡状态下被高威胁攻击击破防御）发生时广播
 * （公共 API，只读通知）。
 *
 * <p>完美弹反成功的命中绝不会被判定为破盾。破盾后防守方进入短暂「无法格挡」窗口
 * （架势扣减走更高倍率）。注意：危攻击即使被"格挡"，其生命伤害仍按规则结算。</p>
 */
public class ShieldBreakEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    /** 破盾来源。 */
    public enum Cause {
        /** 斧类武器命中普通格挡。 */
        AXE,
        /** 危攻击被格挡（矛 + 突进附魔 + 疾跑）。 */
        DANGER
    }

    private final UUID defender;
    private final UUID attacker;
    private final UUID duelId;
    private final Cause cause;
    private final double defenderStanceLoss;

    public ShieldBreakEvent(UUID defender, UUID attacker, UUID duelId,
                            Cause cause, double defenderStanceLoss) {
        this.defender = defender;
        this.attacker = attacker;
        this.duelId = duelId;
        this.cause = cause;
        this.defenderStanceLoss = defenderStanceLoss;
    }

    public UUID defender() {
        return defender;
    }

    public UUID attacker() {
        return attacker;
    }

    public UUID duelId() {
        return duelId;
    }

    public Cause cause() {
        return cause;
    }

    /** 防守方因此承受的架势损失（已应用）。 */
    public double defenderStanceLoss() {
        return defenderStanceLoss;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
