package org.alpha.sekiroBedwar.welcome;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.List;
import java.util.UUID;

/**
 * 玩法指南书（独立模块）：BedWars 大厅发放、进对局收回。
 *
 * <p>玩家<b>进入服务器落到大厅</b>（Bukkit {@code PlayerJoinEvent}，延迟 1 tick）或
 * <b>离开对局回到大厅</b>（BedWars {@code PlayerLeaveEvent}）时，若背包中还没有指南书，
 * 发放一本成书（WRITTEN_BOOK，10 页玩法介绍，PDC 标记 {@code welcome_book}）；
 * <b>进入对局</b>（BedWars 进局事件）时按标记精确收回（只清自带这本书，不动其他物品）。
 * 书籍内容见 {@link #PAGES}；总开关 {@code welcome.enabled}。</p>
 */
public final class WelcomeManager implements Listener {

    /** 10 页玩法指南（成书正文）。 */
    private static final List<String> PAGES = List.of(
            """
            §l§9第一页｜起床战争§r

            欢迎来到起床战争。

            你的目标很简单：

            §l收集资源、购买装备、保护自己的床，并摧毁其他队伍的床。§r

            床存在时，死亡后可以重新复活。

            床被摧毁后，死亡将无法再次复活。

            除了传统的起床战争，本服务器加入了全新的§l架势战斗系统§r。""",
            """
            §l§9第二页｜资源与商店§r

            岛屿会不断生成资源。

            不同资源拥有不同价值，可以用于购买：

            • 武器与装备
            • 方块
            • 弓箭
            • 药水
            • 忍具
            • 其他强化物品

            部分资源还会影响你的§l架势上限§r。

            因此，资源不仅是购物的货币，也是战斗中的力量。

            §l带着更多资源战斗，也意味着承担更大的风险。§r""",
            """
            §l§9第三页｜架势§r

            除了生命值，你还有一条§l架势条§r。

            攻击与防御都会影响架势。

            当架势进入危险状态后，你更容易被击溃。

            架势崩溃后，会产生短暂的§l处决机会§r。

            决斗中，架势崩溃还可能导致部分资源转移。

            所以战斗并不只是：

            §l“把对方的血打空。”§r

            你也可以通过控制对方的架势赢得战斗。""",
            """
            §l§9第四页｜格挡与弹反§r

            持盾可以进行格挡。

            在正确时机进行防御，可以完成§l完美弹反§r。

            完美弹反能够：

            • 化解攻击；
            • 给对手造成架势压力；
            • 打断部分攻击节奏。

            部分特殊攻击无法被普通弹反。

            §l观察攻击节奏，比一直举盾更加重要。§r""",
            """
            §l§9第五页｜危与识破§r

            部分攻击会出现：

            §c「危」§r

            危险攻击无法使用普通的完美弹反处理。

            面对不同类型的危险攻击，需要使用不同的应对方式。

            其中：

            §l识破§r

            可以反制部分危险攻击，并获得战斗优势。

            看到「危」时，不要只想着举盾。""",
            """
            §l§9第六页｜决斗§r

            当两名玩家在合适区域内互相攻击，且附近没有其他玩家干扰时，可能触发§l决斗§r。

            决斗期间：

            • 双方进入独立的战斗区域；
            • 部分资源刷新会暂停并暂存；
            • 无法使用方块主动逃离；
            • 无法使用末影珍珠主动逃离；
            • 其他玩家进入可能使决斗中断；
            • 被击入虚空仍然会死亡。

            决斗结束后，根据结果进行资源结算。

            §l决斗的胜负，不只是生命值的较量。§r""",
            """
            §l§9第七页｜忍具§r

            商店中可以购买特殊的§l忍具§r。

            忍具拥有不同的战斗用途。

            有的可以：

            • 改变攻击节奏；
            • 控制距离；
            • 干扰视野；
            • 创造攻击机会；
            • 改变自身状态。

            例如，部分忍具可以创造短暂的视野屏障。

            §l忍具不一定直接造成伤害。§r

            有时候，改变对手能够看到什么，本身就是一种攻击。""",
            """
            §l§9第八页｜秘传§r

            战斗中存在特殊的§l秘传招式§r。

            每种秘传都有自己的触发条件、攻击节奏和特殊效果。

            目前可以研究：

            §b秘传·飞渡浮舟§r

            连续攻击并按照特定节奏衔接，可以完成特殊的连续攻击。

            §b秘传·苇名十字斩§r

            满足空手蓄势条件后重新拔出近战武器，并在短时间内完成连续攻击。

            更多秘传将在后续开放。

            成功完成秘传可以获得额外的战斗收益。""",
            """
            §l§9第九页｜连段与派生§r

            秘传并不是孤立存在的。

            不同攻击、秘传、忍具和状态之间可能产生§l派生§r。

            例如：

            一个招式留下的攻击窗口，

            可能成为另一个招式的机会。

            但并不存在一套必须照着使用的固定连招。

            你可以：

            §l继续攻击。§r

            也可以：

            §l主动收手，重新寻找节奏。§r

            甚至可以利用忍具改变原本的攻击方式。""",
            """
            §l§9第十页｜战斗的本质§r

            这里没有唯一的“正确连招”。

            你需要自己判断：

            §l什么时候攻击？§r

            §l什么时候弹反？§r

            §l什么时候使用秘传？§r

            §l什么时候继续连段？§r

            §l什么时候应该停手？§r

            §l什么时候值得拿资源冒险？§r

            你可以研究招式，也可以研究对手。

            §l规则是固定的，打法由你创造。§r

            祝你找到属于自己的战斗方式。"""
    );

    private final SekiroBedwar plugin;
    private final WelcomeConfig config;
    private final NamespacedKey bookKey;

    public WelcomeManager(SekiroBedwar plugin, WelcomeConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.bookKey = new NamespacedKey(plugin, "welcome_book");
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> {
            Player p = Bukkit.getPlayer(ev.getPlayer().getUuid());
            if (p != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> grant(p), 1L);
            }
        });
        org.screamingsandals.bedwars.api.events.PlayerJoinedEvent.handle(
                plugin, ev -> Bukkit.getScheduler().runTask(plugin, () -> remove(ev.getPlayer().getUuid())));
        plugin.getLogger().info("玩法指南书已启用：大厅发放，进对局收回");
    }

    public void disable() {
        // 无可变状态
    }

    /** 服务器进入（落大厅）：延迟 1 tick 等出生 / 背包初始化完成后发放。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> grant(player));
    }

    /** 背包已有指南书则跳过；否则发放一本（满包掉脚下）。 */
    private void grant(Player player) {
        if (!config.enabled() || !player.isOnline()) {
            return;
        }
        if (hasBook(player)) {
            return;
        }
        ItemStack book = buildBook();
        if (!player.getInventory().addItem(book).isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), book);
        }
    }

    /** 进对局：按 PDC 标记收回指南书（只清本书）。 */
    private void remove(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isWelcomeBook(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private boolean hasBook(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isWelcomeBook(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWelcomeBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    private ItemStack buildBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta raw = book.getItemMeta();
        // Spigot API 无 Paper 的 WrittenBookMeta；成书的标题 / 作者 / 页在 BookMeta 上
        if (raw instanceof BookMeta meta) {
            meta.setTitle(config.name());
            meta.setAuthor("SekiroBedwar");
            meta.setPages(PAGES.toArray(new String[0]));
            meta.setDisplayName("§d" + config.name());
            meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        return book;
    }
}
