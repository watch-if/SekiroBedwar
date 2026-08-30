package org.alpha.sekiroBedwar.duel;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 默认有效主动攻击判定：按 {@link DuelConfig} 配置判定。
 *
 * <ul>
 *   <li>造成伤害必须 &ge; <code>min-damage</code>（final damage）；</li>
 *   <li><code>melee-only=true</code>：仅近战直接命中（ENTITY_ATTACK / ENTITY_SWEEP_ATTACK）；</li>
 *   <li><code>melee-only=false</code>：近战直接命中或玩家射出的投射物命中均可。</li>
 * </ul>
 *
 * <p>空挥（PlayerAnimationEvent）、靠近（PlayerMoveEvent）、搭方块（BlockPlaceEvent）、
 * 普通移动均不属于本判定范围。</p>
 */
public final class DefaultValidAttackPredicate implements ValidAttackPredicate {
    private final DuelConfig config;

    public DefaultValidAttackPredicate(DuelConfig config) {
        this.config = config;
    }

    @Override
    public boolean isValidAttack(EntityDamageByEntityEvent event) {
        // 实际造成伤害必须严格大于 min-damage（默认 0 即“确实造成了伤害”）
        if (event.getFinalDamage() <= config.minDamage()) {
            return false;
        }
        if (config.meleeOnly()) {
            return event.getDamager() instanceof Player
                    && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
        }
        if (event.getDamager() instanceof Player) {
            return true;
        }
        return event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }
}
