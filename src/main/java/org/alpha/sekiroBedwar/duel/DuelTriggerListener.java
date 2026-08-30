package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 决斗触发监听器。
 *
 * <p>唯一触发入口是 {@link EntityDamageByEntityEvent}（玩家主动命中另一玩家）：
 * <ul>
 *   <li>先经 {@link ValidAttackPredicate} 过滤（排除空挥/无效伤害）；</li>
 *   <li>记录命中，并对称评估 <code>tryTriggerDuel</code>；</li>
 *   <li>{@link PlayerQuitEvent} 仅用于清理状态，不参与触发。</li>
 * </ul>
 * 靠近（PlayerMoveEvent）、空挥（PlayerAnimationEvent）、搭方块（BlockPlaceEvent）、
 * 普通移动均不在监听范围，因此不会触发决斗。</p>
 *
 * <p>BedWars 生命周期事件（PlayerLeaveEvent / GameEndEvent）在
 * {@link DuelTriggerManager#enable()} 中通过 API 静态 <code>handle</code> 订阅。</p>
 */
public final class DuelTriggerListener implements Listener {
    private final DuelTriggerManager manager;
    private final ValidAttackPredicate validAttackPredicate;

    public DuelTriggerListener(DuelTriggerManager manager, ValidAttackPredicate validAttackPredicate) {
        this.manager = manager;
        this.validAttackPredicate = validAttackPredicate;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || attacker.equals(victim)) {
            return;
        }
        if (!validAttackPredicate.isValidAttack(event)) {
            return;
        }

        manager.recordValidAttack(attacker, victim);
        manager.tryTriggerDuel(attacker, victim);
        manager.tryTriggerDuel(victim, attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.purgePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        manager.hideVisuals(event.getPlayerA(), event.getPlayerB());
    }

    /** 解析本次伤害的攻击方玩家（近战直接命中或玩家射出的投射物）。 */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
