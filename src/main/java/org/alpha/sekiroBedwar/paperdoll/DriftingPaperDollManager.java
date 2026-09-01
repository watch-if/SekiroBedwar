package org.alpha.sekiroBedwar.paperdoll;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.player.BWPlayer;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/**
 * 漂流纸人管理器：消耗品，右键血量减半换 5 纸人（可超上限），效果死亡复位。
 */
public final class DriftingPaperDollManager {
    private static final String MARKER_START = "# === SekiroBedwar drifting-paper-doll START ===";
    private static final String MARKER_END = "# === SekiroBedwar drifting-paper-doll END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final PaperDollConfig config;
    private final PaperDollManager paperDollManager;
    private final NamespacedKey ownerKey;
    private final DriftingPaperDollListener listener;

    public DriftingPaperDollManager(SekiroBedwar plugin, PaperDollConfig config,
                                    PaperDollManager paperDollManager) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.ownerKey = new NamespacedKey(plugin, "drifting_paper_doll");
        this.listener = new DriftingPaperDollListener(this);
    }

    public void enable() {
        if (!config.driftingEnabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
        injectShop();
        plugin.getLogger().info("漂流纸人已启用：价格=" + config.driftingPriceAmount()
                + " " + config.driftingPriceCurrency() + " 上限=" + config.driftingMaxHold());
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

    // ============ 商店 ============

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
            plugin.getLogger().warning("读取漂流纸人购买物品失败: " + ex.getMessage());
            return;
        }
        if (bought == null || bought.getType() != config.driftingMaterial()) {
            return;
        }
        String name = bought.hasItemMeta() && bought.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(bought.getItemMeta().getDisplayName()) : "";
        if (!name.contains(config.driftingName())) {
            return;
        }
        ev.setCancelled(true);
        if (countDrifting(player) >= config.driftingMaxHold()) {
            player.sendMessage("§c漂流纸人已达上限（" + config.driftingMaxHold() + "）！");
            return;
        }
        if (!deduct(player, ev.getMaterialItem())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makeDriftingPaperDoll(player));
    }

    private boolean deduct(Player player, ItemStackHolder costHolder) {
        if (costHolder == null) {
            return false;
        }
        ItemStack cost = costHolder.as(ItemStack.class);
        if (cost == null || cost.getType() == Material.AIR) {
            return false;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().removeItem(cost);
        return leftover.isEmpty();
    }

    /** 幂等注入：把漂流纸人购买项并入剑攻速类别（sword-speed 块内，END 之前）。 */
    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过漂流纸人商店注入");
            return;
        }
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            plugin.getLogger().info("未找到商店文件 " + shopFile.getAbsolutePath() + "，跳过注入");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            content = removeBlock(content);
            int speedEnd = content.indexOf(SPEED_END);
            if (speedEnd < 0) {
                plugin.getLogger().warning("未找到剑攻速商店块，跳过漂流纸人注入");
                return;
            }
            String block = buildBlock();
            content = content.substring(0, speedEnd) + block + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入漂流纸人商店物品（并入剑攻速类别）: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("漂流纸人商店注入失败: " + ex.getMessage());
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
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_START).append('\n');
        sb.append("  - price: ").append(config.driftingPriceAmount()).append(" of ")
                .append(config.driftingPriceCurrency()).append('\n');
        sb.append("    stack:\n");
        sb.append("      type: ").append(config.driftingMaterial().name().toLowerCase()).append('\n');
        sb.append("      display-name: \"").append(yamlEscape(config.driftingName())).append("\"\n");
        sb.append("      lore:\n");
        sb.append("        - \"").append(yamlEscape("血量>50%时右键：上限减半，得 5 纸人")).append("\"\n");
        sb.append(MARKER_END).append('\n');
        return sb.toString();
    }

    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
