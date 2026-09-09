package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 盾牌弹反（独立模块）：主手举盾消耗纸人 → 举盾后一段时间全部按完美弹反窗口处理 →
 * 窗口结束强制解除举盾。
 *
 * <p><b>触发</b>：持盾右键（{@code PlayerInteractEvent} RIGHT_CLICK，主 / 副手任一持盾，
 * 直接读玩家手持不依赖事件手位字段）→ <b>即时扣 {@code deflect.paper-doll-cost} 纸人并开窗</b>；
 * 右键容器 / 门等可交互方块不触发（防误开方块白扣）；已处于弹反窗口内再次右键不重复扣费。
 * 音效反馈（无文字）：成功开窗盾牌格挡声、纸人不足低音提示。</p>
 *
 * <p><b>窗口语义</b>：{@code deflect.deflect-window-ms}（默认 2000ms）内来袭的
 * <b>近战命中一律按完美弹反</b>处理（由 {@code ParryManager} 查询 {@link #isDeflecting}
 * 强制走完美弹反分支——免伤免击退 + 攻击方架势 −= Dbase×parry-attacker-multiplier +
 * 反馈音效 + 封印计数 + 崩条评估 + 巴之雷钩子，与普通完美弹反完全一致）。与普通完美弹反的
 * 差别：不受「举盾后 170ms」窗口限制、不受「一次按住只弹反一击」限制——<b>整个窗口每一击
 * 都弹反</b>（这就是纸人的开销）。危攻击依然不可弹反（ParryManager 危判定在窗口判定之前）；
 * 弓箭不参与弹反（与完美弹反口径一致）；受击状态（stagger，无法格挡）期间不授予强制弹反。</p>
 *
 * <p><b>强制收盾</b>：窗口结束时 {@code setCooldown(SHIELD, 1)} 强制 {@code isBlocking()}
 * 变 false（1.21.11 无“停止持盾”API，冷却是既有的强制手段）——玩家可立即重新右键举盾
 * 再开一窗（再付纸人）。</p>
 *
 * <p><b>恐怖区免疫</b>：处于弹反窗口的玩家免疫僵尸头颅恐怖区的负面（{@code TerrorManager}
 * 查询 {@link #isDeflecting}），语义不变。</p>
 */
public final class DeflectManager {

    private final SekiroBedwar plugin;
    private final DeflectConfig config;
    private final PaperDollManager paperDollManager;
    private final SekiroShopManager shop;
    private final DeflectListener listener;

    /** 玩家 → 弹反窗口截止时刻（单调毫秒）。窗口结束即移除。 */
    private final Map<UUID, Long> deflectUntil = new HashMap<>();
    private BukkitTask expireTask;

    public DeflectManager(SekiroBedwar plugin, DeflectConfig config, PaperDollManager paperDollManager,
                          SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.shop = shop;
        this.listener = new DeflectListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        expireTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 1L, 1L);
        shop.register(new ShopItem("shield", 60, viewer -> renderShieldItem(), ctx -> buyShield(ctx)));
        plugin.getLogger().info("盾牌弹反已启用：纸人×" + config.paperDollCost()
                + " 完美弹反窗口=" + config.deflectWindowMs() + "ms（窗口结束强制收盾）");
    }

    public void disable() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
        deflectUntil.clear();
    }

    public void clear(UUID uuid) {
        deflectUntil.remove(uuid);
    }

    /** 玩家是否处于纸人弹反窗口（= 完美弹反窗口，也用于免疫恐怖区负面）。 */
    public boolean isDeflecting(UUID uuid) {
        Long until = deflectUntil.get(uuid);
        return until != null && now() < until;
    }

    /**
     * 持盾右键触发（由 {@link DeflectListener} 调用，即时扣费，不做延迟举盾确认）：
     * 已在窗口内不重复扣；纸人不足仅低音提示（不开窗）；成功开窗 + 盾牌格挡音效。
     */
    public boolean tryStartDeflect(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (isDeflecting(uuid)) {
            return false; // 已在弹反窗口内：不重复扣费（防连点/事件重复触发）
        }
        if (!paperDollManager.consumePaperDolls(player, config.paperDollCost())) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.4f);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(uuid,
                    org.alpha.sekiroBedwar.api.ToolId.SHIELD_DEFLECT, null,
                    org.alpha.sekiroBedwar.api.ToolUseResult.INSUFFICIENT_RESOURCE);
            return false; // 纸人不足：仅低音提示（不扣不开窗，无文字）
        }
        deflectUntil.put(uuid, now() + config.deflectWindowMs());
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.1f);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(uuid,
                org.alpha.sekiroBedwar.api.ToolId.SHIELD_DEFLECT, null,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
        return true;
    }

    /** 任一手持盾（主手或副手）。 */
    public static boolean hasShieldInHand(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.SHIELD
                || player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
    }

    /** 周期检测：窗口结束 → 移除记录 + 强制解除举盾（盾牌 1 tick 冷却打断持盾）。 */
    private void expire() {
        if (deflectUntil.isEmpty()) {
            return;
        }
        long now = now();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(deflectUntil).entrySet()) {
            if (now < entry.getValue()) {
                continue;
            }
            deflectUntil.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.setCooldown(Material.SHIELD, 1);
            }
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    // ============ 忍具商店 GUI（盾牌） ============

    /** 购买入口（GUI 点击路由）：扣费 → 发普通盾牌（价格配置化 deflect.shop）。 */
    public void buyShield(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.shopCurrency()), config.shopAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(new ItemStack(Material.SHIELD));
        player.sendMessage("§a已购得盾牌！");
    }

    /** 渲染：用途 + 价格。 */
    private ItemStack renderShieldItem() {
        List<String> lore = new ArrayList<>();
        lore.add("§7主手持盾右键：消耗 " + config.paperDollCost() + " 纸人");
        lore.add("§7举盾后 " + (config.deflectWindowMs() / 1000) + "s 内近战全部完美弹反");
        lore.add(ShopCurrency.priceLore(config.shopCurrency(), config.shopAmount()));
        return SekiroShopManager.icon(Material.SHIELD, "§f盾牌", lore);
    }
}
