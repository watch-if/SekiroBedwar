package org.alpha.sekiroBedwar.bead;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.events.PlayerRespawnedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 佛珠管理器：忍具商店 GUI 购买（单局上限 4 次、每次 +5 最大血量、价格递增），
 * 最大血量为全局增益、死亡后由重生恢复。递增价在 GUI 内实时渲染，点击时按当次实际价扣费。
 */
public final class BeadManager {
    private static final double BASE_MAX_HEALTH = 20.0;

    private final SekiroBedwar plugin;
    private final BeadConfig config;
    private final SekiroShopManager shop;
    private final Map<UUID, Integer> count = new HashMap<>();

    public BeadManager(SekiroBedwar plugin, BeadConfig config, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.shop = shop;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        PlayerRespawnedEvent.handle(plugin, this::handleRespawn);
        PlayerLeaveEvent.handle(plugin, ev -> count.remove(ev.getPlayer().getUuid()));
        shop.register(new ShopItem("bead", 50, this::renderItem, this::buy));
        plugin.getLogger().info("佛珠已启用：上限=" + config.maxPurchases()
                + " 每颗+=" + config.hpPerBead() + " 血量（忍具商店 GUI 递增价购买）");
    }

    public void disable() {
        count.clear();
    }

    /** 本次实际价格（递增：base + increment × 已购次数）。 */
    private int priceFor(int current) {
        return config.basePrice() + config.priceIncrement() * current;
    }

    /** 购买入口（GUI 点击路由）：上限 → 递增价扣费 → 加最大血量。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        int current = count.getOrDefault(player.getUniqueId(), 0);
        if (current >= config.maxPurchases()) {
            player.sendMessage("§c佛珠已达上限（" + config.maxPurchases() + " 次）！");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.currency()), priceFor(current))) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        int next = current + 1;
        count.put(player.getUniqueId(), next);
        player.setMaxHealth(BASE_MAX_HEALTH + config.hpPerBead() * next);
        player.sendMessage("§a佛珠 +" + config.hpPerBead()
                + " 最大血量（当前上限 " + (int) (BASE_MAX_HEALTH + config.hpPerBead() * next) + "）！");
    }

    /** 动态渲染：效果 + 已购 n/max + 本次递增价。 */
    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7购买 +" + config.hpPerBead() + " 最大血量（本局，死亡重生保留）");
        int current = viewer == null ? 0 : count.getOrDefault(viewer.getUniqueId(), 0);
        lore.add(ShopCurrency.priceLore(config.currency(), priceFor(current)));
        lore.add("§7已购: " + current + "/" + config.maxPurchases());
        if (current >= config.maxPurchases()) {
            lore.add("§c已达上限");
        }
        return SekiroShopManager.icon(config.material(), "§d" + config.name(), lore);
    }

    private void handleRespawn(PlayerRespawnedEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Integer c = count.get(uuid);
        if (c == null || c <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setMaxHealth(BASE_MAX_HEALTH + config.hpPerBead() * c);
            }
        }, 1L);
    }

}
