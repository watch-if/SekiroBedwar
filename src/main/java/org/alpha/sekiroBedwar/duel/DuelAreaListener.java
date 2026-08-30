package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 决斗区域限制监听器：
 * <ul>
 *   <li>伤害事件 → 记录击退（区分“主动逃离”与“被攻击击退”）；</li>
 *   <li>主动传送（末影珍珠 / 紫颂果等 {@code area.block-teleport-causes}）落点越界 → 取消；</li>
 *   <li>搭方块越界（{@code area.block-place-escaping}）→ 取消（防止搭路逃离）；</li>
 *   <li>玩家下线 / 决斗结束 → 清理击退记录（防泄漏）。</li>
 * </ul>
 */
public final class DuelAreaListener implements Listener {
    private final DuelAreaGuard guard;
    private final DuelConfig config;

    public DuelAreaListener(DuelAreaGuard guard, DuelConfig config) {
        this.guard = guard;
        this.config = config;
    }

    /** 受害者受到伤害：记录击退时间戳与方向（MONITOR，取消的伤害不产生击退）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            guard.recordKnockback(victim, event.getDamager());
        }
    }

    /** 拦截主动传送越界（末影珍珠 / 紫颂果等）：落点越过边界即取消。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (config.blockedTeleportCauses().contains(event.getCause())
                && guard.isBeyondBoundary(player, event.getTo())) {
            event.setCancelled(true);
        }
    }

    /** 拦截「把方块搭到边界外」的搭路逃离（{@code area.block-place-escaping}，处决窗口豁免）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (config.blockPlaceEscaping()
                && guard.isBeyondBoundary(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** 玩家下线：清理其击退记录。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        guard.purgePlayer(event.getPlayer().getUniqueId());
    }

    /** 决斗结束：清理双方击退记录。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        guard.onDuelEnded(event);
    }
}
