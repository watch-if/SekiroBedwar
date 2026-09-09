package org.alpha.sekiroBedwar.api.events;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 一次<b>普通格挡</b>（盾牌格挡但未落入完美弹反窗口 / 或危攻击被弹反规则豁免之外的
 * 格挡情形）成功完成后广播（公共 API，只读通知）。
 *
 * <p>核心规则：普通格挡不完全免架势——防守方按公式扣架势（{@code defenderStanceLoss}
 * 已应用），生命伤害走原版盾牌减伤。被完美弹反的命中不会广播本事件。</p>
 */
public class BlockEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID defender;
    private final UUID attacker;
    private final UUID duelId;
    private final Material attackerWeapon;
    private final double defenderStanceLoss;
    private final boolean projectile;

    public BlockEvent(UUID defender, UUID attacker, UUID duelId, Material attackerWeapon,
                      double defenderStanceLoss, boolean projectile) {
        this.defender = defender;
        this.attacker = attacker;
        this.duelId = duelId;
        this.attackerWeapon = attackerWeapon;
        this.defenderStanceLoss = defenderStanceLoss;
        this.projectile = projectile;
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

    /** 攻击方主手武器（可空）。 */
    public Material attackerWeapon() {
        return attackerWeapon;
    }

    /** 防守方因此承受的架势损失（已应用）。 */
    public double defenderStanceLoss() {
        return defenderStanceLoss;
    }

    /** 攻击是否为投射物（弓箭等；近战为 false）。 */
    public boolean projectile() {
        return projectile;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
