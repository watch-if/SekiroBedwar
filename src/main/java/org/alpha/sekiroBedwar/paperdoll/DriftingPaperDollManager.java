package org.alpha.sekiroBedwar.paperdoll;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 漂流纸人管理器：消耗品，右键血量减半换 5 纸人（可超上限），效果死亡复位。
 * 经忍具商店 GUI 购买。
 */
public final class DriftingPaperDollManager {

    private final SekiroBedwar plugin;
    private final PaperDollConfig config;
    private final PaperDollManager paperDollManager;
    private final SekiroShopManager shop;
    private final NamespacedKey ownerKey;
    private final DriftingPaperDollListener listener;

    public DriftingPaperDollManager(SekiroBedwar plugin, PaperDollConfig config,
                                    PaperDollManager paperDollManager, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.shop = shop;
        this.ownerKey = new NamespacedKey(plugin, "drifting_paper_doll");
        this.listener = new DriftingPaperDollListener(this);
    }

    public void enable() {
        if (!config.driftingEnabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        shop.register(new ShopItem("drifting-paper-doll", 41, this::renderItem, this::buy));
        plugin.getLogger().info("漂流纸人已启用：价格=" + config.driftingPriceAmount()
                + " " + config.driftingPriceCurrency() + " 上限=" + config.driftingMaxHold() + "（忍具商店 GUI 购买）");
    }

    public void disable() {
    }

    // ============ 物品识别 ============

    public boolean isDriftingPaperDoll(ItemStack item) {
        if (item == null || item.getType() != config.driftingMaterial() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isOwnedDriftingPaperDoll(ItemStack item, Player player) {
        if (!isDriftingPaperDoll(item)) {
            return false;
        }
        String owner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    private int countDrifting(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isOwnedDriftingPaperDoll(item, player)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean consumeDrifting(Player player, int n) {
        if (player == null || n <= 0) {
            return n <= 0;
        }
        if (countDrifting(player) < n) {
            return false;
        }
        int remaining = n;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!isOwnedDriftingPaperDoll(item, player)) {
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

    private ItemStack makeDriftingPaperDoll(Player player) {
        ItemStack item = new ItemStack(config.driftingMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§f" + config.driftingName());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    // ============ 右键使用 ============

    /** 右键使用漂流纸人：血量 &gt; 50% 上限时消耗 1 个，血量上限砍半并发放纸人。 */
    public void handleUse(Player player, ItemStack held) {
        if (!config.driftingEnabled() || player == null || held == null) {
            return;
        }
        if (!isOwnedDriftingPaperDoll(held, player)) {
            return;
        }
        double max = player.getMaxHealth();
        if (player.getHealth() <= max * config.driftingHpThreshold()) {
            return; // 血量不足阈值，不可用
        }
        if (!consumeDrifting(player, 1)) {
            return;
        }
        reduceMaxHealth(player);
        paperDollManager.givePaperDolls(player, config.driftingPaperDollsGranted());
    }

    private void reduceMaxHealth(Player player) {
        Attribute attr = maxHealthAttribute();
        if (attr == null) {
            return;
        }
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) {
            return;
        }
        double currentMax = inst.getBaseValue();
        double newMax = currentMax * config.driftingHpReduction();
        inst.setBaseValue(newMax);
        if (player.getHealth() > newMax) {
            player.setHealth(newMax);
        }
    }

    // ============ 死亡清理 ============

    /** 死亡掉落：从掉落列表移除漂流纸人（不掉落地面）。 */
    public void handlePlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isDriftingPaperDoll);
    }

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 点击路由）：持有上限 → 扣费 → 发绑定漂流纸人。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (countDrifting(player) >= config.driftingMaxHold()) {
            player.sendMessage("§c漂流纸人已达上限（" + config.driftingMaxHold() + "）！");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.driftingPriceCurrency()),
                config.driftingPriceAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makeDriftingPaperDoll(player));
    }

    /** 动态渲染：用法 + 价格 + 持有数。 */
    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7血量>50%时右键：上限减半，得 " + config.driftingPaperDollsGranted() + " 纸人");
        lore.add(ShopCurrency.priceLore(config.driftingPriceCurrency(), config.driftingPriceAmount()));
        if (viewer != null) {
            int held = countDrifting(viewer);
            lore.add("§7持有: " + held + "/" + config.driftingMaxHold());
            if (held >= config.driftingMaxHold()) {
                lore.add("§c已达上限");
            }
        }
        return SekiroShopManager.icon(config.driftingMaterial(), "§f" + config.driftingName(), lore);
    }

    @SuppressWarnings("removal")
    private static Attribute maxHealthAttribute() {
        for (String name : new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
            try {
                return Attribute.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 该名字在当前 API 中不存在，尝试下一个
            }
        }
        return null;
    }
}
