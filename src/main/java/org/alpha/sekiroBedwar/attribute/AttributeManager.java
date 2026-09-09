package org.alpha.sekiroBedwar.attribute;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.lightning.LightningManager;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 属性伤害管理器（独立模块）：锈丸 / 炎上两系强化 + 还原，与巴之雷共用
 * <b>「三选一专精」互斥</b>——巴之雷、锈丸、炎上只能选其一升级（树内仍逐级 L1→L2），
 * 选定后其余两系购买被封锁；<b>还原</b>（需已持有任一系）充值三系进度让玩家重新选择。
 *
 * <p><b>共同触发结构</b>（口径与巴之雷三连击一致，仅近战、仅 ACTIVE 决斗内由
 * Block/Parry 钩子驱动）：连续 {@code required-hits} 次未被完美弹反的有效近战命中
 * （间隔 ≤ {@code max-interval-ms}，被完美弹反清零）→ 开启 {@code window-ms}（默认 5s）
 * 属性窗口；<b>窗口内</b>每一记未被完美弹反的近战命中附加属性效果（后续命中同时刷新窗口）。
 * 第 3 击本身开窗不吃效果（开窗前不在窗口内）。</p>
 *
 * <p><b>锈丸</b>：窗口命中叠中毒（L1 中毒 I / L2 中毒 II + 凋零，每击 +{@code poison/wither-duration-seconds} 10s）。
 * <b>炎上</b>：窗口命中给火焰附加（+{@code fire-ticks} 燃烧刻）；L2 时窗口命中同时叠加凋零，
 * 且<b>非窗口期</b>未被弹反的命中有 {@code out-of-window-chance}(30%) 概率直接点燃对手。</p>
 */
public final class AttributeManager {

    /** 三系属性树（LIGHTNING 由 LightningManager 持有等级，互斥判定统一在这里）。 */
    public enum Tree { LIGHTNING, RUST, BURN }

    private final SekiroBedwar plugin;
    private final AttributeConfig config;
    private final SekiroShopManager shop;
    private LightningManager lightning; // 装配后 setLightningManager 回填（互相引用）

    private final TreeState rust = new TreeState();
    private final TreeState burn = new TreeState();

    public AttributeManager(SekiroBedwar plugin, AttributeConfig config, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.shop = shop;
    }

    public void setLightningManager(LightningManager lightning) {
        this.lightning = lightning;
    }

    public void enable() {
        PlayerLeaveEvent.handle(plugin, ev -> clear(ev.getPlayer().getUuid()));
        if (config.rustEnabled()) {
            shop.register(new ShopItem("rust", 21, this::renderRust, ctx -> buy(ctx, Tree.RUST)));
        }
        if (config.burnEnabled()) {
            shop.register(new ShopItem("burn", 22, this::renderBurn, ctx -> buy(ctx, Tree.BURN)));
        }
        if (config.restoreEnabled()) {
            shop.register(new ShopItem("restore", 23, this::renderRestore, this::buyRestore));
        }
        plugin.getLogger().info("属性伤害已启用：锈丸(中毒/凋零) 炎上(火焰/30%点燃) 还原——与巴之雷三选一专精");
    }

    public void disable() {
        rust.clearAll();
        burn.clearAll();
    }

    /** 离局 / 退出：清两系等级与计数（三选一选择随之释放）。 */
    public void clear(UUID uuid) {
        rust.clear(uuid);
        burn.clear(uuid);
    }

    // ============ 互斥查询（LightningManager 也走这里） ============

    /** 玩家是否已选择 self 以外的属性树。 */
    public boolean ownsOtherTree(UUID uuid, Tree self) {
        switch (self) {
            case RUST:
                return burn.level(uuid) > 0 || hasLightning(uuid);
            case BURN:
                return rust.level(uuid) > 0 || hasLightning(uuid);
            case LIGHTNING:
            default:
                return rust.level(uuid) > 0 || burn.level(uuid) > 0;
        }
    }

    /** 玩家是否已选择任意一系（还原的前置）。 */
    public boolean ownsAny(UUID uuid) {
        return rust.level(uuid) > 0 || burn.level(uuid) > 0 || hasLightning(uuid);
    }

    /** 属性窗口（锈丸/炎上）剩余毫秒——经验条倒计时用（三选一互斥，两系取较大者）。 */
    public long getWindowRemainingMillis(UUID uuid) {
        long now = now();
        long r = rust.windowUntil.getOrDefault(uuid, 0L) - now;
        long b = burn.windowUntil.getOrDefault(uuid, 0L) - now;
        return Math.max(0L, Math.max(r, b));
    }

    private boolean hasLightning(UUID uuid) {
        return lightning != null && lightning.hasLightning(uuid);
    }

    // ============ 战斗钩子（口径同巴之雷：BlockManager 未弹反 / ParryManager 弹反） ============

    public void onAttack(Player attacker, Player victim, boolean parried) {
        if (attacker == null || victim == null || attacker.equals(victim)) {
            return;
        }
        if (config.rustEnabled()) {
            onHit(rust, attacker, victim, parried, true);
        }
        if (config.burnEnabled()) {
            onHit(burn, attacker, victim, parried, false);
        }
    }

    private void onHit(TreeState st, Player attacker, Player victim, boolean parried, boolean isRust) {
        UUID a = attacker.getUniqueId();
        long now = now();
        if (parried) {
            st.hits.remove(a);
            st.lastHit.remove(a); // 被完美弹反：连续计数清零（已开窗口的到期时间不提前）
            return;
        }
        if (st.level(a) <= 0) {
            return;
        }
        boolean inWindow = st.windowUntil.getOrDefault(a, 0L) > now;
        if (inWindow) {
            applyWindowEffect(st.level(a), victim, isRust);
        } else if (!isRust && st.level(a) >= 2
                && ThreadLocalRandom.current().nextDouble() < config.burnOutsideChance()) {
            // 炎上 Lv2 被动：非窗口期未被弹反的命中 30% 概率直接点燃
            ignite(victim, config.burnFireTicks());
        }
        long maxInterval = isRust ? config.rustMaxIntervalMs() : config.burnMaxIntervalMs();
        int required = isRust ? config.rustRequiredHits() : config.burnRequiredHits();
        long windowMs = isRust ? config.rustWindowMs() : config.burnWindowMs();
        Long last = st.lastHit.get(a);
        int count = (last != null && now - last <= maxInterval) ? st.hits.getOrDefault(a, 0) + 1 : 1;
        st.hits.put(a, count);
        st.lastHit.put(a, now);
        if (count >= required) {
            st.windowUntil.put(a, now + windowMs); // 达标开窗；窗口内每次命中续窗
            if (!inWindow) { // 公共忍具事件：仅"开启新窗口"时刻上报（续窗不重复）
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(a,
                        isRust ? org.alpha.sekiroBedwar.api.ToolId.RUST : org.alpha.sekiroBedwar.api.ToolId.BURN,
                        victim.getUniqueId(), org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
            }
        }
    }

    /** 窗口命中的属性效果（锈丸=中毒，L2 毒 II+凋零；炎上=火焰附加，L2 同时叠加凋零）。 */
    private void applyWindowEffect(int attackerLevel, Player victim, boolean isRust) {
        if (isRust) {
            stackPotion(victim, PotionEffectType.POISON, attackerLevel >= 2 ? 1 : 0,
                    config.rustPoisonSeconds());
            if (attackerLevel >= 2) {
                stackPotion(victim, PotionEffectType.WITHER, 0, config.rustWitherSeconds());
            }
        } else {
            ignite(victim, config.burnFireTicks());
            if (attackerLevel >= 2) {
                stackPotion(victim, PotionEffectType.WITHER, 0, config.burnWitherSeconds());
            }
        }
    }

    private static void ignite(Player victim, int addTicks) {
        victim.setFireTicks(Math.min(victim.getFireTicks() + addTicks, 200));
    }

    /** 叠加式药水效果（在剩余时长上加 addSeconds 秒，封顶 300s）。 */
    private static void stackPotion(Player victim, PotionEffectType type, int amplifier, int addSeconds) {
        PotionEffect cur = victim.getPotionEffect(type);
        int duration = Math.min(addSeconds * 20 + (cur == null ? 0 : cur.getDuration()), 20 * 300);
        victim.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true));
    }

    // ============ 购买 ============

    /** 购买入口（单按钮：自动买下一级 L1→L2）；互斥锁定 / 满级 / 余额不足均不扣费。 */
    public void buy(BuyContext ctx, Tree tree) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        TreeState st = tree == Tree.RUST ? rust : burn;
        boolean rustTree = tree == Tree.RUST;
        if ((rustTree && !config.rustEnabled()) || (!rustTree && !config.burnEnabled())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (ownsOtherTree(uuid, tree)) {
            player.sendMessage("§c只能专精一种属性强化（巴之雷/锈丸/炎上）——可购买「还原」后重选！");
            return;
        }
        int level = st.level(uuid) + 1;
        if (level > 2) {
            player.sendMessage("§c" + treeName(rustTree) + "已满级！");
            return;
        }
        String currency = rustTree
                ? (level == 1 ? config.rustLv1Currency() : config.rustLv2Currency())
                : (level == 1 ? config.burnLv1Currency() : config.burnLv2Currency());
        int amount = rustTree
                ? (level == 1 ? config.rustLv1Amount() : config.rustLv2Amount())
                : (level == 1 ? config.burnLv1Amount() : config.burnLv2Amount());
        if (!ShopCurrency.deduct(player, ShopCurrency.of(currency), amount)) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        st.levels.put(uuid, level);
        player.sendMessage("§a" + treeName(rustTree) + " 已升至 Lv." + level + "！");
    }

    /** 还原购买：需已持有任一属性系；充值三系进度（含收回巴之雷附赠三叉戟）供重选。 */
    public void buyRestore(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (!config.restoreEnabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!ownsAny(uuid)) {
            player.sendMessage("§c尚未选择任何属性强化，无需还原！");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.restoreCurrency()), config.restoreAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        rust.clear(uuid);
        burn.clear(uuid);
        if (lightning != null) {
            lightning.resetForRestore(uuid);
        }
        player.sendMessage("§a已还原属性强化，可重新选择巴之雷 / 锈丸 / 炎上！");
    }

    private String treeName(boolean rustTree) {
        return rustTree ? config.rustName() : config.burnName();
    }

    // ============ GUI 渲染 ============

    private org.bukkit.inventory.ItemStack renderRust(Player viewer) {
        return renderTree(viewer, true);
    }

    private org.bukkit.inventory.ItemStack renderBurn(Player viewer) {
        return renderTree(viewer, false);
    }

    private org.bukkit.inventory.ItemStack renderTree(Player viewer, boolean isRust) {
        TreeState st = isRust ? rust : burn;
        int current = viewer == null ? 0 : st.level(viewer.getUniqueId());
        java.util.List<String> lore = new java.util.ArrayList<>();
        if (isRust) {
            lore.add("§7连续 " + config.rustRequiredHits() + " 次攻击未被完美弹反");
            lore.add("§7→ " + (config.rustWindowMs() / 1000) + "s 窗口内命中叠加中毒");
            lore.add("§eLv.1：中毒 I ×10s 每次命中叠加");
            lore.add("§eLv.2：升级为中毒 II + 凋零");
        } else {
            lore.add("§7连续 " + config.burnRequiredHits() + " 次攻击未被完美弹反");
            lore.add("§7→ " + (config.burnWindowMs() / 1000) + "s 窗口内命中附加火焰");
            lore.add("§eLv.1：窗口命中点燃对手");
            lore.add("§eLv.2：窗口命中同时叠加凋零；非窗口 30% 概率点燃");
        }
        lore.add("§7当前: §fLv." + current + "§7 / §fLv.2");
        if (current >= 2) {
            lore.add("§a✔ 已满级");
            return SekiroShopManager.icon(treeIcon(isRust), "§c" + treeName(isRust) + "（已满级）", lore);
        }
        int level = current + 1;
        String currency = isRust
                ? (level == 1 ? config.rustLv1Currency() : config.rustLv2Currency())
                : (level == 1 ? config.burnLv1Currency() : config.burnLv2Currency());
        int amount = isRust
                ? (level == 1 ? config.rustLv1Amount() : config.rustLv2Amount())
                : (level == 1 ? config.burnLv1Amount() : config.burnLv2Amount());
        lore.add(ShopCurrency.priceLore(currency, amount));
        if (viewer != null && ownsOtherTree(viewer.getUniqueId(), isRust ? Tree.RUST : Tree.BURN)) {
            lore.add("§c已专精其他属性——购买「还原」后可选");
        } else {
            lore.add("§7三选一专精，点击升下一级");
        }
        return SekiroShopManager.icon(treeIcon(isRust), "§c" + treeName(isRust) + " Lv." + level, lore);
    }

    private org.bukkit.Material treeIcon(boolean isRust) {
        return isRust ? config.rustIcon() : config.burnIcon();
    }

    private org.bukkit.inventory.ItemStack renderRestore(Player viewer) {
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("§7充值 巴之雷/锈丸/炎上 的购买与等级");
        lore.add("§7（含收回巴之雷三叉戟），重新三选一");
        lore.add(ShopCurrency.priceLore(config.restoreCurrency(), config.restoreAmount()));
        if (viewer != null) {
            if (ownsAny(viewer.getUniqueId())) {
                lore.add("§e可还原：当前已专精一系");
            } else {
                lore.add("§c需先购买任一属性强化");
            }
        }
        return SekiroShopManager.icon(config.restoreIcon(), "§d" + config.restoreName(), lore);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 单系状态：等级 + 连续命中计数 + 窗口截止。 */
    private static final class TreeState {
        final Map<UUID, Integer> levels = new HashMap<>();
        final Map<UUID, Integer> hits = new HashMap<>();
        final Map<UUID, Long> lastHit = new HashMap<>();
        final Map<UUID, Long> windowUntil = new HashMap<>();

        int level(UUID uuid) {
            return levels.getOrDefault(uuid, 0);
        }

        void clear(UUID uuid) {
            levels.remove(uuid);
            hits.remove(uuid);
            lastHit.remove(uuid);
            windowUntil.remove(uuid);
        }

        void clearAll() {
            levels.clear();
            hits.clear();
            lastHit.clear();
            windowUntil.clear();
        }
    }
}
