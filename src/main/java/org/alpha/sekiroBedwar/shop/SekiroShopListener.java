package org.alpha.sekiroBedwar.shop;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
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
 *   <li><b>GUI 顶栏（展示区）</b>：一切点击取消，条目点击路由到 {@link ShopItem#buy()}
 *       （BWPlayer 点击瞬间经 API 实查）后刷新窗口——展示物品永不可取走；</li>
 *   <li><b>GUI 底栏（玩家自己的物品栏）</b>：<b>放行常规整理</b>（槽间移动 / 快捷栏交换 /
 *       手持放置等），仅拦截会跨越顶底边界或波及展示区的动作——shift 移动
 *       （{@code MOVE_TO_OTHER_INVENTORY}，物品会被塞进展示区）、双击收集
 *       （{@code COLLECT_TO_CURSOR}，会把展示物品收上光标），以及任何触及顶栏槽位的拖拽。
 *       早前"底栏一律取消"会让玩家物品栏不能动且客户端预测回滚表现为吞物品。</li>
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
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && !clicked.equals(top)) {
            // 玩家自己的物品栏：常规整理放行，只拦会跨进展示区 / 波及展示区的动作
            InventoryAction action = event.getAction();
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true); // 顶栏（展示区 / 玻璃板 / 空槽 / 窗外）点击一律取消
        if (!(event.getWhoClicked() instanceof Player player)) {
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
        if (!(SekiroShopManager.holderOf(top) instanceof SekiroShopHolder)) {
            return;
        }
        // 纯玩家物品栏内的拖拽放行；任何触及顶栏槽位（rawSlot < top size）的拖拽取消
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
