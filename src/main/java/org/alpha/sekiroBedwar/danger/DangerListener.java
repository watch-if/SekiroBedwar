package org.alpha.sekiroBedwar.danger;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * 危攻击监听器（薄壳）：识破（HIGHEST 伤害）+ 下蹲计时 + 清理。
 */
public final class DangerListener implements Listener {
    private final DangerManager manager;

    public DangerListener(DangerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        manager.handleDamage(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            manager.recordSneakStart(event.getPlayer());
        } else {
            manager.clear(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        manager.clear(event.getDuel().getPlayerAUuid());
        manager.clear(event.getDuel().getPlayerBUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.clear(event.getPlayer().getUniqueId());
    }
}
