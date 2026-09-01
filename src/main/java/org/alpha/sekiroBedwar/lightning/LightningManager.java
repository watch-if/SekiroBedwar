package org.alpha.sekiroBedwar.lightning;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.events.PlayerRespawnedEvent;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 巴之雷（雷击 / 雷反）管理器（独立模块）。
 *
 * <p>商店两级购买：L1 只获得「三连击接跳斩落雷」效果；L2 附赠忠诚三叉戟（含 L1 效果），
 * 且三叉戟远程命中可衔接落雷。购买依次进行、不可跳级（{@link StorePrePurchaseEvent} 拦截）。</p>
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
 * <p>雷击伤害 = {@code lightning-damage}（默认 5），雷击架势扣除 = 雷击伤害 ×
 * {@code lightning-stance-multiplier}（默认 4）。雷击用 {@code strikeLightningEffect} 视觉闪电 +
 * 泛型伤害（不触发 {@code EntityDamageByEntityEvent}，避免被格挡/弹反二次处理）。</p>
 */
public final class LightningManager {
    private static final String MARKER_START = "# === SekiroBedwar lightning START ===";
    private static final String MARKER_END = "# === SekiroBedwar lightning END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final LightningConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final PaperDollManager paperDollManager;
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
                            PaperDollManager paperDollManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.paperDollManager = paperDollManager;
        this.listener = new LightningListener(this);
    }

    /** 注册监听 + BedWars API 事件处理器 + 注入商店类别。 */
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
            StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
            PlayerLeaveEvent.handle(plugin, ev -> clear(ev.getPlayer().getUuid()));
            injectShop();
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

    /** 三叉戟远程命中目标（{@code ProjectileHitEvent}）：记录 2s 衔接窗口。 */
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

    /** 雷反：攻击方是被雷击者且非落地、170ms 内攻击雷击者。 */
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

    /** 是否处于跳击窗口（三连击窗口 或 三叉戟对目标窗口）。 */
    private boolean inJumpWindow(UUID buyer, Player victim) {
        long now = now();
        Long comboUntil = comboReadyUntil.get(buyer);
        if (comboUntil != null && comboUntil > now) {
            return true;
        }
        UUID tt = tridentTarget.get(buyer);
        Long tj = tridentJumpUntil.get(buyer);
        return tt != null && tt.equals(victim.getUniqueId()) && tj != null && tj > now;
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

    // ============ 商店 ============

    /** 处理 {@link StorePrePurchaseEvent}：取消后自扣费并应用等级（依次购买、不可跳级）。 */
    private void handlePrePurchase(StorePrePurchaseEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !ev.getPlayer().isInGame()) {
            return;
        }
        ItemStackHolder holder = ev.getNewItem();
        if (holder == null) {
            return;
        }
        ItemStack bought = holder.as(ItemStack.class);
        if (bought == null || bought.getType() == Material.AIR) {
            return;
        }
        int level = parseLevel(ChatColor.stripColor(displayName(bought)));
        if (level <= 0) {
            return; // 非本模块物品
        }
        ev.setCancelled(true);
        int current = levels.getOrDefault(uuid, 0);
        if (level <= current) {
            player.sendMessage("§c巴之雷 Lv." + level + " 已购买过！");
            return;
        }
        if (level == 2 && current < 1) {
            player.sendMessage("§c需先购买巴之雷 Lv.1！");
            return;
        }
        if (!deduct(player, ev.getMaterialItem())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        levels.put(uuid, level);
        if (level >= 2) {
            player.getInventory().addItem(buildTrident());
        }
        player.sendMessage("§a已习得巴之雷 Lv." + level + "！");
    }

    /** 自扣费：精确移除 BedWars 原本要收的货币栈。 */
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

    /** 幂等注入：把巴之雷两个购买项<b>并入剑攻速类别</b>（sword-speed 块内，END 之前）。 */
    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过巴之雷商店注入");
            return;
        }
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            plugin.getLogger().info("未找到商店文件 " + shopFile.getAbsolutePath() + "，跳过注入");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            // 1. 移除旧的巴之雷 items（如有）
            content = removeBlock(content, MARKER_START, MARKER_END);
            // 2. 并入剑攻速类别（sword-speed END 之前）
            int speedEnd = content.indexOf(SPEED_END);
            if (speedEnd < 0) {
                plugin.getLogger().warning("未找到剑攻速商店块，跳过巴之雷注入");
                return;
            }
            String lightningItems = buildLightningItems();
            content = content.substring(0, speedEnd) + lightningItems + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入巴之雷商店物品（并入剑攻速类别）: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("巴之雷商店注入失败: " + ex.getMessage());
        }
    }

    /** 移除 start~end 标记之间的块（含标记行）；不存在则原样返回。 */
    private static String removeBlock(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(endMarker, start);
        if (end < 0) {
            return content;
        }
        int endLine = content.indexOf('\n', end);
        endLine = endLine < 0 ? content.length() : endLine + 1;
        return content.substring(0, start) + content.substring(endLine);
    }

    /** 生成巴之雷两个购买项（缩进对齐剑攻速类别 items 列表，无独立类别头）。 */
    private String buildLightningItems() {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_START).append('\n');
        // L1
        sb.append("  - price: ").append(config.level1Amount()).append(" of ")
                .append(config.level1Currency()).append('\n');
        sb.append("    stack:\n");
        appendMap(sb, 6, "type", config.categoryMaterial().name().toLowerCase());
        appendMap(sb, 6, "display-name", config.categoryName() + " Lv.1");
        sb.append("      lore:\n");
        sb.append("        - \"").append(yamlEscape("三连击（≤0.7s）后 1s 内跳斩命中即落雷")).append("\"\n");
        // L2
        sb.append("  - price: ").append(config.level2Amount()).append(" of ")
                .append(config.level2Currency()).append('\n');
        sb.append("    stack:\n");
        appendMap(sb, 6, "type", config.categoryMaterial().name().toLowerCase());
        appendMap(sb, 6, "display-name", config.categoryName() + " Lv.2");
        sb.append("      lore:\n");
        sb.append("        - \"").append(yamlEscape("附赠忠诚三叉戟")).append("\"\n");
        sb.append("        - \"").append(yamlEscape("三叉戟远程命中可衔接落雷")).append("\"\n");
        sb.append(MARKER_END).append('\n');
        return sb.toString();
    }

    /** 按 {indent} 空格缩进写一行键值对。 */
    private static void appendMap(StringBuilder sb, int indent, String key, String value) {
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
        sb.append(key).append(": \"").append(yamlEscape(value)).append("\"\n");
    }

    /** YAML 双引号字符串转义（反斜杠与双引号）。 */
    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 从 display-name 解析购买等级；非本模块物品返回 0。 */
    private int parseLevel(String stripped) {
        String prefix = ChatColor.stripColor(config.categoryName()) + " Lv.";
        if (stripped == null || !stripped.startsWith(prefix)) {
            return 0;
        }
        try {
            int level = Integer.parseInt(stripped.substring(prefix.length()).trim());
            return (level == 1 || level == 2) ? level : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null || meta.getDisplayName() == null ? "" : meta.getDisplayName();
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
