package org.alpha.sekiroBedwar.armory;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 护甲商店劫持监听器（薄壳）：LOWEST 优先级拦截 BedWars 商店<b>主页</b>中显示名匹配
 * {@code armor-shop.category-name} 的分类图标点击（slib 点击处理在 NORMAL 且首行判
 * isCancelled，LOWEST 取消即完整劫持），改为打开自管套装页。
 * 先做便宜的名称比对，再走主页判定（反射链），非商店主页零开销。
 */
public final class ArmorShopListener implements Listener {
    private final ArmorShopManager manager;

    ArmorShopListener(ArmorShopManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onShopClick(InventoryClickEvent event) {
        if (!manager.active()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        int raw = event.getRawSlot();
        if (clicked == null || !clicked.equals(top) || raw < 0 || raw >= top.getSize()) {
            return;
        }
        ItemStack item = top.getItem(raw);
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        String name = ChatColor.stripColor(meta.getDisplayName());
        if (name == null || !name.contains(manager.config().categoryName())) {
            return; // 先名字后反射：绝大多数点击在此免费短路
        }
        if (!manager.shopManager().isShopMainView(top)) {
            return; // 只劫持主菜单的分类图标，子页里同名物品不碰
        }
        event.setCancelled(true);
        manager.openArmory(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer().getUniqueId());
    }
}
