package org.alpha.sekiroBedwar.swordblock;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 剑格挡模块（独立）：给剑赋予盾牌格挡能力，让剑右键举盾格挡、能被斧头破盾、右键禁用。
 *
 * <p>机制：Minecraft <b>1.21.x 后期（~1.21.6+）</b>引入了 {@code minecraft:blocks_attacks} 数据组件，
 * 把盾牌格挡从 {@code ItemShield} 硬编码改成了数据驱动——带该组件的任意物品右键都会进入
 * 格挡态（{@code Player.isBlocking()} 为真）。本模块检测到该组件可用时，用
 * {@link org.bukkit.UnsafeValues#modifyItemStack(ItemStack, String)} 把组件加到剑上。</p>
 *
 * <p><b>版本门控</b>：<b>1.21.4 及更早没有 {@code blocks_attacks}</b>，本模块自动跳过；
 * 升级到含该组件的 1.21.x（门槛取 1.21.8）后自动生效。
 * {@code sword-blocking.enabled=false} 或版本不支持时不注册任务。</p>
 *
 * <p><b>注入方式</b>：周期性任务扫描在线玩家的主手 / 副手 / 背包，对「剑且尚未标记」的物品
 * 调用 {@code modifyItemStack} 追加 {@code blocks_attacks} 组件，并用 {@link NamespacedKey}
 * 打标记（{@code PersistentDataContainer}，随物品走，重进背包 / 重生后仍在）——标记存在即跳过，
 * 每把剑只处理一次，不反复改写。组件补丁只增不改其余数据，BedWars 资源物品的自定义名 / NBT
 * 原样保留。</p>
 *
 * <p><b>与架势 / 弹反的关系</b>：本模块只负责让剑“能格挡”，不碰架势换算——剑格挡后
 * {@code isBlocking()} 为真，完美弹反（{@code parry/} 包）与普通格挡（{@code block/} 包）
 * 的既有 {@code isBlocking()} 判定自动把剑格挡纳入，斧头破盾的盾牌冷却也天然作用于剑。</p>
 */
public final class SwordBlockingManager {
    /** 默认赋予格挡的剑（配置缺失时回退）。 */
    private static final Set<Material> DEFAULT_SWORDS = Set.of(
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
            Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD);

    /** blocks_attacks 组件值（盾牌默认行为）。 */
    private static final String BLOCKS_ATTACKS_VALUE =
            "{block_delay_seconds:0.25f,disable_cooldown_scale:1.0f,"
                    + "damage_reductions:[{horizontal_blocking_angle:90.0f,base:0.0f,factor:1.0f}],"
                    + "item_damage:{threshold:3.0f,base:1.0f,factor:1.0f},"
                    + "bypassed_by:\"#minecraft:bypasses_shield\","
                    + "block_sound:\"minecraft:item.shield.block\","
                    + "disabled_sound:\"minecraft:item.shield.break\"}";

    private final SekiroBedwar plugin;
    private final boolean enabled;
    private final boolean supported;
    private final Set<Material> swordMaterials = new HashSet<>();
    private final int scanTicks;
    private final NamespacedKey markerKey;
    private BukkitTask scanTask;

    public SwordBlockingManager(SekiroBedwar plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "sword_blocking");
        File file = new File(plugin.getDataFolder(), "duel.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        this.enabled = yaml.getBoolean("sword-blocking.enabled", true);
        this.scanTicks = Math.max(1, yaml.getInt("sword-blocking.scan-ticks", 10));
        List<String> names = yaml.getStringList("sword-blocking.materials");
        for (String name : names) {
            try {
                this.swordMaterials.add(Material.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 非法材料名忽略；下面空集合时回退默认剑
            }
        }
        if (this.swordMaterials.isEmpty()) {
            this.swordMaterials.addAll(DEFAULT_SWORDS);
        }
        this.supported = isBlocksAttacksSupported();
    }

    /** 注册扫描任务（仅 enabled 且版本支持 blocks_attacks 时）。 */
    public void enable() {
        if (!enabled) {
            plugin.getLogger().info("剑格挡已禁用（sword-blocking.enabled=false）");
            return;
        }
        if (!supported) {
            plugin.getLogger().info("剑格挡不可用：当前版本无 minecraft:blocks_attacks 组件（1.21.4 及更早没有，需更高 1.21.x），已跳过");
            return;
        }
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAll, 1L, scanTicks);
        plugin.getLogger().info("剑格挡已启用（blocks_attacks 组件，剑右键举盾格挡）");
    }

    /** 插件禁用：取消扫描任务。 */
    public void disable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    /** 扫描全部在线玩家，给剑补 blocks_attacks（幂等，标记存在即跳过）。 */
    private void scanAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanPlayer(player);
        }
    }

    private void scanPlayer(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setItemInMainHand(ensureSwordBlocking(inv.getItemInMainHand()));
        inv.setItemInOffHand(ensureSwordBlocking(inv.getItemInOffHand()));
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack modified = ensureSwordBlocking(contents[i]);
            if (modified != contents[i]) {
                inv.setItem(i, modified);
            }
        }
    }

    /** 若为「未标记的剑」，追加 blocks_attacks 组件并打标记；否则原样返回。 */
    private ItemStack ensureSwordBlocking(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !swordMaterials.contains(stack.getType())) {
            return stack;
        }
        if (hasMarker(stack)) {
            return stack;
        }
        String argument = "minecraft:" + stack.getType().getKey().getKey()
                + "[minecraft:blocks_attacks=" + BLOCKS_ATTACKS_VALUE + "]";
        ItemStack modified = Bukkit.getUnsafe().modifyItemStack(stack, argument);
        if (modified == null || modified.getType().isAir()) {
            return stack;
        }
        mark(modified);
        return modified;
    }

    /** 是否已标记「已赋予格挡」（避免每 tick 反复改写 / 解析失败刷日志）。 */
    private boolean hasMarker(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** 打标记（随物品的自定义数据持久化，重进背包 / 重生后仍在）。 */
    private void mark(ItemStack stack) {
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
    }

    /** blocks_attacks 组件是否为当前版本可用（1.21.x 后期引入；1.21.4 及更早没有，用 1.21.8 做保守门槛）。 */
    private static boolean isBlocksAttacksSupported() {
        int[] version = parseVersion(Bukkit.getBukkitVersion());
        return compare(version, new int[]{1, 21, 8}) >= 0;
    }

    /** 解析 "1.21.1" / "1.21.4" 为 int[]；非数字段按 0。 */
    private static int[] parseVersion(String raw) {
        if (raw == null) {
            return new int[0];
        }
        String[] parts = raw.split("-")[0].split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    /** 逐段比较版本号（长度不足补 0）。 */
    private static int compare(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }
}
