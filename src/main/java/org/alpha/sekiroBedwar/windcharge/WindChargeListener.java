package org.alpha.sekiroBedwar.windcharge;

import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 风弹监听器（薄壳）：
 * <ul>
 *   <li><b>释放触发</b>（HIGH + ignoreCancelled，晚于纸人消耗 / 退还的 NORMAL 阶段）：
 *       风弹投掷实际发生 → 生成爆风墙；</li>
 *   <li><b>禁攻取消</b>（LOWEST，早于弹反 HIGH / 普通格挡 NORMAL）：禁攻窗口内玩家作为
 *       伤害来源的一切对实体伤害取消——被取消的攻击不进入架势换算；</li>
 *   <li><b>绑定物三拦截</b>（丢弃 / 容器点击 / 容器流转，与纸人 / 雾璃鸦同款）；</li>
 *   <li><b>清理</b>：退出清封印 / 不可叠加状态（离局经 PlayerLeaveEvent.handle）。</li>
 * </ul>
 */
public final class WindChargeListener implements Listener {
    private final WindChargeManager manager;

    public WindChargeListener(WindChargeManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof WindCharge charge)) {
            return;
        }
        if (!(charge.getShooter() instanceof Player caster)) {
            return;
        }
        manager.handleLaunch(caster);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        manager.handleAttackAttempt(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isWindCharge(event.getItemDrop().getItemStack())) {
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
        boolean involved = manager.isWindCharge(current) || manager.isWindCharge(cursor);
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
        if (manager.isWindCharge(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.clearPlayer(event.getPlayer().getUniqueId());
    }
}
