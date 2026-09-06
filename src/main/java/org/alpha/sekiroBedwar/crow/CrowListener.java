package org.alpha.sekiroBedwar.crow;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
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
 * 雾璃鸦监听器（薄壳）：右键激活（拦截原版投掷）+ 投掷兜底 + 护身伤害拦截
 * （HIGHEST，晚于完美弹反 HIGH / 普通格挡 NORMAL——被弹开或格挡掉的伤害不算
 * "受到的伤害"，不消耗护身）+ 绑定物防丢弃 / 容器 + 清理。
 */
public final class CrowListener implements Listener {
    private final CrowManager manager;

    public CrowListener(CrowManager manager) {
        this.manager = manager;
    }

    /** 右键激活主路径：持绑定雾璃鸦右键（空击 / 对方块）即触发，拦截原版投掷。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        manager.handleUse(event.getPlayer(), event);
    }

    /** 兜底：交互取消未生效导致眼仍被掷出时，悬停玩家的投掷眼一律拦下。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player thrower)) {
            return;
        }
        manager.handleStrayEye(thrower, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        manager.handleDamage(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isCrowEye(event.getItemDrop().getItemStack())) {
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
        boolean involved = manager.isCrowEye(current) || manager.isCrowEye(cursor);
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
        if (manager.isCrowEye(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** 死亡：结束悬停并重新起补给 CD（复活后自动补回一只）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        java.util.UUID uuid = event.getEntity().getUniqueId();
        manager.clear(uuid);
        manager.scheduleRefill(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.clearAll(event.getPlayer().getUniqueId());
    }
}
