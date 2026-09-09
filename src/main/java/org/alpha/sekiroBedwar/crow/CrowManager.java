package org.alpha.sekiroBedwar.crow;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderSignal;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 雾璃鸦管理器（独立模块）：反击型忍具。
 *
 * <p><b>获得</b>：忍具商店单按钮购买（默认 20 金），得到一只绑定玩家的末影之眼
 * （图标/物品即 {@code crow.material}，PDC 记录 owner）；<b>每人每局限购
 * {@code crow.max-per-player}(1) 只——按购买计数判定（不检测背包），配合自动补给
 * 购买一次即可长期持有</b>；绑定物不可丢弃 / 入容器、死亡不掉落。</p>
 *
 * <p><b>使用（右键激活）</b>：主手 / 副手持绑定雾璃鸦右键 → 取消原版投掷（眼不飞出）→
 * 消耗 1 只雾璃鸦 + {@code crow.paper-doll-cost}(2) 纸人 → 进入悬停：一只末影之眼以
 * {@link ItemDisplay} 悬浮在玩家头顶，持续 {@code crow.hover-ms}(2000)。悬停中再次右键
 * 无效果（不扣）；纸人不足仅低音提示（不扣鸦）。激活成功 / 失败都有音效反馈（无文字）。</p>
 *
 * <p><b>补给 CD</b>：激活消耗（或死亡失去）后进入 {@code crow.refill-delay-seconds}(10) 秒
 * 冷却，到期自动向背包补发一只（仍受持有上限约束；补发有拾取音效）。{@code 0} = 关闭补给。</p>
 *
 * <p><b>悬停效果（一次性）</b>：悬停期间玩家受到的第一次来自玩家（近战或投射物射手）的伤害
 * ——伤害取消（不扣血）→ 玩家传送到攻击方身后安全方块 → 雾璃鸦随之破碎。
 * 悬停 {@code hover-ms} 到点无人攻击则破碎（{@code crow.shatter-chance}=1.0 必碎；
 * 判定未碎时返还一只），护身效果随之失效。</p>
 */
public final class CrowManager {

    private final SekiroBedwar plugin;
    private final CrowConfig config;
    private final PaperDollManager paperDollManager;
    private final SekiroShopManager shop;
    private final NamespacedKey ownerKey;
    private final CrowListener listener;

    /** 玩家 → 悬停中的雾璃鸦状态。 */
    private final Map<UUID, CrowState> hovering = new HashMap<>();
    /** 补给 CD：玩家 → 自动补发一只雾璃鸦的时刻（激活消耗 / 死亡后写入）。 */
    private final Map<UUID, Long> refillAt = new HashMap<>();
    /** 玩家 → 本局已购买次数（购买上限按计数判定，不检测背包；离局清除）。 */
    private final Map<UUID, Integer> purchases = new HashMap<>();
    private BukkitTask tickTask;

    public CrowManager(SekiroBedwar plugin, CrowConfig config,
                       PaperDollManager paperDollManager, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.shop = shop;
        this.ownerKey = new NamespacedKey(plugin, "crow_owner");
        this.listener = new CrowListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        org.screamingsandals.bedwars.api.events.PlayerLeaveEvent.handle(
                plugin, ev -> clearAll(ev.getPlayer().getUuid())); // 离局全清：计数/悬停/补给 CD（下局重新购买）
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        shop.register(new ShopItem("crow", 42, this::renderItem, this::buy));
        plugin.getLogger().info("雾璃鸦已启用：悬停=" + config.hoverMs() + "ms 纸人×"
                + config.paperDollCost() + " 破碎概率=" + config.shatterChance());
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (CrowState state : new ArrayList<>(hovering.values())) {
            clearDisplay(state);
        }
        hovering.clear();
        refillAt.clear();
        purchases.clear();
    }

    /** 结束某玩家悬停并清显示实体（不动补给 CD）。 */
    public void clear(UUID uuid) {
        CrowState state = hovering.remove(uuid);
        if (state != null) {
            clearDisplay(state);
        }
    }

    /** 离线清理：悬停 + 补给 CD + 购买计数一并移除。 */
    public void clearAll(UUID uuid) {
        clear(uuid);
        refillAt.remove(uuid);
        purchases.remove(uuid);
    }

    /**
     * 玩家死亡：结束悬停；<b>仅当本局购买过雾璃鸦</b>才重新起补给 CD（修：从未购买
     * 的玩家死亡复活白得一只的 bug）。
     */
    public void handleDeath(UUID uuid) {
        clear(uuid);
        if (purchases.getOrDefault(uuid, 0) > 0) {
            scheduleRefill(uuid);
        }
    }

    /** 启动补给 CD：delay 秒后自动补发一只（已在上限则届时跳过）。 */
    public void scheduleRefill(UUID uuid) {
        if (config.refillDelaySeconds() <= 0) {
            return;
        }
        refillAt.put(uuid, now() + config.refillDelaySeconds() * 1000L);
    }

    /** 补给 CD 到期处理：在线且未达上限 → 背包补一只 + 拾取音效。 */
    private void processRefills(long now) {
        if (refillAt.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Long> entry : new HashMap<>(refillAt).entrySet()) {
            if (now < entry.getValue()) {
                continue;
            }
            refillAt.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (countCrows(player) >= config.maxPerPlayer()) {
                continue;
            }
            player.getInventory().addItem(makeCrowEye(player));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.4f);
        }
    }

    // ============ 绑定物品识别 ============

    public boolean isCrowEye(ItemStack item) {
        if (item == null || item.getType() != config.material() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isOwnedCrowEye(ItemStack item, Player player) {
        if (!isCrowEye(item)) {
            return false;
        }
        String owner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    /** 玩家背包中绑定雾璃鸦总数（含未激活的）。 */
    public int countCrows(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isOwnedCrowEye(item, player)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private ItemStack makeCrowEye(Player player) {
        ItemStack item = new ItemStack(config.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§d" + config.name());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    /** 从背包消耗一只绑定雾璃鸦（存在性已验证）。 */
    private void consumeOne(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isOwnedCrowEye(item, player)) {
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

    // ============ 商店 ============

    /** 购买入口（GUI 点击路由）：每局限购计数 → 扣费 → 发绑定雾璃鸦（不检测背包）。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        int used = purchases.getOrDefault(player.getUniqueId(), 0);
        if (used >= config.maxPerPlayer()) {
            player.sendMessage("§c雾璃鸦每局限购 " + config.maxPerPlayer() + " 只！");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.priceCurrency()), config.priceAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        purchases.put(player.getUniqueId(), used + 1);
        player.getInventory().addItem(makeCrowEye(player));
        player.sendMessage("§a已购得雾璃鸦！");
    }

    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7掷出后悬顶 " + (config.hoverMs() / 1000.0) + "s：");
        lore.add("§7首次受击免伤并传送至攻击方身后");
        lore.add("§7使用消耗 " + config.paperDollCost() + " 纸人 + 雾璃鸦×1");
        lore.add(ShopCurrency.priceLore(config.priceCurrency(), config.priceAmount()));
        if (viewer != null) {
            int used = purchases.getOrDefault(viewer.getUniqueId(), 0);
            lore.add("§7本局已购: " + used + "/" + config.maxPerPlayer());
            if (used >= config.maxPerPlayer()) {
                lore.add("§a✔ 已购买（消耗 / 死亡后自动补发）");
            }
        }
        return SekiroShopManager.icon(config.material(), "§d" + config.name(), lore);
    }

    // ============ 右键激活 ============

    /**
     * 右键使用雾璃鸦之眼：取消原版投掷 → 扣 2 纸人 + 1 鸦 → 开启头顶悬停。
     * 悬停中再点无效果（不扣）；纸人不足取消投掷但只低音提示（不扣鸦）。
     */
    public void handleUse(Player player, org.bukkit.event.player.PlayerInteractEvent event) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!isOwnedCrowEye(main, player) && !isOwnedCrowEye(off, player)) {
            return; // 非绑定雾璃鸦：原版行为
        }
        event.setCancelled(true); // 拦截末影之眼的原版投掷
        if (isHovering(player.getUniqueId())) {
            return; // 已在悬停：忽略重复点击
        }
        if (paperDollManager.countPaperDolls(player) < config.paperDollCost()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.4f);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(player.getUniqueId(),
                    org.alpha.sekiroBedwar.api.ToolId.CROW, null,
                    org.alpha.sekiroBedwar.api.ToolUseResult.INSUFFICIENT_RESOURCE);
            return; // 纸人不足：不扣鸦
        }
        paperDollManager.consumePaperDolls(player, config.paperDollCost());
        consumeOne(player);
        startHover(player);
        scheduleRefill(player.getUniqueId()); // 消耗后进入补给 CD（默认 10s 补一只）
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.3f);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(player.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolId.CROW, null,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
    }

    /** 兜底：若取消交互后仍有投掷物生成（版本差异），悬停中玩家掷出的眼一律拦截。 */
    public void handleStrayEye(Player thrower, ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderSignal && isHovering(thrower.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    boolean isHovering(UUID uuid) {
        return hovering.containsKey(uuid);
    }

    /** 开启一次悬停（同玩家旧悬停立即破碎让位）。 */
    private void startHover(Player player) {
        CrowState previous = hovering.remove(player.getUniqueId());
        if (previous != null) {
            clearDisplay(previous);
        }
        CrowState state = new CrowState();
        state.until = now() + config.hoverMs();
        state.display = spawnDisplay(player);
        hovering.put(player.getUniqueId(), state);
    }

    private ItemDisplay spawnDisplay(Player player) {
        Location above = player.getEyeLocation().add(0.0, 1.6, 0.0);
        try {
            ItemDisplay display = player.getWorld().spawn(above, ItemDisplay.class);
            display.setItemStack(new ItemStack(Material.ENDER_EYE));
            display.setGravity(false);
            display.setPersistent(false);
            return display;
        } catch (RuntimeException ex) {
            return null; // 显示实体失败不影响机制本体
        }
    }

    // ============ 悬停维护与到期破碎 ============

    /** 每 tick：头顶跟随 + 到期破碎判定 + 补给 CD 到期补发。 */
    private void tick() {
        if (hovering.isEmpty() && refillAt.isEmpty()) {
            return;
        }
        processRefills(now());
        long now = now();
        for (Map.Entry<UUID, CrowState> entry : new HashMap<>(hovering).entrySet()) {
            CrowState state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                clear(entry.getKey());
                continue;
            }
            if (now >= state.until) {
                // 到点破碎判定（默认 100%；未碎返还一只）
                boolean shivered = ThreadLocalRandom.current().nextDouble() < config.shatterChance();
                hovering.remove(entry.getKey());
                if (shivered) {
                    shatterFx(player);
                } else if (countCrows(player) < config.maxPerPlayer()) {
                    player.getInventory().addItem(makeCrowEye(player));
                }
                clearDisplay(state);
            } else {
                followPlayer(state, player);
            }
        }
    }

    /** 悬停期间受到玩家来源的首次伤害：免伤 + 传送到攻击方身后 + 破碎。 */
    public void handleDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        CrowState state = hovering.get(victim.getUniqueId());
        if (state == null) {
            return;
        }
        Player attacker = CombatUtils.resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return; // 非玩家来源的伤害不消耗护身
        }
        event.setCancelled(true); // 第一次伤害不扣血
        hovering.remove(victim.getUniqueId());
        clearDisplay(state);
        shatterFx(victim);
        Location behind = findSafeLocationBehind(attacker);
        if (behind != null) {
            victim.teleport(behind);
        }
    }

    /** 雾璃鸦破碎反馈：紫粒 + 玻璃 / 物品破碎音。 */
    private void shatterFx(Player player) {
        Location eye = player.getEyeLocation().add(0.0, 1.6, 0.0);
        player.getWorld().spawnParticle(Particle.END_ROD, eye, 30, 0.25, 0.25, 0.25, 0.05);
        player.getWorld().playSound(eye, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.2f);
    }

    private void followPlayer(CrowState state, Player player) {
        if (state.display == null || !state.display.isValid()) {
            return;
        }
        state.display.teleport(player.getEyeLocation().add(0.0, 1.6, 0.0));
    }

    private static void clearDisplay(CrowState state) {
        if (state.display != null) {
            state.display.remove();
            state.display = null;
        }
    }

    /** 攻击者身后 2 格的可行走安全点（脚下实体、占位两格空气）；找不到返回 null。 */
    private Location findSafeLocationBehind(Player attacker) {
        Location origin = attacker.getLocation();
        org.bukkit.util.Vector back = origin.getDirection().setY(0.0);
        if (back.lengthSquared() < 1.0e-4) {
            back = new org.bukkit.util.Vector(0, 0, -1);
        }
        back.normalize().multiply(-2.0);
        Location base = origin.clone().add(back);
        Location[] candidates = {
                base,
                base.clone().add(0, 1, 0),
                base.clone().add(0, -1, 0),
                base.clone().add(1, 0, 0),
                base.clone().add(-1, 0, 0),
                base.clone().add(0, 0, 1),
                base.clone().add(0, 0, -1),
        };
        for (Location c : candidates) {
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()
                    && !c.clone().add(0, -1, 0).getBlock().isPassable()) {
                return c.clone().add(0.5, 0.0, 0.5);
            }
        }
        return null;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 悬停状态：截止时刻 + 头顶显示实体。 */
    private static final class CrowState {
        long until;
        ItemDisplay display;
    }
}
