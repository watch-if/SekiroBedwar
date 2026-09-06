package org.alpha.sekiroBedwar.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;

/**
 * 忍具商店 GUI 的 {@link InventoryHolder} 标记：槽位 → {@link ShopItem} 映射，
 * 监听器按 holder 实例识别自家 GUI（不依赖标题字符串）并路由点击。
 */
public final class SekiroShopHolder implements InventoryHolder {
    private final Map<Integer, ShopItem> slots;
    private Inventory inventory;

    SekiroShopHolder(Map<Integer, ShopItem> slots) {
        this.slots = slots;
    }

    /** 建窗后回填（{@link #getInventory()} 契约要求非空）。 */
    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    Map<Integer, ShopItem> slots() {
        return slots;
    }
}
