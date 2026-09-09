package org.alpha.sekiroBedwar.equip;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 取消耐久消耗（独立小模块）：监听 {@link PlayerItemDamageEvent}（盔甲受击磨损、
 * 工具使用磨损、盾牌格挡磨损都会派发该事件），按类别取消 → 物品不再掉耐久。
 *
 * <p>开关（duel.yml {@code no-durability:}）：{@code enabled} 总开关；{@code armor}（盔/胸/腿/靴
 * 含海龟壳）、{@code tools}（剑/镐/斧/锄/锹）、{@code shield}（盾牌）分别控制。
 * 盾牌默认<b>不</b>保护——保留斧头破盾玩法里的耐久消耗风味。</p>
 */
public final class DurabilityGuardManager implements Listener {

    private final SekiroBedwar plugin;
    private final DurabilityGuardConfig config;

    public DurabilityGuardManager(SekiroBedwar plugin, DurabilityGuardConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("取消耐久已启用：护甲=" + config.armor() + " 工具=" + config.tools()
                + " 盾牌=" + config.shield());
    }

    public void disable() {
        // 监听器随插件禁用自然失效，无自持状态
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (!org.alpha.sekiroBedwar.combat.BwScope.inGame(event.getPlayer().getUniqueId())) {
            return; // 玩法只在 BedWars 对局内生效（对局外耐久按原版）
        }
        if (isProtected(item.getType())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(Material type) {
        if (type == Material.SHIELD) {
            return config.shield();
        }
        String name = type.name();
        if (config.armor() && (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS"))) {
            return true;
        }
        return config.tools() && (name.endsWith("_SWORD") || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL"));
    }
}
