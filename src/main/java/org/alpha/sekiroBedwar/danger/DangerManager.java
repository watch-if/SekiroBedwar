package org.alpha.sekiroBedwar.danger;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
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
 * 危攻击 / 识破管理器：主手持矛（LUNGE 突进附魔）疾跑攻击 = 危，不可完美弹反；
 * 格挡危 → 破盾 + 扣架势；识破（下蹲后 170ms 内接危）→ 免疫并反扣攻击方架势。
 */
public final class DangerManager {
    private static final String MARKER_START = "# === SekiroBedwar spear START ===";
    private static final String MARKER_END = "# === SekiroBedwar spear END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final DangerConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final DangerListener listener;

    /** 玩家开始下蹲的时刻（供识破 170ms 窗口判定）。 */
    private final Map<UUID, Long> sneakStart = new HashMap<>();

    public DangerManager(SekiroBedwar plugin, DangerConfig config,
                         StanceManager stanceManager, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.listener = new DangerListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
        injectShop();
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

    // ============ 商店（下界合金长矛 + 突进） ============

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
            return;
        }
        if (bought == null || bought.getType() != Material.NETHERITE_SPEAR) {
            return;
        }
        String name = bought.hasItemMeta() && bought.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(bought.getItemMeta().getDisplayName()) : "";
        if (!name.contains("下界合金长矛")) {
            return;
        }
        ev.setCancelled(true);
        if (!deduct(player, ev.getMaterialItem())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(buildSpear());
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

    private ItemStack buildSpear() {
        ItemStack spear = new ItemStack(Material.NETHERITE_SPEAR);
        ItemMeta meta = spear.getItemMeta();
        meta.setDisplayName("§b下界合金长矛");
        meta.addEnchant(Enchantment.LUNGE, 1, true);
        spear.setItemMeta(meta);
        return spear;
    }

    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过长矛商店注入");
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
                plugin.getLogger().warning("未找到剑攻速商店块，跳过长矛注入");
                return;
            }
            String block = buildBlock();
            content = content.substring(0, speedEnd) + block + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入下界合金长矛商店物品: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("长矛商店注入失败: " + ex.getMessage());
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
        return MARKER_START + "\n"
                + "  - price: 1 of diamond\n"
                + "    stack:\n"
                + "      type: netherite_spear\n"
                + "      display-name: \"下界合金长矛\"\n"
                + "      lore:\n"
                + "        - \"突进：疾跑攻击为危攻击\"\n"
                + MARKER_END + "\n";
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
