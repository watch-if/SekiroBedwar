package org.alpha.sekiroBedwar.lightning;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.events.PlayerRespawnedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 巴之雷（雷击 / 雷反）管理器（独立模块）。
 *
 * <p>两级强化（忍具商店 GUI 单按钮购买）：L1 只获得「三连击接跳斩落雷」
 * 效果；L2 附赠忠诚三叉戟（含 L1 效果），且三叉戟远程命中可衔接落雷。购买依次进行、不可跳级。</p>
 *
 * <p><b>触发雷击的两条路径</b>（均由 {@link #onAttack} 驱动，仅在 ACTIVE 决斗内）：</p>
 * <ul>
 *   <li><b>三连击（L1）</b>：购买者连续 {@code combo.required-hits} 次有效架势命中（每次间隔 ≤
 *       {@code combo.max-interval-ms}，中间被完美弹反即清零）后，开启 {@code combo.jump-window-ms}
 *       跳击窗口；窗口内一次<b>空中攻击</b>（非落地）命中对方 → 落雷。</li>
 *   <li><b>三叉戟衔接（L2）</b>：三叉戟远程命中目标后，{@code trident.hit-window-ms} 内目标再被
 *       一次有效架势命中 → 开启 {@code trident.jump-window-ms} 跳击窗口；窗口内空中攻击命中 → 落雷。</li>
 * </ul>
 * 跳击触发「无论有没有被完美弹反都会触发」（空中命中即便被弹反也落雷）。</p>
 *
 * <p><b>雷反</b>：落雷先<b>缓存</b>伤害与架势扣减（不立即结算）。被雷击者若在 {@code reversal.window-ms}
 * 内、处于<b>非落地</b>、攻击到雷击者（同样无论被弹反与否）→ 走雷反：恢复 {@code reversal.heal-hp}
 * 血量 + 返还对方雷击伤害 × {@code reversal.return-multiplier} 的架势伤害，并清除缓存（伤害不结算）；
 * 窗口过期未反则缓存伤害 / 架势照常施加。木剑时恢复 / 返还取 wood 变体。</p>
 *
 * <p>雷击伤害 = {@code lightning-damage}，雷击架势扣除 = 雷击伤害 ×
 * {@code lightning-stance-multiplier}。雷击用 {@code strikeLightningEffect} 视觉闪电 +
 * 泛型伤害（不触发 {@code EntityDamageByEntityEvent}，避免被格挡/弹反二次处理）。</p>
 */
public final class LightningManager {

    private final SekiroBedwar plugin;
    private final LightningConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final PaperDollManager paperDollManager;
    private final SekiroShopManager shop;
    private final LightningListener listener;

    /** 雷击过期结算任务（每 tick 检查待结算雷击并施加缓存伤害）。 */
    private BukkitTask expireTask;

    /** L2 三叉戟丢失补偿任务。 */
    private BukkitTask compensateTask;
    /** L2 三叉戟「上次检测到缺失」时刻（供补偿计时）。 */
    private final Map<UUID, Long> tridentMissingSince = new HashMap<>();

    /** 购买等级：0 未购 / 1 一级 / 2 二级。 */
    private final Map<UUID, Integer> levels = new HashMap<>();

    // 三连击状态
    private final Map<UUID, Integer> comboCount = new HashMap<>();
    private final Map<UUID, Long> comboLastHit = new HashMap<>();
    private final Map<UUID, Long> comboReadyUntil = new HashMap<>();

    // 三叉戟衔接状态
    private final Map<UUID, UUID> tridentTarget = new HashMap<>();
    private final Map<UUID, Long> tridentHitUntil = new HashMap<>();
    private final Map<UUID, Long> tridentJumpUntil = new HashMap<>();

    // 雷击状态（雷反）
    private final Map<UUID, Strike> strikes = new HashMap<>();

    public LightningManager(SekiroBedwar plugin, LightningConfig config,
                            StanceManager stanceManager, DuelManager duelManager,
                            PaperDollManager paperDollManager, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.paperDollManager = paperDollManager;
        this.shop = shop;
        this.listener = new LightningListener(this);
    }

    /** 注册监听 + 周期任务 + 登记忍具商店条目（L1 / L2）。 */
    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        expireTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireStrikes, 1L, 1L);
        PlayerRespawnedEvent.handle(plugin, this::handleRespawn);
        if (config.tridentCompensateDelaySeconds() > 0) {
            compensateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::compensateTridents, 20L, 20L);
        }
        if (config.shopEnabled()) {
            PlayerLeaveEvent.handle(plugin, ev -> clear(ev.getPlayer().getUuid()));
            // 单按钮合并：显示下一级（L1→L2），点击即购下一级，买后按钮自动更新
            shop.register(new ShopItem("lightning", 20, this::renderItem, this::buy));
        }
        plugin.getLogger().info("巴之雷已启用：伤害=" + config.lightningDamage()
                + " 架势×" + config.lightningStanceMultiplier()
                + " 连击=" + config.comboRequiredHits() + "击");
    }

    /** 插件禁用：取消结算任务并清空状态。 */
    public void disable() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
        if (compensateTask != null) {
            compensateTask.cancel();
            compensateTask = null;
        }
        tridentMissingSince.clear();
        levels.clear();
        comboCount.clear();
        comboLastHit.clear();
        comboReadyUntil.clear();
        tridentTarget.clear();
        tridentHitUntil.clear();
        tridentJumpUntil.clear();
        strikes.clear();
    }

    /** 清理单个玩家全部状态（离局 / 退出）。 */
    private void clear(UUID uuid) {
        levels.remove(uuid);
        comboCount.remove(uuid);
        comboLastHit.remove(uuid);
        comboReadyUntil.remove(uuid);
        tridentTarget.remove(uuid);
        tridentHitUntil.remove(uuid);
        tridentJumpUntil.remove(uuid);
        strikes.remove(uuid);
        tridentMissingSince.remove(uuid);
    }

    /**
     * 一次近战命中到达对方（由 {@code ParryManager} / {@code BlockManager} 在决斗内调用）。
     * {@code parried}=true 表示被完美弹反（事件已取消）；false 表示已造成有效架势扣除。
     */
    public void onAttack(Player attacker, Player victim, boolean parried) {
        if (!config.enabled() || attacker == null || victim == null) {
            return;
        }
        if (tryReversal(attacker, victim)) {
            return;
        }
        int level = levels.getOrDefault(attacker.getUniqueId(), 0);
        if (level <= 0) {
            return;
        }
        boolean airborne = !attacker.isOnGround();
        if (airborne && inJumpWindow(attacker.getUniqueId(), victim)) {
            triggerLightning(attacker, victim);
            resetCombo(attacker.getUniqueId());
            tridentJumpUntil.remove(attacker.getUniqueId());
            return;
        }
        if (parried) {
            resetCombo(attacker.getUniqueId());
            return;
        }
        countCombo(attacker.getUniqueId());
        if (level >= 2) {
            chainTrident(attacker, victim);
        }
    }

    /** 三叉戟远程命中目标（{@code ProjectileHitEvent}）：记录衔接窗口。 */
    public void handleTridentHit(Player thrower, Player victim) {
        if (!config.enabled() || thrower == null || victim == null) {
            return;
        }
        if (levels.getOrDefault(thrower.getUniqueId(), 0) < 2) {
            return;
        }
        Duel duel = duelManager.getDuel(thrower.getUniqueId()).orElse(null);
        if (duel == null || duel.getState() != DuelState.ACTIVE || !duel.contains(victim.getUniqueId())) {
            return;
        }
        tridentTarget.put(thrower.getUniqueId(), victim.getUniqueId());
        tridentHitUntil.put(thrower.getUniqueId(), now() + config.tridentHitWindowMs());
    }

    /** 玩家是否已购买巴之雷（任一等级）。 */
    public boolean hasLightning(UUID uuid) {
        return levels.getOrDefault(uuid, 0) > 0;
    }

    /** 跳击窗口剩余毫秒（三连击窗口与三叉戟窗口取较大者，无则 0）。 */
    public long getJumpWindowRemainingMillis(UUID uuid) {
        long now = now();
        long remaining = 0L;
        Long combo = comboReadyUntil.get(uuid);
        if (combo != null) {
            remaining = Math.max(remaining, combo - now);
        }
        Long trident = tridentJumpUntil.get(uuid);
        if (trident != null) {
            remaining = Math.max(remaining, trident - now);
        }
        return Math.max(0L, remaining);
    }

    // ============ 核心判定 ============

    /** 雷反：攻击方是被雷击者且非落地、窗口内攻击雷击者。 */
    private boolean tryReversal(Player attacker, Player victim) {
        UUID id = attacker.getUniqueId();
        Strike strike = strikes.get(id);
        if (strike == null) {
            return false;
        }
        if (strike.until < now()) {
            strikes.remove(id);
            return false;
        }
        if (!strike.striker.equals(victim.getUniqueId())) {
            return false;
        }
        if (!strike.airborne) {
            return false;
        }
        strikes.remove(id);
        boolean wood = attacker.getInventory().getItemInMainHand().getType() == Material.WOODEN_SWORD;
        double heal = wood ? config.reversalHealHpWood() : config.reversalHealHp();
        double retMult = wood ? config.reversalReturnMultiplierWood() : config.reversalReturnMultiplier();
        // 恢复 HP
        double max = attacker.getMaxHealth();
        attacker.setHealth(Math.min(max, attacker.getHealth() + heal));
        // 返还对方架势伤害
        stanceManager.reduceStance(strike.striker, strike.damage * retMult);
        return true;
    }

    /** 周期结算：雷反窗口过期的待结算雷击 → 施加缓存伤害与架势，然后移除。 */
    private void expireStrikes() {
        if (strikes.isEmpty()) {
            return;
        }
        long now = now();
        for (Map.Entry<UUID, Strike> entry : new ArrayList<>(strikes.entrySet())) {
            Strike strike = entry.getValue();
            if (strike.until >= now) {
                continue;
            }
            strikes.remove(entry.getKey());
            Player victim = Bukkit.getPlayer(entry.getKey());
            if (victim == null || !victim.isOnline() || victim.isDead()) {
                continue;
            }
            victim.damage(strike.damage);
            stanceManager.reduceStance(entry.getKey(), strike.stanceDeducted);
            stanceManager.markActive(entry.getKey());
        }
    }

    /** 是否处于跳击窗口（三连击窗口 或 三叉戟窗口）。 */
    private boolean inJumpWindow(UUID buyer, Player victim) {
        long now = now();
        Long comboUntil = comboReadyUntil.get(buyer);
        if (comboUntil != null && comboUntil > now) {
            return true;
        }
        Long tj = tridentJumpUntil.get(buyer);
        return tj != null && tj > now;
    }

    /** 计数三连击（≤ max-interval 连续，达到 required-hits 开启跳击窗口）。 */
    private void countCombo(UUID buyer) {
        long now = now();
        Long last = comboLastHit.get(buyer);
        int prev;
        if (last != null && now - last <= config.comboMaxIntervalMs()) {
            prev = comboCount.getOrDefault(buyer, 0) + 1;
        } else {
            prev = 1;
        }
        comboCount.put(buyer, prev);
        comboLastHit.put(buyer, now);
        if (prev >= config.comboRequiredHits()) {
            comboReadyUntil.put(buyer, now + config.comboJumpWindowMs());
        }
    }

    /** 三叉戟衔接：远程命中窗口内、目标被有效架势命中 → 开启跳击窗口。 */
    private void chainTrident(Player attacker, Player victim) {
        UUID buyer = attacker.getUniqueId();
        long now = now();
        UUID tt = tridentTarget.get(buyer);
        Long th = tridentHitUntil.get(buyer);
        if (tt != null && tt.equals(victim.getUniqueId()) && th != null && th > now) {
            tridentJumpUntil.put(buyer, now + config.tridentJumpWindowMs());
            tridentTarget.remove(buyer);
            tridentHitUntil.remove(buyer);
        }
    }

    /** 重置三连击状态（被弹反 / 落雷触发后）。 */
    private void resetCombo(UUID buyer) {
        comboCount.remove(buyer);
        comboLastHit.remove(buyer);
        comboReadyUntil.remove(buyer);
    }

    /** 落雷：视觉闪电 + 缓存待结算伤害/架势，记录雷击（供雷反）；伤害与架势在雷反窗口过期后由 {@link #expireStrikes} 施加。 */
    private void triggerLightning(Player attacker, Player victim) {
        if (victim == null || !victim.isOnline() || victim.isDead()) {
            return;
        }
        if (paperDollManager != null && !paperDollManager.consumePaperDolls(attacker, paperDollManager.cost())) {
            return;
        }
        double dmg = config.lightningDamage();
        double stanceDeduct = dmg * config.lightningStanceMultiplier();
        victim.getWorld().strikeLightningEffect(victim.getLocation());
        stanceManager.markActive(victim.getUniqueId());
        strikes.put(victim.getUniqueId(),
                new Strike(attacker.getUniqueId(), now() + config.reversalWindowMs(), dmg, stanceDeduct,
                        !victim.isOnGround()));
    }

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 单按钮）：自动购买下一级（依次、不可跳级），扣费 → 应用（L2 附赠三叉戟）。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int level = levels.getOrDefault(uuid, 0) + 1;
        if (level > 2) {
            player.sendMessage("§c巴之雷已习得全部等级！");
            return;
        }
        String currency = level == 1 ? config.level1Currency() : config.level2Currency();
        int amount = level == 1 ? config.level1Amount() : config.level2Amount();
        if (!ShopCurrency.deduct(player, ShopCurrency.of(currency), amount)) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        levels.put(uuid, level);
        if (level >= 2) {
            player.getInventory().addItem(buildTrident());
        }
        player.sendMessage("§a已习得巴之雷 Lv." + level + "！");
    }

    /** 动态渲染（单按钮）：当前等级 / 下一级效果与价格；L2 后显示已学满。 */
    private ItemStack renderItem(Player viewer) {
        int current = viewer == null ? 0 : levels.getOrDefault(viewer.getUniqueId(), 0);
        List<String> lore = new ArrayList<>();
        lore.add("§7当前: §fLv." + current + "§7 / §fLv.2");
        if (current >= 2) {
            lore.add("§a✔ 已习得全部等级");
            return SekiroShopManager.icon(config.categoryMaterial(),
                    "§b" + config.categoryName() + "（已学满）", lore);
        }
        int next = current + 1;
        if (next == 1) {
            lore.add("§7Lv.1：三连击（≤" + config.comboMaxIntervalMs() + "ms）后跳击窗口内");
            lore.add("§7空中攻击命中即落雷");
            lore.add(ShopCurrency.priceLore(config.level1Currency(), config.level1Amount()));
        } else {
            lore.add("§7Lv.2：附赠忠诚三叉戟，远程命中可衔接落雷");
            lore.add(ShopCurrency.priceLore(config.level2Currency(), config.level2Amount()));
        }
        lore.add("§7依次购买，点击即学下一级");
        return SekiroShopManager.icon(config.categoryMaterial(),
                "§b" + config.categoryName() + " Lv." + next, lore);
    }

    /** L2 附赠：忠诚三叉戟。 */
    private ItemStack buildTrident() {
        ItemStack trident = new ItemStack(Material.TRIDENT);
        ItemMeta meta = trident.getItemMeta();
        meta.setDisplayName("§b巴之雷");
        meta.addEnchant(Enchantment.LOYALTY, 3, true);
        trident.setItemMeta(meta);
        return trident;
    }

    /** 死亡掉落：L2 拥有者不掉落三叉戟。 */
    public void handlePlayerDeath(PlayerDeathEvent event) {
        if (levels.getOrDefault(event.getEntity().getUniqueId(), 0) < 2) {
            return;
        }
        event.getDrops().removeIf(this::isTrident);
    }

    /** 重生补发：L2 拥有者若无三叉戟则补发。 */
    private void handleRespawn(PlayerRespawnedEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        if (levels.getOrDefault(uuid, 0) < 2) {
            return;
        }
        tridentMissingSince.remove(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !hasTrident(player)) {
                player.getInventory().addItem(buildTrident());
            }
        }, 1L);
    }

    /** 周期补偿：L2 拥有者背包长时间无三叉戟 → 补发。 */
    private void compensateTridents() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(levels.keySet())) {
            if (levels.getOrDefault(uuid, 0) < 2) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (hasTrident(player)) {
                tridentMissingSince.remove(uuid);
                continue;
            }
            Long since = tridentMissingSince.get(uuid);
            if (since == null) {
                tridentMissingSince.put(uuid, now);
                continue;
            }
            if (now - since >= config.tridentCompensateDelaySeconds() * 1000L) {
                player.getInventory().addItem(buildTrident());
                tridentMissingSince.remove(uuid);
            }
        }
    }

    private boolean hasTrident(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isTrident(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrident(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) {
            return false;
        }
        String name = item.getItemMeta().getDisplayName();
        return name != null && name.contains("巴之雷");
    }

    /** 服务器单调时钟（毫秒，仅用于差值）。 */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 一次雷击记录（供雷反）。 */
    private static final class Strike {
        final UUID striker;
        final long until;
        final double damage;
        final double stanceDeducted;
        final boolean airborne;

        Strike(UUID striker, long until, double damage, double stanceDeducted, boolean airborne) {
            this.striker = striker;
            this.until = until;
            this.damage = damage;
            this.stanceDeducted = stanceDeducted;
            this.airborne = airborne;
        }
    }
}
