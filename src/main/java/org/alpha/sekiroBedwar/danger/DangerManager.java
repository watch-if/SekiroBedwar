package org.alpha.sekiroBedwar.danger;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 危攻击 / 识破管理器：主手持矛（LUNGE 突进附魔）疾跑攻击 = 危，不可完美弹反；
 * 格挡危 → 破盾 + 扣架势；识破（下蹲后 170ms 内接危）→ 免疫并反扣攻击方架势。
 *
 * <p>下界合金长矛经忍具商店 GUI 购买，价格配置化（{@code danger.shop}）。</p>
 */
public final class DangerManager {

    private final SekiroBedwar plugin;
    private final DangerConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final SekiroShopManager shop;
    private final DangerListener listener;

    /** 玩家开始下蹲的时刻（供识破 170ms 窗口判定）。 */
    private final Map<UUID, Long> sneakStart = new HashMap<>();

    public DangerManager(SekiroBedwar plugin, DangerConfig config,
                         StanceManager stanceManager, DuelManager duelManager,
                         SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.shop = shop;
        this.listener = new DangerListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        shop.register(new ShopItem("spear", 30, viewer -> renderItem(), ctx -> buySpear(ctx)));
        plugin.getLogger().info("危攻击已启用：架势×" + config.stancePenalty()
                + " 识破窗=" + config.mikiriWindowMs() + "ms 破盾=" + config.shieldBreakSeconds() + "s");
    }

    public void disable() {
        sneakStart.clear();
    }

    public void clear(UUID uuid) {
        sneakStart.remove(uuid);
    }

    // ============ 判定 ============

    /** 主手是否矛（任一等级）。 */
    public boolean isSpear(Material material) {
        return material != null && material.name().endsWith("_SPEAR");
    }

    /** 该近战命中是否危攻击（矛 + LUNGE 附魔 + 疾跑）。 */
    public boolean isDangerAttack(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return false;
        }
        Player attacker = CombatUtils.resolveMeleeAttacker(event);
        if (attacker == null) {
            return false;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isSpear(weapon.getType())) {
            return false;
        }
        if (weapon.getEnchantmentLevel(Enchantment.LUNGE) <= 0) {
            return false;
        }
        return attacker.isSprinting();
    }

    /** 识破：受害者下蹲且距下蹲 ≤ mikiri-window-ms。 */
    public boolean isMikiri(Player victim) {
        if (victim == null || !victim.isSneaking()) {
            return false;
        }
        Long start = sneakStart.get(victim.getUniqueId());
        if (start == null) {
            return false;
        }
        return now() - start <= config.mikiriWindowMs();
    }

    /** 记录玩家开始下蹲的时刻。 */
    public void recordSneakStart(Player player) {
        sneakStart.put(player.getUniqueId(), now());
    }

    // ============ 伤害处理（HIGHEST） ============

    /** 识破成功 → 取消危伤害 + 攻击方扣架势。 */
    public void handleDamage(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!isDangerAttack(event)) {
            return;
        }
        if (!isMikiri(victim)) {
            return;
        }
        Player attacker = CombatUtils.resolveMeleeAttacker(event);
        event.setCancelled(true);
        sneakStart.remove(victim.getUniqueId());
        if (attacker != null) {
            stanceManager.reduceStance(attacker.getUniqueId(), config.stancePenalty());
            stanceManager.markActive(attacker.getUniqueId());
        }
    }

    /** 危格挡惩罚：破盾 + 防守方扣架势。 */
    public void applyShieldBreak(Player victim) {
        stanceManager.disableBlocking(victim.getUniqueId(), config.shieldBreakSeconds());
        int ticks = Math.max(1, (int) Math.ceil(config.shieldBreakSeconds() * 20.0));
        victim.setCooldown(Material.SHIELD, ticks);
        stanceManager.reduceStance(victim.getUniqueId(), config.stancePenalty());
    }

    // ============ 忍具商店 GUI（下界合金长矛 + 突进） ============

    /** 购买入口（GUI 点击路由）：扣费 → 发突进长矛。 */
    public void buySpear(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.shopCurrency()), config.shopAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(buildSpear());
        player.sendMessage("§a已购得下界合金长矛（突进）！");
    }

    /** 渲染：说明 + 价格。 */
    private ItemStack renderItem() {
        List<String> lore = new ArrayList<>();
        lore.add("§7突进：疾跑攻击为危攻击");
        lore.add("§7危不可被完美弹反，格挡被破盾");
        lore.add(ShopCurrency.priceLore(config.shopCurrency(), config.shopAmount()));
        return SekiroShopManager.icon(Material.NETHERITE_SPEAR, "§b下界合金长矛", lore);
    }

    private ItemStack buildSpear() {
        ItemStack spear = new ItemStack(Material.NETHERITE_SPEAR);
        ItemMeta meta = spear.getItemMeta();
        meta.setDisplayName("§b下界合金长矛");
        meta.addEnchant(Enchantment.LUNGE, 1, true);
        spear.setItemMeta(meta);
        return spear;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
