package org.alpha.sekiroBedwar.api.events;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>无格挡命中</b>（未被完美弹反、未被盾牌格挡的决斗内攻击）结算后广播
 * （公共 API，只读通知）。
 *
 * <p>核心架势换算已应用（{@code stanceLoss} = 实机血量伤害 × 命中倍率）；
 * 生命伤害按原版。与 {@link BlockEvent} / {@link PerfectParryEvent} 互斥：
 * 一次命中只会是三者之一（或破盾分支 {@link ShieldBreakEvent}）。</p>
 */
public class HitLandedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID attacker;
    private final UUID victim;
    private final UUID duelId;
    private final Material attackerWeapon;
    private final double stanceLoss;
    private final double healthDamage;
    private final boolean melee;

    public HitLandedEvent(UUID attacker, UUID victim, UUID duelId, Material attackerWeapon,
                          double stanceLoss, double healthDamage, boolean melee) {
        this.attacker = attacker;
        this.victim = victim;
        this.duelId = duelId;
        this.attackerWeapon = attackerWeapon;
        this.stanceLoss = stanceLoss;
        this.healthDamage = healthDamage;
        this.melee = melee;
    }

    public UUID attacker() {
        return attacker;
    }

    public UUID victim() {
        return victim;
    }

    public UUID duelId() {
        return duelId;
    }

    /** 攻击方主手武器（可空；投射物时为射手主手物品，可能为弓 / 弩）。 */
    public Material attackerWeapon() {
        return attackerWeapon;
    }

    /** 受击方因此承受的架势损失（已应用）。 */
    public double stanceLoss() {
        return stanceLoss;
    }

    /** 实际扣血量伤害（护甲减伤后）。 */
    public double healthDamage() {
        return healthDamage;
    }

    /** 是否近战直接命中（false = 投射物命中）。 */
    public boolean melee() {
        return melee;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
