package org.alpha.sekiroBedwar.sugar;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 夜叉戮糖监听器（薄壳）：右键使用（拦截原版唱片进唱机）+ 绑定物防丢弃 / 容器 + 死亡 / 退出清理。
 */
public final class SugarListener implements Listener {
    private final SugarManager manager;

    public SugarListener(SugarManager manager) {
        this.manager = manager;
    }

    /** 右键使用主路径：持绑定糖右键（空击 / 对方块）即触发，拦截唱片进唱机。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        manager.handleUse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isSugar(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean involved = manager.isSugar(current) || manager.isSugar(cursor);
        if (!involved) {
            return;
        }
        Inventory bottom = event.getView().getBottomInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && !clicked.equals(bottom)) {
            event.setCancelled(true);
            return;
        }
        InventoryAction action = event.getAction();
        if (action == InventoryAction.DROP_ALL_CURSOR || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (manager.isSugar(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** 死亡：清效果状态 + 从掉落移除糖。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        manager.handlePlayerDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.clearAll(event.getPlayer().getUniqueId());
    }
}
