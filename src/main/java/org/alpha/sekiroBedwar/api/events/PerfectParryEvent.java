package org.alpha.sekiroBedwar.api.events;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>完美弹反</b>成功后广播（公共 API，只读通知；含盾牌弹反窗口内的弹反）。
 *
 * <p>判定与效果由核心结算完毕（攻击已被完整弹开：免伤害与击退；攻击方架势按
 * 惩罚公式扣除）。外部插件只消费结果，不依赖、也感知不到内部窗口计时器。</p>
 */
public class PerfectParryEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID parryer;
    private final UUID attacker;
    private final UUID duelId;
    private final Material attackerWeapon;
    private final double attackerStanceLoss;

    public PerfectParryEvent(UUID parryer, UUID attacker, UUID duelId,
                             Material attackerWeapon, double attackerStanceLoss) {
        this.parryer = parryer;
        this.attacker = attacker;
        this.duelId = duelId;
        this.attackerWeapon = attackerWeapon;
        this.attackerStanceLoss = attackerStanceLoss;
    }

    /** 弹反成功方（防守方）。 */
    public UUID parryer() {
        return parryer;
    }

    /** 攻击被弹开方。 */
    public UUID attacker() {
        return attacker;
    }

    /** 所属决斗（弹反只在 ACTIVE 决斗内生效，必非空）。 */
    public UUID duelId() {
        return duelId;
    }

    /** 攻击方当时主手武器（可空 = 空手）。 */
    public Material attackerWeapon() {
        return attackerWeapon;
    }

    /** 攻击方因此承受的架势损失（已应用）。 */
    public double attackerStanceLoss() {
        return attackerStanceLoss;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
