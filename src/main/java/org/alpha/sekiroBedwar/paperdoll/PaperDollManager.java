package org.alpha.sekiroBedwar.paperdoll;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.screamingsandals.bedwars.api.Team;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.game.LocalGame;
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
 * 纸人管理器（独立模块）：忍具系统的铺垫资源。
 */
public final class PaperDollManager {
    private static final String MARKER_START = "# === SekiroBedwar paper-doll START ===";
    private static final String MARKER_END = "# === SekiroBedwar paper-doll END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final PaperDollConfig config;
    private final NamespacedKey ownerKey;
    private final PaperDollListener listener;

    /** 投掷物命中标记：投掷者 UUID → {目标 UUID, 截止时刻}。 */
    private final Map<UUID, MarkedTarget> markedTargets = new HashMap<>();

    public PaperDollManager(SekiroBedwar plugin, PaperDollConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.ownerKey = new NamespacedKey(plugin, "paper_doll_owner");
        this.listener = new PaperDollListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
        injectShop();
        plugin.getLogger().info("纸人已启用：上限=" + config.maxPerPlayer());
    }

    public void disable() {
        markedTargets.clear();
    }

    /** 巴之雷每次落雷消耗的纸人数量。 */
    public int cost() {
        return config.lightningCost();
    }

    /** 玩家拥有的纸人数量。 */
    public int countPaperDolls(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isOwnedPaperDoll(item, player)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** 消耗 n 个纸人（不足返回 false 不扣）。 */
    public boolean consumePaperDolls(Player player, int n) {
        if (player == null || n <= 0) {
            return n <= 0;
        }
        if (countPaperDolls(player) < n) {
            return false;
        }
        int remaining = n;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!isOwnedPaperDoll(item, player)) {
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

    public boolean isPaperDoll(ItemStack item) {
        if (item == null || item.getType() != config.material() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isOwnedPaperDoll(ItemStack item, Player player) {
        if (!isPaperDoll(item)) {
            return false;
        }
        String owner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    /** 抛投物发射：有纸人时消耗纸人并退还物品（1 纸人抵一次投掷）。 */
    public void handleProjectileLaunch(Player shooter, Projectile projectile) {
        if (!config.throwEnabled()) {
            return;
        }
        if (countPaperDolls(shooter) < config.throwCost()) {
            return;
        }
        consumePaperDolls(shooter, config.throwCost());
        Material refund = refundMaterial(projectile);
        if (refund != null) {
            ItemStack item = new ItemStack(refund);
            Bukkit.getScheduler().runTask(plugin, () -> shooter.getInventory().addItem(item));
        }
    }

    private Material refundMaterial(Projectile projectile) {
        if (projectile instanceof WindCharge) {
            return Material.WIND_CHARGE;
        }
        if (projectile instanceof Fireball) {
            return Material.FIRE_CHARGE;
        }
        return null;
    }

    /** 投掷物命中目标：记录命中标记（2s 窗口）。 */
    public void handleProjectileHit(Player shooter, Player victim) {
        if (!config.teleportEnabled() || shooter == null || victim == null) {
            return;
        }
        if (shooter.equals(victim)) {
            return;
        }
        markedTargets.put(shooter.getUniqueId(),
                new MarkedTarget(victim.getUniqueId(), System.nanoTime() / 1_000_000L + config.teleportWindowMs()));
    }

    /** 追加的近战命中：命中标记目标且在窗口内 → 传送并消耗纸人。 */
    public void handleAttack(Player attacker, Player victim) {
        if (!config.teleportEnabled()) {
            return;
        }
        MarkedTarget mark = markedTargets.get(attacker.getUniqueId());
        if (mark == null || !mark.target.equals(victim.getUniqueId())) {
            return;
        }
        if (mark.until < System.nanoTime() / 1_000_000L) {
            markedTargets.remove(attacker.getUniqueId());
            return;
        }
        if (countPaperDolls(attacker) < config.teleportCost()) {
            return;
        }
        markedTargets.remove(attacker.getUniqueId());
        consumePaperDolls(attacker, config.teleportCost());
        attacker.teleport(findSafeLocation(victim));
    }

    private Location findSafeLocation(Player target) {
        Location base = target.getLocation();
        Location[] candidates = {
                base.clone(),
                base.clone().add(0, 1, 0),
                base.clone().add(1, 0, 0),
                base.clone().add(-1, 0, 0),
                base.clone().add(0, 0, 1),
                base.clone().add(0, 0, -1),
        };
        for (Location c : candidates) {
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                return c.clone().add(0.5, 0, 0.5);
            }
        }
        return base.clone().add(0, 1, 0);
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
            plugin.getLogger().warning("读取纸人购买物品失败: " + ex.getMessage());
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
        if (countPaperDolls(player) >= config.maxPerPlayer()) {
            player.sendMessage("§c纸人已达上限（" + config.maxPerPlayer() + "）！");
            return;
        }
        PaperDollConfig.PaperPrice price = config.priceForAliveTeams(countAliveTeams(bw.getGame()));
        if (!deduct(player, currencyMaterial(price.currency()), price.amount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makePaperDoll(player));
    }

    private int countAliveTeams(LocalGame game) {
        if (game == null) {
            return 2;
        }
        int alive = 0;
        for (Team team : game.getActiveTeams()) {
            if (team.isAlive()) {
                alive++;
            }
        }
        return Math.max(2, alive);
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

    private ItemStack makePaperDoll(Player player) {
        ItemStack item = new ItemStack(config.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§f" + config.name());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    /** 发放 n 个绑定纸人（跳过购买上限校验，供漂流纸人奖励可超上限）。 */
    public void givePaperDolls(Player player, int n) {
        if (player == null || n <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(config.material(), n);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("§f" + config.name());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        stack.setItemMeta(meta);
        player.getInventory().addItem(stack);
    }

    /** 死亡掉落：从掉落列表移除纸人（不掉落地面）。 */
    public void handlePlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isPaperDoll);
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
                return Material.IRON_INGOT;
        }
    }

    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
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
                return;
            }
            String block = buildBlock();
            content = content.substring(0, speedEnd) + block + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入纸人商店物品: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("纸人商店注入失败: " + ex.getMessage());
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
        sb.append("  - price: 1 of iron\n");
        sb.append("    stack:\n");
        sb.append("      type: ").append(config.material().name().toLowerCase()).append('\n');
        sb.append("      display-name: \"").append(yamlEscape(config.name())).append("\"\n");
        sb.append("      lore:\n");
        sb.append("        - \"").append(yamlEscape("忍具的消耗品")).append("\"\n");
        sb.append("        - \"").append(yamlEscape("价格随存活队伍数变化")).append("\"\n");
        sb.append(MARKER_END).append('\n');
        return sb.toString();
    }

    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class MarkedTarget {
        final UUID target;
        final long until;

        MarkedTarget(UUID target, long until) {
            this.target = target;
            this.until = until;
        }
    }
}
