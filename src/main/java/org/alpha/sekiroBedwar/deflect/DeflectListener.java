package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 盾牌弹反返还监听器（薄壳）：主手持盾右键触发 + 免疫累计 + 反击返还 + 清理。
 */
public final class DeflectListener implements Listener {
    private final DeflectManager manager;

    public DeflectListener(DeflectManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.SHIELD) {
            return;
        }
        manager.tryStartDeflect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamageNegate(EntityDamageByEntityEvent event) {
        manager.handleNegate(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamageReflect(EntityDamageByEntityEvent event) {
        manager.handleReflect(event);
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
