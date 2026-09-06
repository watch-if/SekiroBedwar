package org.alpha.sekiroBedwar.shop;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.screamingsandals.bedwars.api.player.BWPlayer;

/**
 * 忍具商店监听器（薄壳）：
 * <ul>
 *   <li><b>接管入口点击</b>（LOWEST）：slib 商店主页右下角槽位的自家标记物品被点击 →
 *       取消并打开忍具商店 GUI（slib 的点击处理在 NORMAL 且首行判 isCancelled，
 *       LOWEST 取消即可完整劫持翻页）；</li>
 *   <li><b>GUI 内</b>：顶 / 底栏一切点击与拖拽取消（展示物品永不可取走、玩家物品也拖不进来），
 *       顶栏条目点击路由到 {@link ShopItem#buy()}（BWPlayer 点击瞬间经 API 实查）后刷新窗口。</li>
 * </ul>
 * 与纸人 / 僵尸头颅的容器拦截监听无冲突（那些只认带 owner-PDC 的物品，GUI 图标无 PDC）。
 */
public final class SekiroShopListener implements Listener {
    private final SekiroShopManager manager;

    public SekiroShopListener(SekiroShopManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTakeoverClick(InventoryClickEvent event) {
        if (!manager.takeoverActive()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(top) || top.getSize() <= 0) {
            return;
        }
        int corner = top.getSize() - 1;
        if (event.getRawSlot() != corner) {
            return;
        }
        if (!manager.isMarked(top.getItem(corner))) {
            return; // 该位不是自家入口（分类子页的原生翻页等）——放行
        }
        event.setCancelled(true);
        manager.open(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(SekiroShopManager.holderOf(top) instanceof SekiroShopHolder holder)) {
            return;
        }
        event.setCancelled(true); // 一切点击取消（含底栏 shift 移动）
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(top)) {
            return;
        }
        ShopItem item = holder.slots().get(event.getSlot());
        if (item == null) {
            return; // 玻璃板 / 空槽
        }
        BWPlayer bw = manager.bwOf(player);
        if (bw == null && !SekiroShopManager.BACK_ID.equals(item.id())) {
            player.closeInventory(); // API 查不到玩家（未在 BedWars 中 / 插件异常）——不开买；返回按钮例外
            return;
        }
        item.buy().accept(new BuyContext(player, bw));
        manager.refresh(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (SekiroShopManager.holderOf(top) instanceof SekiroShopHolder) {
            event.setCancelled(true);
        }
    }
}
