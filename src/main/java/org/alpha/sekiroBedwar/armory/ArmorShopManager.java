package org.alpha.sekiroBedwar.armory;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.equip.AutoEquipManager;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.DyeColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 护甲商店（独立小模块）：劫持 BedWars 商店主页的「护甲」分类点击（与忍具商店入口同一
 * LOWEST 劫持机制，判定 = slib 商店主页 + 点击物品显示名匹配 {@code armor-shop.category-name}），
 * 打开自管套装页。
 *
 * <p>页面复用忍具商店整套框架（{@link SekiroShopManager#openPage}：SekiroShopHolder 的
 * 点击取消/路由/购买后刷新/右下角返回按钮全部继承）——返回按钮重开 BedWars 商店。
 * 每套 = 盔/胸/腿/靴 四件，购买后<b>立即穿到身上</b>。</p>
 *
 * <p><b>档位升级制</b>（sets 列表顺序即档位从低到高）：首次可任选一档；购买更高级时
 * <b>清除身上旧护甲</b>（装备栏四格直接销毁，不掉落不退回）再穿新套装；已拥有同档或更高档后，
 * 该档及更低档购买入口<b>锁定</b>（不扣费）。档位记录按局（离局/退出清除），死亡不清
 * （配合 keep-armor-on-death 护甲本来就在身上）。</p>
 *
 * <p><b>已知边界</b>：不经 BedWars 购买管道 → 不即时获得团队「保护」升级附魔、无队伍染色。</p>
 */
public final class ArmorShopManager {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SekiroBedwar plugin;
    private final ArmorShopConfig config;
    private final SekiroShopManager shopManager;
    private final ArmorShopListener listener;

    /** 玩家 UUID → 已拥有的最高档位（sets 列表下标；无记录 = 未购）。 */
    private final Map<UUID, Integer> highestTier = new HashMap<>();

    private boolean active;

    public ArmorShopManager(SekiroBedwar plugin, ArmorShopConfig config, SekiroShopManager shopManager) {
        this.plugin = plugin;
        this.config = config;
        this.shopManager = shopManager;
        this.listener = new ArmorShopListener(this);
    }

    public void enable() {
        if (!config.enabled() || config.sets().isEmpty()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> highestTier.remove(ev.getPlayer().getUuid()));
        active = true;
        plugin.getLogger().info("护甲商店已启用：劫持商店主页「" + config.categoryName() + "」分类 → 套装页（"
                + config.sets().size() + " 档，高级替换并清除旧甲、低级锁定）");
    }

    public void disable() {
        active = false;
        highestTier.clear();
    }

    /** 退出服务器：档位记录随局清除。 */
    public void handleQuit(UUID uuid) {
        highestTier.remove(uuid);
    }

    boolean active() {
        return active;
    }

    ArmorShopConfig config() {
        return config;
    }

    SekiroShopManager shopManager() {
        return shopManager;
    }

    /** 打开护甲套装页（点击劫持命中后由监听器调用）。 */
    public void openArmory(Player player) {
        List<ShopItem> page = new ArrayList<>();
        List<ArmorShopConfig.ArmorSet> sets = config.sets();
        for (int i = 0; i < sets.size(); i++) {
            final int index = i;
            final ArmorShopConfig.ArmorSet set = sets.get(i);
            page.add(new ShopItem("armor-" + set.id, index,
                    viewer -> renderSet(set, index, viewer),
                    ctx -> buySet(ctx, set, index)));
        }
        shopManager.openPage(player, config.guiTitle(), page);
    }

    private ItemStack renderSet(ArmorShopConfig.ArmorSet set, int index, Player viewer) {
        Material icon = iconOf(set);
        if (icon == null) {
            icon = Material.IRON_CHESTPLATE; // 理论不到达（pieces() 已校验），兜底防渲染炸
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7头盔 + 胸甲 + 护腿 + 靴 四件，购后立即穿上");
        lore.add(ShopCurrency.priceLore(set.currency, set.amount));
        if (viewer != null) {
            int highest = highestTier.getOrDefault(viewer.getUniqueId(), -1);
            if (index < highest) {
                lore.add("§c低阶已锁定（已拥有更高档护甲）");
            } else if (index == highest) {
                lore.add("§a✔ 当前套装（不可重复购买）");
            } else if (highest >= 0) {
                lore.add("§6升级购买：清除身上旧甲并穿上本套");
            }
            lore.add("§7余额: " + ShopCurrency.count(viewer, ShopCurrency.of(set.currency)) + " "
                    + ShopCurrency.label(set.currency));
        }
        return SekiroShopManager.icon(icon, "§b" + set.displayName, lore);
    }

    /** 档位校验（低档/同档锁定）→ 扣费 → 清除旧甲 → 四件即穿。 */
    private void buySet(BuyContext ctx, ArmorShopConfig.ArmorSet set, int index) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        Material[] pieces = set.pieces();
        if (pieces == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int highest = highestTier.getOrDefault(uuid, -1);
        if (index <= highest) {
            player.sendMessage(index == highest
                    ? "§c已拥有该套装，无需重复购买！"
                    : "§c该低档护甲已锁定（你已拥有更高档套装）！");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(set.currency), set.amount)) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        if (highest >= 0) {
            clearWornArmor(player); // 升级购买：销毁身上旧护甲（不掉落不退回）
        }
        PlayerInventory inv = player.getInventory();
        org.bukkit.Color leatherColor = teamDyeColor(player); // 皮革仿 applycolorbyteam 染队伍色
        for (Material piece : pieces) {
            ItemStack stack = new ItemStack(piece);
            if (leatherColor != null && stack.getItemMeta() instanceof LeatherArmorMeta meta) {
                meta.setColor(leatherColor);
                stack.setItemMeta(meta);
            }
            if (!AutoEquipManager.equipPiece(player, stack)) {
                Map<Integer, ItemStack> leftover = inv.addItem(stack); // 装备失败兜底：入背包，绝不吞物品
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        }
        highestTier.put(uuid, index);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.0f);
        player.sendMessage("§a已购得并穿上 " + set.displayName + "！");
    }

    /** 玩家队伍色 → 皮革染色 RGB（TeamColor.name() 与 DyeColor 同名；未知色名返回 null 不染色）。 */
    private org.bukkit.Color teamDyeColor(Player player) {
        try {
            BWPlayer bw = shopManager.bwOf(player);
            if (bw == null) {
                return null;
            }
            LocalGame game = bw.getGame();
            if (game == null) {
                return null;
            }
            String colorName = game.getTeamOfPlayer(bw).getColor().name().toUpperCase(Locale.ROOT);
            DyeColor dye;
            try {
                dye = DyeColor.valueOf(colorName);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            return dye.getColor();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void clearWornArmor(Player player) {
        PlayerInventory inv = player.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            inv.setItem(slot, null);
        }
    }

    private static Material iconOf(ArmorShopConfig.ArmorSet set) {
        String upper = set.materialBase.toUpperCase(Locale.ROOT);
        Material icon = Material.matchMaterial(upper + "_CHESTPLATE");
        if (icon == null || icon.isAir()) {
            icon = Material.matchMaterial(upper + "_HELMET");
        }
        return icon == null || icon.isAir() ? null : icon;
    }
}
