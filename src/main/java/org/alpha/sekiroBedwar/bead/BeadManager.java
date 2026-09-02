package org.alpha.sekiroBedwar.bead;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.events.PlayerRespawnedEvent;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.player.BWPlayer;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 佛珠管理器：商店购买（单局上限 4 次、每次 +5 最大血量、价格递增），最大血量为全局增益、死亡后由重生恢复。
 */
public final class BeadManager {
    private static final double BASE_MAX_HEALTH = 20.0;
    private static final String MARKER_START = "# === SekiroBedwar bead START ===";
    private static final String MARKER_END = "# === SekiroBedwar bead END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final BeadConfig config;
    private final Map<UUID, Integer> count = new HashMap<>();

    public BeadManager(SekiroBedwar plugin, BeadConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
        PlayerRespawnedEvent.handle(plugin, this::handleRespawn);
        PlayerLeaveEvent.handle(plugin, ev -> count.remove(ev.getPlayer().getUuid()));
        injectShop();
        plugin.getLogger().info("佛珠已启用：上限=" + config.maxPurchases()
                + " 每颗+=" + config.hpPerBead() + " 血量");
    }

    public void disable() {
        count.clear();
    }

    private void handlePrePurchase(StorePrePurchaseEvent ev) {
        BWPlayer bw = ev.getPlayer();
        Player player = Bukkit.getPlayer(bw.getUuid());
        if (player == null || !bw.isInGame()) {
            return;
        }
        ItemStackHolder holder = ev.getNewItem();
        if (holder == null) {
            return;
        }
        ItemStack bought;
        try {
            bought = holder.as(ItemStack.class);
        } catch (RuntimeException ex) {
            return;
        }
        if (bought == null || bought.getType() != config.material()) {
            return;
        }
        String name = bought.hasItemMeta() && bought.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(bought.getItemMeta().getDisplayName()) : "";
        if (!name.contains(config.name())) {
            return;
        }
        ev.setCancelled(true);
        int current = count.getOrDefault(player.getUniqueId(), 0);
        if (current >= config.maxPurchases()) {
            player.sendMessage("§c佛珠已达上限（" + config.maxPurchases() + " 次）！");
            return;
        }
        int price = config.basePrice() + config.priceIncrement() * current;
        if (!deduct(player, currencyMaterial(config.currency()), price)) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        int next = current + 1;
        count.put(player.getUniqueId(), next);
        player.setMaxHealth(BASE_MAX_HEALTH + config.hpPerBead() * next);
        player.sendMessage("§a佛珠 +" + config.hpPerBead()
                + " 最大血量（当前上限 " + (int) (BASE_MAX_HEALTH + config.hpPerBead() * next) + "）！");
    }

    private void handleRespawn(PlayerRespawnedEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Integer c = count.get(uuid);
        if (c == null || c <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setMaxHealth(BASE_MAX_HEALTH + config.hpPerBead() * c);
            }
        }, 1L);
    }

    private boolean deduct(Player player, Material currency, int amount) {
        if (currency == null) {
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

    private static Material currencyMaterial(String currency) {
        switch (currency.toLowerCase()) {
            case "iron":
                return Material.IRON_INGOT;
            case "gold":
                return Material.GOLD_INGOT;
            case "diamond":
                return Material.DIAMOND;
            case "emerald":
                return Material.EMERALD;
            case "bronze":
                return Material.BRICK;
            default:
                return Material.DIAMOND;
        }
    }

    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过佛珠商店注入");
            return;
        }
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            content = removeBlock(content);
            int speedEnd = content.indexOf(SPEED_END);
            if (speedEnd < 0) {
                plugin.getLogger().warning("未找到剑攻速商店块，跳过佛珠注入");
                return;
            }
            String block = buildBlock();
            content = content.substring(0, speedEnd) + block + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入佛珠商店物品: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("佛珠商店注入失败: " + ex.getMessage());
        }
    }

    private String removeBlock(String content) {
        int start = content.indexOf(MARKER_START);
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(MARKER_END, start);
        if (end < 0) {
            return content;
        }
        int endLine = content.indexOf('\n', end);
        endLine = endLine < 0 ? content.length() : endLine + 1;
        return content.substring(0, start) + content.substring(endLine);
    }

    private String buildBlock() {
        return MARKER_START + "\n"
                + "  - price: " + config.basePrice() + " of " + config.currency() + "\n"
                + "    stack:\n"
                + "      type: " + config.material().name().toLowerCase() + "\n"
                + "      display-name: \"" + config.name() + "\"\n"
                + "      lore:\n"
                + "        - \"购买 +" + config.hpPerBead() + " 最大血量（本局，上限 " + config.maxPurchases() + " 次）\"\n"
                + MARKER_END + "\n";
    }
}
