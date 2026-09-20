package org.alpha.sekiroBedwar.sugar;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 夜叉戮糖管理器（消耗品忍具）：只狼的「夜叉戮糖」——代价换攻击。
 *
 * <p><b>获得</b>：忍具商店单按钮购买（默认 20 金），得到绑定玩家的唱片
 * （{@code sugar.material}=MUSIC_DISC_13，PDC 记录 owner）；绑定物不可丢弃 / 入容器、
 * 死亡不掉落。</p>
 *
 * <p><b>使用（右键）</b>：主 / 副手持绑定糖右键（拦截原版唱片进唱机）→ 消耗 1 个糖，
 * 立即进入 {@code duration-seconds}(30s) 效果：力量 II（{@code strength-amplifier}）、
 * HP 上限减半（当前 HP 随上限钳制）、架势上限减半、该玩家造成的架势伤害 ×
 * {@code stance-multiplier}(1.5)。效果持续期间再次右键 = 刷新时长 + 力量 II（不重复减半）。</p>
 *
 * <p><b>结束</b>：HP 上限复原（HP 不恢复）、架势上限复原、躯干值恢复到最大架势的
 * {@code recover-stance-fraction}(50%)、力量 II 移除。死亡 / 离局 / 插件禁用清理。</p>
 */
public final class SugarManager {

    private final SekiroBedwar plugin;
    private final SugarConfig config;
    private final StanceManager stanceManager;
    private final SekiroShopManager shop;
    private final NamespacedKey ownerKey;
    private final SugarListener listener;

    /** 效果截止时刻（nanoTime 毫秒）。 */
    private final Map<UUID, Long> activeUntil = new HashMap<>();
    /** 应用糖时的最大生命值快照（结束复原用）。 */
    private final Map<UUID, Double> snapshotMaxHealth = new HashMap<>();

    private BukkitTask tickTask;

    public SugarManager(SekiroBedwar plugin, SugarConfig config,
                        StanceManager stanceManager, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.shop = shop;
        this.ownerKey = new NamespacedKey(plugin, "sugar_owner");
        this.listener = new SugarListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> clearAll(ev.getPlayer().getUuid())); // 离局全清
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // 架势伤害倍率 + 架势上限缩放：由造成方 / 目标是否处于糖效决定，集中在 StanceManager 应用
        stanceManager.setStanceDamageMultiplierProvider(
                uuid -> isActive(uuid) ? config.stanceMultiplier() : 1.0);
        stanceManager.setMaxStanceScaleProvider(
                uuid -> isActive(uuid) ? config.maxStanceFactor() : 1.0);
        shop.register(new ShopItem("sugar", 44, this::renderItem, this::buy));
        plugin.getLogger().info("夜叉戮糖已启用：价格=" + config.priceAmount() + " "
                + config.priceCurrency() + " 时长=" + config.durationSeconds() + "s 架势×"
                + config.stanceMultiplier() + " 上限×" + config.maxHealthFactor());
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // 恢复在线玩家的临时减半（避免插件重载后 HP 上限 / 架势缩放残留）
        for (UUID uuid : new ArrayList<>(activeUntil.keySet())) {
            activeUntil.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                restoreLimits(player);
            }
        }
        snapshotMaxHealth.clear();
        stanceManager.setStanceDamageMultiplierProvider(null);
        stanceManager.setMaxStanceScaleProvider(null);
    }

    /** 离线清理：效果状态与快照一并移除。 */
    public void clearAll(UUID uuid) {
        activeUntil.remove(uuid);
        snapshotMaxHealth.remove(uuid);
    }

    // ============ 状态查询 ============

    /** 是否处于糖效期间。 */
    public boolean isActive(UUID uuid) {
        Long until = activeUntil.get(uuid);
        return until != null && now() < until;
    }

    // ============ 绑定物品识别 ============

    public boolean isSugar(ItemStack item) {
        if (item == null || item.getType() != config.material() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isOwnedSugar(ItemStack item, Player player) {
        if (!isSugar(item)) {
            return false;
        }
        String owner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    private int countSugar(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isOwnedSugar(item, player)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** 从背包消耗一个绑定糖（存在性已验证）。 */
    private void consumeOne(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isOwnedSugar(item, player)) {
                continue;
            }
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItem(i, null);
            }
            return;
        }
    }

    private ItemStack makeSugar(Player player) {
        ItemStack item = new ItemStack(config.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§d" + config.name());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    // ============ 右键使用 ============

    /** 右键使用：拦截原版唱片进唱机 → 消耗 1 个 → 进入 / 刷新糖效。 */
    public void handleUse(Player player, PlayerInteractEvent event) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!isOwnedSugar(main, player) && !isOwnedSugar(off, player)) {
            return; // 非绑定糖：原版行为
        }
        event.setCancelled(true); // 拦截唱片右键放入唱机等原版行为
        if (!consumeOneSafe(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (isActive(uuid)) {
            // 效果期间再次使用：刷新时长 + 力量 II，不重复减半、不重复快照
            activeUntil.put(uuid, now() + config.durationSeconds() * 1000L);
            applyStrength(player);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.0f);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(uuid,
                    org.alpha.sekiroBedwar.api.ToolId.SUGAR, null,
                    org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
            return;
        }
        apply(player);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.0f);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(uuid,
                org.alpha.sekiroBedwar.api.ToolId.SUGAR, null,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
    }

    private boolean consumeOneSafe(Player player) {
        if (countSugar(player) < 1) {
            return false;
        }
        consumeOne(player);
        return true;
    }

    /** 施加糖效：力量 II + HP 上限减半（当前 HP 随上限钳制）+ 架势上限减半 + 快照。 */
    private void apply(Player player) {
        UUID uuid = player.getUniqueId();
        applyStrength(player);
        double currentMax = player.getMaxHealth();
        snapshotMaxHealth.put(uuid, currentMax);
        player.setMaxHealth(currentMax * config.maxHealthFactor());
        stanceManager.setMaxScale(uuid, config.maxStanceFactor()); // 决斗中才有效（无架势则无操作）
        activeUntil.put(uuid, now() + config.durationSeconds() * 1000L);
    }

    /** 施加 / 刷新力量 II（addPotionEffect 覆盖同类型旧时长）。 */
    private void applyStrength(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                config.durationSeconds() * 20, config.strengthAmplifier()));
    }

    /** 每 tick：检测到期 → 复原。 */
    private void tick() {
        if (activeUntil.isEmpty()) {
            return;
        }
        long now = now();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(activeUntil).entrySet()) {
            if (now < entry.getValue()) {
                continue;
            }
            activeUntil.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                snapshotMaxHealth.remove(entry.getKey()); // 离线/死亡：清快照不强恢复
                continue;
            }
            restore(player);
        }
    }

    /** 效果结束复原：架势上限复原 + 躯干恢复到 50% + HP 上限复原（HP 不恢复）+ 移除力量 II。 */
    private void restore(Player player) {
        UUID uuid = player.getUniqueId();
        restoreLimits(player);
        if (stanceManager.hasStance(uuid)) {
            double max = stanceManager.getMaxStance(uuid);
            stanceManager.setStance(uuid, max * config.recoverStanceFraction());
        }
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1.0f, 1.0f);
    }

    /** 仅复原 HP 上限 / 架势上限 / 力量 II（不恢复躯干、不动 HP），供 disable / 死亡兜底。 */
    private void restoreLimits(Player player) {
        UUID uuid = player.getUniqueId();
        stanceManager.setMaxScale(uuid, 1.0);
        Double snapshot = snapshotMaxHealth.remove(uuid);
        if (snapshot != null) {
            player.setMaxHealth(snapshot);
        }
        player.removePotionEffect(PotionEffectType.STRENGTH);
    }

    // ============ 死亡 / 掉落 ============

    /** 死亡：清效果状态与快照、从掉落移除糖（不掉落地面）。 */
    public void handlePlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        activeUntil.remove(uuid);
        snapshotMaxHealth.remove(uuid);
        stanceManager.setMaxScale(uuid, 1.0); // 若仍有架势状态则复原缩放（死亡通常已移除架势）
        event.getDrops().removeIf(this::isSugar);
    }

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 点击路由）：扣费 → 发绑定糖。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.priceCurrency()), config.priceAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makeSugar(player));
        player.sendMessage("§a已购得" + config.name() + "！");
    }

    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7右键使用：力量II " + config.durationSeconds() + "s");
        lore.add("§7期间架势伤害 ×" + config.stanceMultiplier());
        lore.add("§7但 HP / 架势上限减半");
        lore.add("§7结束恢复 50% 躯干（HP 不恢复）");
        lore.add(ShopCurrency.priceLore(config.priceCurrency(), config.priceAmount()));
        if (viewer != null && isActive(viewer.getUniqueId())) {
            lore.add("§e▶ 效果生效中");
        }
        return SekiroShopManager.icon(config.material(), "§d" + config.name(), lore);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
