package org.alpha.sekiroBedwar.duel;

import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 有效主动攻击判定接口。
 *
 * <p>判定一次 {@link EntityDamageByEntityEvent} 是否算“有效主动攻击”（玩家主动命中且实际造成伤害）。
 * 可通过 {@link DuelTriggerManager#setValidAttackPredicate(ValidAttackPredicate)} 替换默认实现，
 * 默认实现 {@link DefaultValidAttackPredicate} 按 {@link DuelConfig}（melee-only / min-damage）判定。</p>
 */
@FunctionalInterface
public interface ValidAttackPredicate {

    /**
     * 判定一次伤害事件是否为有效主动攻击。
     *
     * @param event 实体伤害事件（damager 已被确认为玩家或其投射物）
     * @return true 表示有效主动攻击
     */
    boolean isValidAttack(EntityDamageByEntityEvent event);
}
