package org.alpha.sekiroBedwar.speed;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 剑攻速强化管理器（独立模块）。
 *
 * <p>商店可购买的等级化强化——购买后给玩家实体加 {@code ATTACK_SPEED}（ADD_NUMBER）
 * {@code AttributeModifier}，降低本人所有近战武器的攻击冷却；MC 1.21 冷却 tick = 20 ÷ 攻速，
 * 服务端生效、不改客户端。等级/价格/攻速上限全部配置化（{@link SpeedConfig}）。</p>
 *
 * <p><b>与原商店的契合</b>：ScreamingBedWars 商店是纯文件驱动（SimpleInventories
 * {@code shop/shop.yml} 的 {@code data:} 列表），没有任何程序化注册物品的 API——因此本模块在
 * enable 时把「剑攻速强化」类别<b>幂等注入</b>到 ScreamingBedWars 的 {@code shop/shop.yml}
 * （标记注释包裹、可自更新），购买则用 {@link StorePrePurchaseEvent} 拦截。</p>
 *
 * <p><b>购买拦截语义</b>：BedWars 先验可负担
 * （{@code hasPlayerInInventory}），再触发 {@code StorePrePurchaseEvent}；<b>取消该事件会在
 * 任何扣费/给物之前 return</b>——因此拦截「取消 + 自己 removeItem 扣费 + 应用修正」，既不给
 * marker 物品也不双扣。拦截只认 display-name 前缀（「剑攻速强化 Lv.N」），不误伤其它商店物品。</p>
 *
 * <p><b>逐级购买</b>：等级只能按 Lv.1 → Lv.2 → Lv.3 顺序购买，不可跳级（仿巴之雷两级）。</p>
 *
 * <p><b>生命周期</b>：等级存本模块 {@code Map<UUID,Integer>}（BedWars 升级系统为队伍级，无玩家级）。
 * 原版死亡重生重建玩家实体，属性修正不保留 → {@link PlayerRespawnedEvent} 后 1 tick 重应用
 * （对齐 BedWars 自己 EnchantmentUpgradeHandler 的做法）；{@code removeModifier} 先行保证幂等
 * （1.21.1 重生恢复属性时修正可能残留，重复 addModifier 会抛重复键异常）。离局 / 退出服务器 /
 * 重进清理残留修正（本局有效）。</p>
 */
public final class SpeedManager {
    private static final String MARKER_START = "# === SekiroBedwar sword-speed START ===";
    private static final String MARKER_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final SpeedConfig config;
    private final UUID modifierUuid;
    private final Map<UUID, Integer> levels = new HashMap<>();
    private final SpeedListener listener;

    public SpeedManager(SekiroBedwar plugin, SpeedConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.modifierUuid = UUID.nameUUIDFromBytes("sekirobedwar:sword_speed".getBytes(StandardCharsets.UTF_8));
        this.listener = new SpeedListener(this);
    }

    /** 注册监听 + BedWars API 事件处理器 + 注入商店类别。 */
    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        StorePrePurchaseEvent.handle(plugin, this::handlePrePurchase);
        PlayerRespawnedEvent.handle(plugin, this::handleRespawn);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid()));

        injectShop();
        plugin.getLogger().info("剑攻速强化已启用：max-level=" + config.maxLevel()
                + " per-level=" + config.perLevelAttackSpeed()
                + " max-attack-speed=" + config.maxAttackSpeed());
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

    // ============ 商店购买拦截 ============

    /**
     * 处理一次 {@link StorePrePurchaseEvent}：只认我们的 marker 物品（display-name 前缀），
     * 取消后自扣费并应用等级；其余物品完全放行走 BedWars 原流程。
     */
    private void handlePrePurchase(StorePrePurchaseEvent ev) {
        UUID uuid = ev.getPlayer().getUuid();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !ev.getPlayer().isInGame()) {
            return;
        }
        // 升级购买（upgrade property）的 getNewItem() 为 null——放行
        ItemStackHolder newItemHolder = ev.getNewItem();
        if (newItemHolder == null) {
            return;
        }
        ItemStack bought;
        try {
            bought = newItemHolder.as(ItemStack.class);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("读取购买物品失败: " + ex.getMessage());
            return;
        }
        if (bought == null || bought.getType() == Material.AIR) {
            return;
        }
        Integer level = parseLevel(ChatColor.stripColor(displayName(bought)));
        if (level == null) {
            return; // 非本模块物品
        }

        // 取消：BedWars 在任何扣费/给物之前 return，整个交易作废
        ev.setCancelled(true);

        int current = levels.getOrDefault(uuid, 0);
        if (level <= current) {
            player.sendMessage("§c剑攻速强化 Lv." + level + " 已购买过！");
            return;
        }
        // 逐级购买（仿巴之雷两级）：只允许买下一级，不可跳级
        if (level > current + 1) {
            player.sendMessage("§c剑攻速强化需逐级购买，请先购买 Lv." + (current + 1) + "！");
            return;
        }
        try {
            if (!deduct(player, ev.getMaterialItem())) {
                player.sendMessage("§c购买失败：货币不足！");
                return;
            }
            levels.put(uuid, level);
            applySpeed(player, level);
            player.sendMessage("§a剑攻速强化 已升至 Lv." + level + "！"
                    + " 冷却缩短至 " + cooldownTicks(level) + " tick");
        } catch (RuntimeException ex) {
            // 取消已生效，玩家不会损失货币；仅记录日志，避免影响其它购买
            plugin.getLogger().warning("剑攻速强化购买处理异常: " + ex.getMessage());
        }
    }

    /** 自扣费：精确移除 BedWars 原本要收的货币栈（已先验可负担）。 */
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
     * 按确定性 UUID 移除旧修正（remove-then-add 的幂等前提）。运行时为纯 Spigot（1.21.1）：
     * paper-api 独有的 {@code removeModifier(UUID)} 在 Spigot 上不存在（会 NoSuchMethodError），
     * 故遍历 {@code getModifiers()} 找到同 UUID 修正后按 {@code removeModifier(AttributeModifier)} 移除
     * ——该重载与 {@code AttributeModifier.getUniqueId()} 在 paper-api 26.2 与 spigot-api 1.21.1 均存在。
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

    // ============ 商店类别注入 ============

    /**
     * 幂等注入：把「剑攻速强化」类别写到 ScreamingBedWars 的 {@code shop/shop.yml} 末尾。
     * 用标记注释定位旧块并移除，再按当前配置重新生成——配置一改，下次启动自动更新。
     * （BedWars 默认商店在启动时构建并缓存，注入内容需重启后生效。）
     */
    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过剑攻速强化商店注入");
            return;
        }
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            plugin.getLogger().info("未找到商店文件 " + shopFile.getAbsolutePath() + "，跳过注入");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            content = replaceBlock(content);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入剑攻速强化商店物品（第 2 页）: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("剑攻速强化商店注入失败: " + ex.getMessage());
        }
    }

    /** 移除旧标记块（如有），把新块追加到文件末尾。 */
    private String replaceBlock(String content) {
        int start = content.indexOf(MARKER_START);
        if (start >= 0) {
            int end = content.indexOf(MARKER_END, start);
            if (end >= 0) {
                int endLine = content.indexOf('\n', end);
                endLine = endLine < 0 ? content.length() : endLine + 1;
                content = content.substring(0, start) + content.substring(endLine);
            }
        }
        if (!content.endsWith("\n")) {
            content += "\n";
        }
        return content + buildShopBlock();
    }

    /**
     * 由当前配置生成商店顶层购买项 YAML 块（对齐 root shop.yml 的 {@code data:} 列表）：
     * 每个等级一条 {@code   - price:} 顶层项（2 空格），{@code stack:} 子键在 4 空格、
     * {@code type/display-name/lore} 在 6 空格；首项带 {@code pagebreak: before} 把整组
     * 推到商店第 2 页（page forward 进入），与纸人 / 巴之雷顶层项同页。
     */
    private String buildShopBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_START).append('\n');
        for (int level = 1; level <= config.maxLevel(); level++) {
            SpeedConfig.SpeedPrice price = config.price(level);
            sb.append("  - price: ").append(price.amount()).append(" of ")
                    .append(price.currency()).append('\n');
            if (level == 1) {
                sb.append("    pagebreak: before\n");
            }
            sb.append("    stack:\n");
            appendMap(sb, 6, "type", config.categoryMaterial().name().toLowerCase());
            appendMap(sb, 6, "display-name", markerDisplayName(level));
            sb.append("      lore:\n");
            sb.append("        - \"").append(yamlEscape("攻击冷却缩短至 " + cooldownTicks(level) + " tick"))
                    .append("\"\n");
            sb.append("        - \"").append(yamlEscape("本局永久生效，死亡后保留")).append("\"\n");
            if (level > 1) {
                sb.append("        - \"").append(yamlEscape("需先购买 Lv." + (level - 1) + "（逐级购买）")).append("\"\n");
            }
        }
        sb.append(MARKER_END).append('\n');
        return sb.toString();
    }

    /** marker 物品显示名：「{categoryName} Lv.{level}」。 */
    private String markerDisplayName(int level) {
        return config.categoryName() + " Lv." + level;
    }

    /** 按 {indent} 空格缩进写一行键值对。 */
    private static void appendMap(StringBuilder sb, int indent, String key, String value) {
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
        sb.append(key).append(": \"").append(yamlEscape(value)).append("\"\n");
    }

    /** 按 {indent} 空格缩进写 lore 列表。 */
    private static void appendLore(StringBuilder sb, int indent, List<String> lines) {
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
        sb.append("lore:\n");
        for (String line : lines) {
            for (int i = 0; i < indent + 2; i++) {
                sb.append(' ');
            }
            sb.append("- \"").append(yamlEscape(line)).append("\"\n");
        }
    }

    /** YAML 双引号字符串转义（反斜杠与双引号）。 */
    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ============ 解析 ============

    /** 从 display-name 解析强化等级；非本模块物品返回 null。 */
    private Integer parseLevel(String strippedName) {
        String prefix = ChatColor.stripColor(config.categoryName()) + " Lv.";
        if (strippedName == null || !strippedName.startsWith(prefix)) {
            return null;
        }
        String rest = strippedName.substring(prefix.length()).trim();
        try {
            int level = Integer.parseInt(rest);
            return level >= 1 && level <= config.maxLevel() ? level : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null || meta.getDisplayName() == null ? "" : meta.getDisplayName();
    }

    /** 该等级拿剑时的攻击冷却 tick（= 20 ÷ 总攻速，四舍五入用于展示）。 */
    private int cooldownTicks(int level) {
        double total = config.effectiveTotalAttackSpeed(level);
        if (total <= 0.0) {
            return 20;
        }
        return (int) Math.round(20.0 / total);
    }

    /**
     * 攻速属性常量：paper-api 26.2 为 {@code ATTACK_SPEED}（新版命名），
     * spigot-api 1.21.1 为 {@code GENERIC_ATTACK_SPEED}（旧命名）。用 {@code valueOf} 按名探测，
     * 两个编译目标 / 运行环境都能解析（同 {@code combat/CombatUtils} 的攻击力探测）。
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
