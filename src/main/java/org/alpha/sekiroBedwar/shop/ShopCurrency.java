package org.alpha.sekiroBedwar.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 忍具商店货币工具（统一实现，替代各 manager 重复的私有扣费逻辑）。
 *
 * <p><b>按 Material 遍历扣</b>：BedWars 资源物品带自定义显示名（"Iron" 等），
 * {@code removeItem(new ItemStack(type, n))} 走 {@code isSimilar} 会因显示名不相等而失败
 * （已知坑）——必须忽略显示名按 {@link Material} 逐格扣。</p>
 */
public final class ShopCurrency {
    private ShopCurrency() {
    }

    /** BedWars 资源名（bronze/iron/gold/diamond/emerald）→ 物品材质。 */
    public static Material of(String currency) {
        if (currency == null) {
            return Material.IRON_INGOT;
        }
        switch (currency.toLowerCase()) {
            case "gold":
                return Material.GOLD_INGOT;
            case "diamond":
                return Material.DIAMOND;
            case "emerald":
                return Material.EMERALD;
            case "bronze":
                return Material.BRICK;
            case "iron":
            default:
                return Material.IRON_INGOT;
        }
    }

    /** 资源名的中文显示（GUI 价格行用）。 */
    public static String label(String currency) {
        if (currency == null) {
            return "铁";
        }
        switch (currency.toLowerCase()) {
            case "gold":
                return "金";
            case "diamond":
                return "钻石";
            case "emerald":
                return "绿宝石";
            case "bronze":
                return "青铜";
            case "iron":
            default:
                return "铁";
        }
    }

    /** 背包中该货币的总数（按 Material 计，忽略显示名）。 */
    public static int count(Player player, Material currency) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == currency) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** 扣 n 个货币（不足返回 false 且分文不取）。 */
    public static boolean deduct(Player player, Material currency, int amount) {
        if (player == null || currency == null || amount <= 0) {
            return amount <= 0;
        }
        if (count(player, currency) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != currency) {
                continue;
            }
            if (item.getAmount() > remaining) {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            } else {
                remaining -= item.getAmount();
                player.getInventory().setItem(i, null);
            }
        }
        return remaining <= 0;
    }

    /** 价格行 lore：「价格: 30 铁」。 */
    public static String priceLore(String currency, int amount) {
        return "§6价格: §e" + amount + " " + label(currency);
    }
}
