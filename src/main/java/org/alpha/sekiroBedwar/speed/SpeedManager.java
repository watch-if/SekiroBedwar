package org.alpha.sekiroBedwar.speed;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.events.PlayerRespawnedEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 剑攻速强化管理器（独立模块）。
 *
 * <p>等级化强化：购买后给玩家实体加 {@code ATTACK_SPEED}（ADD_NUMBER）{@code AttributeModifier}，
 * 降低本人所有近战武器的攻击冷却；MC 1.21 冷却 tick = 20 ÷ 攻速，服务端生效、不改客户端。
 * 等级/价格/攻速上限全部配置化（{@link SpeedConfig}）。</p>
 *
 * <p><b>购买入口</b>：忍具商店 GUI（{@link SekiroShopManager}）<b>单按钮</b>显示下一级
 * （名称/效果/价格随当前等级滚动），点击即购下一级，门槛 / 扣费 / 应用全在 {@link #buy} 内完成。</p>
 *
 * <p><b>逐级购买</b>：等级只能按 Lv.1 → Lv.2 → Lv.3 顺序购买，不可跳级（单按钮天然逐级）。</p>
 *
 * <p><b>生命周期</b>：等级存本模块 {@code Map<UUID,Integer>}（BedWars 升级系统为队伍级，无玩家级）。
 * 原版死亡重生重建玩家实体，属性修正不保留 → {@link PlayerRespawnedEvent} 后 1 tick 重应用
 * （对齐 BedWars 自己 EnchantmentUpgradeHandler 的做法）；{@code removeModifier} 先行保证幂等
 * （1.21.1 重生恢复属性时修正可能残留，重复 addModifier 会抛重复键异常）。离局 / 退出服务器 /
 * 重进清理残留修正（本局有效）。</p>
 */
public final class SpeedManager {

    private final SekiroBedwar plugin;
    private final SpeedConfig config;
    private final SekiroShopManager shop;
    private final UUID modifierUuid;
    private final Map<UUID, Integer> levels = new HashMap<>();
    private final SpeedListener listener;

    public SpeedManager(SekiroBedwar plugin, SpeedConfig config, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.shop = shop;
        this.modifierUuid = UUID.nameUUIDFromBytes("sekirobedwar:sword_speed".getBytes(StandardCharsets.UTF_8));
        this.listener = new SpeedListener(this);
    }

    /** 注册监听 + BedWars API 事件处理器 + 登记忍具商店条目（单按钮，点击买下一级）。 */
    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        PlayerRespawnedEvent.handle(plugin, this::handleRespawn);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid()));

        // 单按钮合并：显示下一级，点击即买下一级（买后按钮自动更新到再下一级 / 满级）
        shop.register(new ShopItem("sword-speed", 10, this::renderSpeedItem, this::buy));
        plugin.getLogger().info("剑攻速强化已启用：max-level=" + config.maxLevel()
                + " per-level=" + config.perLevelAttackSpeed()
                + " max-attack-speed=" + config.maxAttackSpeed()
                + "（忍具商店 GUI 逐级购买）");
    }

    /** 插件禁用：清等级 + 移除全部在线玩家的攻速修正（防重载后残留生效）。 */
    public void disable() {
        for (UUID uuid : new ArrayList<>(levels.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                removeSpeed(player);
            }
        }
        levels.clear();
    }

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 单按钮）：自动购买「当前级 +1」（逐级不跳级），满级提示。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int level = levels.getOrDefault(uuid, 0) + 1;
        if (level > config.maxLevel()) {
            player.sendMessage("§c剑攻速强化已满级（Lv." + config.maxLevel() + "）！");
            return;
        }
        SpeedConfig.SpeedPrice price = config.price(level);
        if (!ShopCurrency.deduct(player, ShopCurrency.of(price.currency()), price.amount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        levels.put(uuid, level);
        applySpeed(player, level);
        player.sendMessage("§a剑攻速强化 已升至 Lv." + level + "！"
                + " 冷却缩短至 " + cooldownTicks(level) + " tick");
    }

    /** 动态渲染（单按钮）：当前级 / 下一级效果与价格；满级显示已封顶。 */
    private org.bukkit.inventory.ItemStack renderSpeedItem(Player viewer) {
        int current = viewer == null ? 0 : levels.getOrDefault(viewer.getUniqueId(), 0);
        int max = config.maxLevel();
        List<String> lore = new ArrayList<>();
        for (String line : config.categoryLore()) {
            lore.add("§7" + line);
        }
        lore.add("§e当前: §fLv." + current + "§7 / §fLv." + max);
        if (current >= max) {
            lore.add("§a✔ 已满级（冷却 " + cooldownTicks(max) + " tick）");
            lore.add("§e本局永久生效，死亡后保留");
            return SekiroShopManager.icon(config.categoryMaterial(),
                    "§d" + config.categoryName() + "（已满级）", lore);
        }
        int next = current + 1;
        SpeedConfig.SpeedPrice price = config.price(next);
        lore.add("§e下一级 Lv." + next + "：冷却缩短至 " + cooldownTicks(next) + " tick");
        lore.add("§e本局永久生效，死亡后保留");
        lore.add(ShopCurrency.priceLore(price.currency(), price.amount()));
        lore.add("§7逐级购买，点击即购下一级");
        return SekiroShopManager.icon(config.categoryMaterial(),
                "§d" + config.categoryName() + " Lv." + next, lore);
    }

    /** 该等级拿剑时的攻击冷却 tick（= 20 ÷ 总攻速，四舍五入用于展示）。 */
    private int cooldownTicks(int level) {
        double total = config.effectiveTotalAttackSpeed(level);
        if (total <= 0.0) {
            return 20;
        }
        return (int) Math.round(20.0 / total);
    }

    // ============ 攻速修正 ============

    /** 应用等级修正（remove-then-add，幂等防重复键）。 */
    @SuppressWarnings("removal")
    public void applySpeed(Player player, int level) {
        Attribute attr = attackSpeedAttribute();
        if (attr == null) {
            return;
        }
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) {
            return;
        }
        removeSpeedModifier(inst);
        double amount = config.modifierAmount(level);
        if (amount > 0.0) {
            inst.addModifier(new AttributeModifier(modifierUuid, "sekirobedwar:sword_speed", amount,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    /** 移除攻速修正（重进清理残留 / 离局 / 禁用）。 */
    public void removeSpeed(Player player) {
        Attribute attr = attackSpeedAttribute();
        if (attr == null) {
            return;
        }
        AttributeInstance inst = player.getAttribute(attr);
        if (inst != null) {
            removeSpeedModifier(inst);
        }
    }

    /**
     * 按确定性 UUID 移除旧修正（remove-then-add 的幂等前提）。运行时为纯 Spigot：
     * paper-api 独有的 {@code removeModifier(UUID)} 在 Spigot 上不存在（会 NoSuchMethodError），
     * 故遍历 {@code getModifiers()} 找到同 UUID 修正后按 {@code removeModifier(AttributeModifier)} 移除
     * ——该重载与 {@code AttributeModifier.getUniqueId()} 在各编译目标 / 运行环境均存在。
     */
    @SuppressWarnings("removal")
    private void removeSpeedModifier(AttributeInstance inst) {
        for (AttributeModifier modifier : inst.getModifiers()) {
            if (modifier.getUniqueId().equals(modifierUuid)) {
                inst.removeModifier(modifier);
                return;
            }
        }
    }

    // ============ 生命周期 ============

    /** 重进服务器：清理可能在 player.dat 残留的旧修正（本局有效，不留跨会话）。 */
    public void handleJoin(Player player) {
        removeSpeed(player);
    }

    /** 退出服务器：清等级 + 移除修正。 */
    public void handleQuit(Player player) {
        clearPlayer(player.getUniqueId());
    }

    /** 离局 / 退出：移除等级与修正。 */
    private void clearPlayer(UUID uuid) {
        Integer level = levels.remove(uuid);
        if (level != null && level > 0) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                removeSpeed(player);
            }
        }
    }

    /** 重生 1 tick 后重应用（对齐 BedWars EnchantmentUpgradeHandler 的重应用时序）。 */
    private void handleRespawn(PlayerRespawnedEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Integer level = levels.get(uuid);
        if (level == null || level <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                applySpeed(player, level);
            }
        }, 1L);
    }

    /**
     * 攻速属性常量：paper-api 为 {@code ATTACK_SPEED}（新版命名），
     * spigot-api 旧版为 {@code GENERIC_ATTACK_SPEED}。用 {@code valueOf} 按名探测，
     * 编译目标 / 运行环境都能解析（同 {@code combat/CombatUtils} 的攻击力探测）。
     */
    @SuppressWarnings("removal")
    private static Attribute attackSpeedAttribute() {
        for (String name : new String[]{"ATTACK_SPEED", "GENERIC_ATTACK_SPEED"}) {
            try {
                return Attribute.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 该名字在当前 API 中不存在，尝试下一个
            }
        }
        return null;
    }
}
