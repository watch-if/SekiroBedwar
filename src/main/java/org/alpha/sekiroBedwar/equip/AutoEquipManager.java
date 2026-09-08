package org.alpha.sekiroBedwar.equip;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.screamingsandals.bedwars.api.events.PurchaseFailedEvent;
import org.screamingsandals.bedwars.api.events.StorePostPurchaseEvent;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 购买护甲自动装备（独立小模块）：在 BedWars 商店购买任意 盔/胸甲/护腿/靴 后，
 * 从背包取出该件直接穿到对应装备栏；原穿着物退回背包（装不下落脚下）。
 *
 * <p><b>事件配对</b>：{@code StorePostPurchaseEvent} 接口不暴露所购物品，所以用
 * {@code StorePrePurchaseEvent}（{@code getNewItem()}）按玩家暂存待装备材质，
 * Post（BedWars 扣费发放完成后触发）取出装备；{@code PurchaseFailedEvent} 清暂存防误装。
 * 本模块<b>不取消</b>任何商店事件——正常走 BedWars 流程，护甲先入背包再被穿走。</p>
 *
 * <p>忍具商店 GUI 的商品不走 BedWars 商店事件流，与本模块无交集；
 * 团队升级类购买（{@code getNewItem()==null}）自动跳过。附魔/染色等 NBT 经
 * {@code clone()} 原样保留。</p>
 */
public final class AutoEquipManager {

    private final SekiroBedwar plugin;
    private final AutoEquipConfig config;
    /** 玩家 UUID → 本次购买待自动装备的护甲材质（Pre 暂存，Post 消费，Failed 清除）。 */
    private final Map<UUID, Material> pending = new HashMap<>();

    public AutoEquipManager(SekiroBedwar plugin, AutoEquipConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        StorePrePurchaseEvent.handle(plugin, this::handlePre);
        StorePostPurchaseEvent.handle(plugin, this::handlePost);
        PurchaseFailedEvent.handle(plugin, ev -> pending.remove(ev.getPlayer().getUuid()));
        plugin.getLogger().info("自动装备已启用：BedWars 商店购买护甲后直接穿到身上");
    }

    public void disable() {
        pending.clear();
    }

    private void handlePre(StorePrePurchaseEvent ev) {
        ItemStackHolder holder = ev.getNewItem();
        if (holder == null) {
            return; // 升级类购买无物品
        }
        ItemStack item;
        try {
            item = holder.as(ItemStack.class);
        } catch (RuntimeException ex) {
            return;
        }
        if (item == null || equipmentSlotOf(item.getType()) == null) {
            return;
        }
        pending.put(ev.getPlayer().getUuid(), item.getType());
    }

    private void handlePost(StorePostPurchaseEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Material armorType = pending.remove(uuid);
        if (armorType == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            equipFromInventory(player, armorType);
        }
    }

    /** 从背包取出该护甲一件（clone 保 NBT）穿到对应栏位；原穿着物回背包（满则脚下）。 */
    private void equipFromInventory(Player player, Material armorType) {
        PlayerInventory inv = player.getInventory();
        ItemStack piece = null;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() != armorType) {
                continue;
            }
            if (stack.getAmount() > 1) {
                piece = stack.clone();
                piece.setAmount(1);
                stack.setAmount(stack.getAmount() - 1);
                inv.setItem(i, stack);
            } else {
                piece = stack;
                inv.setItem(i, null);
            }
            break;
        }
        if (piece == null) {
            return; // 背包里已找不到（极端情况）
        }
        equipPiece(player, piece);
    }

    /**
     * 把一件护甲直接穿到对应装备栏（盔/胸/腿/靴）；原穿着物退回背包、装不下落脚下。
     * 非护甲物品返回 false 不做任何改动。供本模块与护甲商店 GUI 共用。
     */
    public static boolean equipPiece(Player player, ItemStack piece) {
        if (player == null || piece == null) {
            return false;
        }
        EquipmentSlot slot = equipmentSlotOf(piece.getType());
        if (slot == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack previous = inv.getItem(slot);
        inv.setItem(slot, piece);
        if (previous != null) {
            Map<Integer, ItemStack> leftover = inv.addItem(previous);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        return true;
    }

    /** 材质 → 装备栏位（盔/胸/腿/靴），非护甲返回 null。 */
    private static EquipmentSlot equipmentSlotOf(Material type) {
        String name = type.name();
        if (name.endsWith("_HELMET")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }
}
