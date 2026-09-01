package org.alpha.sekiroBedwar.paperdoll;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 漂流纸人监听器（薄壳）：右键使用 + 死亡清理。
 */
public final class DriftingPaperDollListener implements Listener {
    private final DriftingPaperDollManager manager;

    public DriftingPaperDollListener(DriftingPaperDollManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        manager.handleUse(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        manager.handlePlayerDeath(event);
    }
}
