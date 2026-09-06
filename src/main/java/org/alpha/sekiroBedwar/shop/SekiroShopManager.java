package org.alpha.sekiroBedwar.shop;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.events.StorePrePurchaseEvent;
import org.screamingsandals.bedwars.api.player.BWPlayer;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 忍具商店管理器（独立模块）：插件自管 GUI 商店的唯一宿主。
 *
 * <p>全部插件商品（剑攻速/巴之雷/长矛/纸人/漂流纸人/佛珠/盾牌）的判定、扣费、发放
 * 都在本插件 GUI 内完成，动态价格实时显示与校验。</p>
 *
 * <p><b>入口（默认接管模式 {@code shop.page-forward-takeover: true}）</b>：不注入 shop.yml。
 * 周期任务（5 tick）扫描全体在线玩家：当前打开视图的 holder 是 slib 商店<b>主页</b>
 * （反射链 {@code getInventoryRenderer → getSubInventory → isMain}，holder 经
 * {@link #holderOf} 解快照）就确保<b>右下角槽位</b>（{@code size - 1}）放着带 PDC 标记的
 * 忍具商店入口物品；{@code LOWEST} 优先级拦截该点击（slib 点击处理在 NORMAL 且首行判
 * {@code isCancelled()}）→ 取消 + 开 GUI。入口显示为纯幂等状态巡检，无事件会话依赖。
 * BWPlayer 在点击购买瞬间经 API 实查（{@link #bwOf}）。分类子页的原生翻页 / 返回按钮不受影响。</p>
 *
 * <p><b>入口物品模式（{@code page-forward-takeover: false}）</b>：向 shop.yml 追加免费入口块
 * （{@code price: 0 of iron}），点击经 {@link StorePrePurchaseEvent} 取消后开窗。
 * 两种模式下启动时都幂等移除全部插件商店注入块（sword-speed / paper-doll /
 * drifting-paper-doll / shield / lightning / spear / bead / shop-entry）。</p>
 *
 * <p><b>GUI</b>：54 格箱子，条目按 {@link ShopItem#order()} 排序占前部槽位，其余灰玻璃板填充；
 * 每次打开 / 每次购买后按 {@code render} 重新渲染——动态价格与已购状态实时准确。
 * 点击 / 拖拽一律取消（展示物品永不可取走），顶栏点击路由到对应 {@code buy}。</p>
 */
public final class SekiroShopManager {
    private static final String ENTRY_START = "# === SekiroBedwar shop-entry START ===";
    private static final String ENTRY_END = "# === SekiroBedwar shop-entry END ===";
    static final String SLIB_HOLDER_PREFIX = "org.screamingsandals.bedwars.lib.inventories.";

    /** 历史注入块（各类别 START/END 标记对）；sword-speed 外块含内嵌块一并移除。 */
    private static final String[][] LEGACY_BLOCKS = {
            {"# === SekiroBedwar sword-speed START ===", "# === SekiroBedwar sword-speed END ==="},
            {"# === SekiroBedwar paper-doll START ===", "# === SekiroBedwar paper-doll END ==="},
            {"# === SekiroBedwar drifting-paper-doll START ===", "# === SekiroBedwar drifting-paper-doll END ==="},
            {"# === SekiroBedwar shield START ===", "# === SekiroBedwar shield END ==="},
            {"# === SekiroBedwar lightning START ===", "# === SekiroBedwar lightning END ==="},
            {"# === SekiroBedwar spear START ===", "# === SekiroBedwar spear END ==="},
            {"# === SekiroBedwar bead START ===", "# === SekiroBedwar bead END ==="},
    };

    private static final int GUI_SIZE = 54;

    private final SekiroBedwar plugin;
    private final SekiroShopConfig config;
    private final NamespacedKey entryKey;
    private final List<ShopItem> items = new ArrayList<>();

    private BukkitTask patchTask;
    private boolean takeoverActive;

    public SekiroShopManager(SekiroBedwar plugin, SekiroShopConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.entryKey = new NamespacedKey(plugin, "shop_page_entry");
    }

    public void enable() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，忍具商店不启用");
            return;
        }
        cleanupShopFile(bw);
        if (!config.enabled()) {
            plugin.getLogger().info("忍具商店已禁用（shop.enabled=false），历史注入块已清理");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(new SekiroShopListener(this), plugin);
        if (config.pageForwardTakeover()) {
            patchTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::patchShopViews, 1L, 5L);
            takeoverActive = true;
            plugin.getLogger().info("忍具商店已启用：入口=商店主页右下角槽位接管（5tick 巡检），"
                    + "购买判定全部在本插件 GUI 内");
        } else {
            StorePrePurchaseEvent.handle(plugin, this::handleEntry);
            plugin.getLogger().info("忍具商店已启用：入口=shop.yml 入口物品模式（page-forward-takeover=false）");
        }
    }

    public void disable() {
        if (patchTask != null) {
            patchTask.cancel();
            patchTask = null;
        }
        takeoverActive = false;
        items.clear();
    }

    /** 各 manager 在 enable 时登记自家条目（登记顺序无关，按 order 排序展示）。 */
    public void register(ShopItem item) {
        items.add(item);
    }

    // ============ BWPlayer 实时查询（无会话状态） ============

    /** 点击 / 购买瞬间经 BedWars API 查 BWPlayer（查不到返回 null，调用方拒绝购买）。 */
    public BWPlayer bwOf(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return BedwarsAPI.getInstance().getPlayerManager()
                    .getPlayer(player.getUniqueId()).orElse(null);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ============ pageforward 接管（无状态周期巡检） ============

    boolean takeoverActive() {
        return takeoverActive;
    }

    /** 周期任务：任何正在查看 slib 商店主页的玩家，右下角槽位确保放上入口物品（幂等）。 */
    private void patchShopViews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getSize() <= 0 || !isMainShopView(top)) {
                continue;
            }
            int corner = top.getSize() - 1;
            if (isMarked(top.getItem(corner))) {
                continue;
            }
            top.setItem(corner, buildEntryItem());
        }
    }

    /** top 视图的 holder 是否为 slib（重定位后）的 GUI（不做 isMain 判定）。 */
    boolean isSlibView(Inventory top) {
        Object holder = holderOf(top);
        return holder != null && holder.getClass().getName().startsWith(SLIB_HOLDER_PREFIX);
    }

    /** 点击的槽位物品是否自家入口标记。 */
    boolean isMarked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(entryKey, PersistentDataType.BYTE);
    }

    /** 商店主页入口物品（PDC 标记 + 配置材质/名称/lore）。 */
    private ItemStack buildEntryItem() {
        ItemStack item = new ItemStack(config.entryMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§d" + config.entryName());
        List<String> lore = new ArrayList<>(config.entryLore());
        lore.add("§e▶ 点击进入忍具商店");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(entryKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * top 视图是否 slib 商店的「主页」（分类列表页）：holder 为重定位 slib 类（按类名前缀判定，
     * 不链接 BedWars 内部类——插件 classloader 隔离）且 {@code SubInventory.isMain()} 为真。
     * 只有主页右下角被接管，分类子页的原生翻页 / 返回按钮不受影响。
     */
    boolean isMainShopView(Inventory top) {
        Object holder = holderOf(top);
        if (holder == null || !holder.getClass().getName().startsWith(SLIB_HOLDER_PREFIX)) {
            return false;
        }
        try {
            Object renderer = holder.getClass().getMethod("getInventoryRenderer").invoke(holder);
            if (renderer == null) {
                return false;
            }
            Object sub = renderer.getClass().getMethod("getSubInventory").invoke(renderer);
            if (sub == null) {
                return false;
            }
            return Boolean.TRUE.equals(sub.getClass().getMethod("isMain").invoke(sub));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    // ---- 真实 holder 解析（1.21.x 关键坑）----
    private static Method getHolderWithoutSnapshot;
    private static boolean getHolderResolved;

    /**
     * spigot 1.21.4+ 的 {@code Inventory.getHolder()} 可能返回 <b>snapshot 包装</b>（非真实 holder，
     * 类名不带 slib 前缀 → instanceof / 前缀判定全部失效）。slib 自己经
     * {@code InventoryUtils.getInventoryHolderWithoutSnapshot} 用 {@code getHolder(false)} 规避——
     * 本插件同样反射走 {@code getHolder(false)}，老 API 无此重载时回退默认实现。
     */
    static InventoryHolder holderOf(Inventory inventory) {
        if (!getHolderResolved) {
            try {
                getHolderWithoutSnapshot = Inventory.class.getMethod("getHolder", boolean.class);
            } catch (NoSuchMethodException ignored) {
                getHolderWithoutSnapshot = null;
            }
            getHolderResolved = true;
        }
        if (getHolderWithoutSnapshot != null) {
            try {
                return (InventoryHolder) getHolderWithoutSnapshot.invoke(inventory, false);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 回退默认实现
            }
        }
        return inventory.getHolder();
    }

    // ============ 入口拦截（回退模式：shop.yml 入口物品） ============

    private void handleEntry(StorePrePurchaseEvent ev) {
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
        if (bought == null || bought.getType() == Material.AIR) {
            return;
        }
        String name = bought.hasItemMeta() && bought.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(bought.getItemMeta().getDisplayName()) : "";
        if (!name.contains(ChatColor.stripColor(config.entryName()))) {
            return; // 非入口按钮
        }
        ev.setCancelled(true); // 免费项也须取消：不给物品不扣费
        final UUID uuid = ev.getPlayer().getUuid();
        // 事件回调内同步开窗有客户端 desync 坑 → 下一 tick 打开
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                open(p);
            }
        });
    }

    // ============ GUI 构建 ============

    /** 为玩家现建并打开忍具商店 GUI（每次打开全新渲染）。 */
    public void open(Player player) {
        Map<Integer, ShopItem> slots = new LinkedHashMap<>();
        ItemStack[] contents = new ItemStack[GUI_SIZE];
        List<ShopItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(ShopItem::order));
        int slot = 0;
        for (ShopItem item : sorted) {
            if (slot >= GUI_SIZE) {
                plugin.getLogger().warning("忍具商店条目超过 " + GUI_SIZE + " 个，超出部分未展示: " + item.id());
                break;
            }
            contents[slot] = safeRender(item, player);
            slots.put(slot, item);
            slot++;
        }
        ItemStack filler = filler();
        for (int i = slot; i < GUI_SIZE; i++) {
            contents[i] = filler;
        }
        SekiroShopHolder holder = new SekiroShopHolder(slots);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, config.guiTitle());
        holder.setInventory(inventory);
        inventory.setContents(contents);
        player.openInventory(inventory);
    }

    /** 购买后刷新当前 GUI 的渲染（状态 / 动态价格即时更新）。 */
    public void refresh(Player player) {
        if (!(holderOf(player.getOpenInventory().getTopInventory()) instanceof SekiroShopHolder holder)) {
            return;
        }
        for (Map.Entry<Integer, ShopItem> entry : holder.slots().entrySet()) {
            holder.getInventory().setItem(entry.getKey(), safeRender(entry.getValue(), player));
        }
    }

    /** 渲染异常兜底：坏一项不炸整窗。 */
    private ItemStack safeRender(ShopItem item, Player player) {
        try {
            ItemStack rendered = item.render().apply(player);
            return rendered == null ? filler() : rendered;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("忍具商店条目渲染失败 " + item.id() + ": " + ex.getMessage());
            return filler();
        }
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName("§r");
        pane.setItemMeta(meta);
        return pane;
    }

    /** 供各 manager 渲染条目复用的物品构造：材质 + 显示名 + lore（价格行等由调用方拼好）。 */
    public static ItemStack icon(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        meta.setLore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

    // ============ shop.yml 清理与入口注入（仅回退模式） ============

    /**
     * 幂等重写 shop.yml：移除旧入口块 + 全部历史注入块（takeover 模式下连入口物品都不注入、
     * shop.yml 只剩原版内容；shop.enabled=false 时也执行清理，避免无拦截器的孤儿商店项被
     * BedWars 原生流程以静态价卖出去）。
     */
    private void cleanupShopFile(Plugin bw) {
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            plugin.getLogger().info("未找到商店文件 " + shopFile.getAbsolutePath()
                    + "，跳过忍具商店入口注入 / 历史块清理");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            content = removeBlock(content, ENTRY_START, ENTRY_END);
            for (String[] pair : LEGACY_BLOCKS) {
                content = removeBlock(content, pair[0], pair[1]);
            }
            boolean injectEntry = config.enabled() && !config.pageForwardTakeover();
            if (injectEntry) {
                if (!content.endsWith("\n")) {
                    content += "\n";
                }
                content += buildEntryBlock();
            }
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("shop.yml 历史注入块已清理" + (injectEntry ? "，忍具商店入口物品已注入: " : ": ")
                    + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("shop.yml 清理/注入失败: " + ex.getMessage());
        }
    }

    /** 移除 start~end 标记块（含标记行，跨到 end 行尾）；不存在则原样返回。 */
    private static String removeBlock(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(endMarker, start);
        if (end < 0) {
            return content;
        }
        int endLine = content.indexOf('\n', end);
        endLine = endLine < 0 ? content.length() : endLine + 1;
        return content.substring(0, start) + content.substring(endLine);
    }

    /** 入口块（回退模式）：顶层 data 项，pagebreak 独立页，静态价 0（恒过预检）。 */
    private String buildEntryBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append(ENTRY_START).append('\n');
        sb.append("  - price: 0 of iron\n");
        sb.append("    pagebreak: before\n");
        sb.append("    stack:\n");
        sb.append("      type: ").append(config.entryMaterial().name().toLowerCase()).append('\n');
        sb.append("      display-name: \"").append(yamlEscape(config.entryName())).append("\"\n");
        sb.append("      lore:\n");
        for (String line : config.entryLore()) {
            sb.append("        - \"").append(yamlEscape(line)).append("\"\n");
        }
        sb.append(ENTRY_END).append('\n');
        return sb.toString();
    }

    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
