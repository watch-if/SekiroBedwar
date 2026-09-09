package org.alpha.sekiroBedwar.paperdoll;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.screamingsandals.bedwars.api.Team;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 纸人管理器（独立模块）：忍具系统的铺垫资源。
 *
 * <p>购买经忍具商店 GUI：动态价按存活队伍数实时计算与渲染、点击时校验与扣费。</p>
 */
public final class PaperDollManager {

    private final SekiroBedwar plugin;
    private final PaperDollConfig config;
    private final SekiroShopManager shop;
    private final NamespacedKey ownerKey;
    private final PaperDollListener listener;

    /** 投掷物命中标记：投掷者 UUID → {目标 UUID, 截止时刻}。 */
    private final Map<UUID, MarkedTarget> markedTargets = new HashMap<>();

    public PaperDollManager(SekiroBedwar plugin, PaperDollConfig config, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.shop = shop;
        this.ownerKey = new NamespacedKey(plugin, "paper_doll_owner");
        this.listener = new PaperDollListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        shop.register(new ShopItem("paper-doll", 40, this::renderItem, this::buy));
        plugin.getLogger().info("纸人已启用：上限=" + config.maxPerPlayer() + "（忍具商店 GUI 动态价购买）");
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
        // 白名单投掷物（喷溅/滞留药水、鸡蛋、雪球等）不消耗纸人
        Material mat = projectileMaterial(projectile);
        if (mat != null && config.throwWhitelist().contains(mat)) {
            return;
        }
        if (countPaperDolls(shooter) < config.throwCost()) {
            return;
        }
        consumePaperDolls(shooter, config.throwCost());
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(shooter.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolId.PAPER_DOLL, null,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS); // 投掷抵扣（忍具资源使用）
        Material refund = refundMaterial(projectile);
        if (refund != null) {
            ItemStack item = new ItemStack(refund);
            Bukkit.getScheduler().runTask(plugin, () -> shooter.getInventory().addItem(item));
        }
    }

    private Material projectileMaterial(Projectile projectile) {
        if (projectile instanceof Egg) {
            return Material.EGG;
        }
        if (projectile instanceof Snowball) {
            return Material.SNOWBALL;
        }
        if (projectile instanceof ThrownPotion potion) {
            ItemStack item = potion.getItem();
            return item == null ? null : item.getType();
        }
        if (projectile instanceof WindCharge) {
            return Material.WIND_CHARGE;
        }
        if (projectile instanceof Trident) {
            return Material.TRIDENT;
        }
        if (projectile instanceof Fireball) {
            return Material.FIRE_CHARGE;
        }
        return null;
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
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolId.PAPER_DOLL, victim.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS); // 命中后传送（近战追加路径）
    }

    /** 左键（挥臂）触发传送：投掷物命中标记目标且在窗口内 → 传送并消耗纸人（不需近战打中）。 */
    public void handleSwing(Player attacker) {
        if (!config.teleportEnabled() || attacker == null) {
            return;
        }
        MarkedTarget mark = markedTargets.get(attacker.getUniqueId());
        if (mark == null) {
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
        Player target = Bukkit.getPlayer(mark.target);
        if (target != null && target.isOnline()) {
            attacker.teleport(findSafeLocation(target));
        }
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolId.PAPER_DOLL, mark.target,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS); // 命中后传送（左键路径）
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

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 点击路由）：上限拦截 → 动态价（按存活队伍数）自扣 → 发绑定纸人。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        BWPlayer bw = ctx.bwPlayer();
        if (player == null || !bw.isInGame()) {
            return;
        }
        if (countPaperDolls(player) >= config.maxPerPlayer()) {
            player.sendMessage("§c纸人已达上限（" + config.maxPerPlayer() + "）！");
            return;
        }
        PaperDollConfig.PaperPrice price = config.priceForAliveTeams(countAliveTeams(bw.getGame()));
        if (!ShopCurrency.deduct(player, ShopCurrency.of(price.currency()), price.amount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makePaperDoll(player));
    }

    /** 动态渲染：持有数 + 当前档位价格（随存活队伍数实时变化）。 */
    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7忍具的消耗品");
        PaperDollConfig.PaperPrice price = config.priceForAliveTeams(
                viewer == null ? 2 : countAliveTeams(viewerContextGame(viewer)));
        lore.add(ShopCurrency.priceLore(price.currency(), price.amount()));
        int held = viewer == null ? 0 : countPaperDolls(viewer);
        lore.add("§7持有: " + held + "/" + config.maxPerPlayer());
        if (held >= config.maxPerPlayer()) {
            lore.add("§c已达上限");
        }
        return SekiroShopManager.icon(config.material(), "§f" + config.name(), lore);
    }

    /** 渲染时取查看者所在对局（经 BedWars API 实查；查不到按 2 队档兜底）。 */
    private LocalGame viewerContextGame(Player viewer) {
        BWPlayer bw = shop.bwOf(viewer);
        return bw == null ? null : bw.getGame();
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

    private static final class MarkedTarget {
        final UUID target;
        final long until;

        MarkedTarget(UUID target, long until) {
            this.target = target;
            this.until = until;
        }
    }
}
