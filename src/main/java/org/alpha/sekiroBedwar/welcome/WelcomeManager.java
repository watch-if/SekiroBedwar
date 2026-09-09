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
 * 发放一本成书（WRITTEN_BOOK，18 页玩法介绍，PDC 标记 {@code welcome_book}）；
 * <b>进入对局</b>（BedWars 进局事件）时按标记精确收回（只清自带这本书，不动其他物品）。
 * 排版按成书约束：每页 ≤ 约 13 行、单行 ≤ 8 个汉字（中文每行可视约 8-9 字），
 * 一页一主题。内容见 {@link #PAGES}；总开关 {@code welcome.enabled}。</p>
 */
public final class WelcomeManager implements Listener {

    /** 18 页玩法指南（成书正文；每页一主题、控制在单页可视区内）。 */
    private static final List<String> PAGES = List.of(
            """
            §l§9只狼 · 起床战争§r

            欢迎来到起床战争。

            §l守住自己的床，
            拆掉敌人的床。§r

            床在，死亡可以复活；
            床破，死亡即是终结。

            这里还有一套只狼式的
            §l架势战斗系统§r——
            翻到下一页开始。""",
            """
            §l§9资源 = 力量§r

            岛上不断刷出：
            铁 → 金 → 钻石 → 绿宝石

            它们既是购物货币，
            也决定你的§l架势上限§r。

            §c带得越多，越诱人。§r
            击杀你的一切会掉落结算——
            富有本身就是风险。""",
            """
            §l§9架势条§r

            除了血量，你还有一条
            §l架势§r（经验条显示）。

            被击中、格挡都会扣架势；
            弹反则扣§b对手§r的架势。

            架势见底 = §c临界§r
            再吃一发近战就会§l崩溃§r。

            杀掉对手不一定要打空血。""",
            """
            §l§9崩条与处决§r

            架势崩溃后你会短暂
            §c硬直：无法格挡§r。

            对手获得处决窗口——
            窗口内被杀：重罚；
            逃出台阶：轻罚。

            崩了≠死：
            §l拉开距离、绕出白圈§r，
            处决者拿不到满额。""",
            """
            §l§9格挡与弹反§r

            右键持盾：

            §l• §r普通格挡：免伤，
              但仍掉一点架势
            §l• §r完美弹反：举盾后
              §b0.17 秒§r内挡下 = 无伤
              + 重创对手架势
            §l• §r斧头劈中格挡 = 破盾

            看节奏，别死举盾。""",
            """
            §l§9「危」与识破§r

            §c「危」§r攻击（突进矛+疾跑）：
            无法弹反，格挡会破盾。

            应对：§l识破§r——
            下蹲后 0.17 秒内被「危」
            命中 = 免疫 + 反削架势。

            见危不举盾，蹲下反它。""",
            """
            §l§9决斗§r

            两个敌人在孤立区域
            互殴 → 触发§l决斗§r
            （红白双圈亮起）。

            圈内：
            • 物资刷新暂存
            • 不能搭路 / 珍珠逃跑
            • 闲人进入 → 决斗作废
            • 摔落虚空 = 死亡

            这是勇者的擂台。""",
            """
            §l§9决斗结算§r

            决斗中死亡§l不掉任何东西§r：

            • 崩条处决 → 按比例转移
            • 击杀 / 虚空 → 全额转移
            • 闲人搅局 → 双方回滚

            输了也会「送」对方资源，
            §l残血时别贪刀§r——
            读局势决定打还是逃。""",
            """
            §l§9节奏的语言§r

            决斗中没有原版无敌帧：
            你的每一刀都实打实。

            招式按§l节拍§r判定——

            接上段落 = §b铁砧落地§r
            脱拍     = §c铁砧打磨§r

            听到连串「哐当」，
            你就在连招之中。""",
            """
            §l§9忍具商店§r

            打开 BedWars 商店，
            右下角 §d只狼忍具§r 入口。

            攻速、雷、火、毒、矛、
            纸人、雾鸦、风弹、佛珠…
            都在这一页页里。

            资源即货币，价格随局势
            实时浮动。""",
            """
            §l§9纸人§r

            忍具的通用弹药（上限20）：

            • 投掷任何投掷物抵 1 个
            • 投掷命中后左键：耗 1
              传送至目标身边
            • 举盾右键耗 2：2 秒
              §l自动完美弹反§r
            • 龙闪 2 个、落雷 4 个""",
            """
            §l§9雾璃鸦 & 风弹§r

            §d雾璃鸦§r：右键激活，眼
            悬顶 2 秒——期间第一次
            玩家伤害§l免伤§r并传送至
            攻击方身后。死了会补发。

            §b风弹§r：掷出瞬间面前掀起
            半椭圆爆风墙，撞上手脚
            的玩家 §c0.5 秒§r不能攻
            防。开路、拆节奏皆宜。""",
            """
            §l§9雷与三系§r

            §b巴之雷§r：三连击后接
            跳斩 = 落雷；空中接雷
            反击 = §l雷反§r。

            三选一专精（可「还原」重选）：
            • 巴之雷——爆发
            • 锈丸——中毒凋零
            • 炎上——点火附燃

            选一路，走到底。""",
            """
            §l§9装备速览§r

            • §b下界合金长矛§r：附魔
              突进，疾跑刺 = 「危」
            • §b剑攻速§r：三级渐购
            • 剑也能右键格挡（新版本）
            • §b护甲套装§r：一键即穿
            • §b佛珠§r：一颗 +5 血量
            • 僵尸头颅：杀敌掉落，
              可布恐惧领域""",
            """
            §l§9秘传·飞渡浮舟§r

            七刀节奏（tick）：
            §b7.3 10 5.7 5.3 5.7 16§r
            每刀容差 ±0.2

            第 3 刀起：落地音 +
            §l1 秒防击退§r（刷新）
            第 6 刀命中：额外削架势
            七刀全连：纸人+架势+HP""",
            """
            §l§9秘传·苇名十字斩§r

            §l空手§r 0.5~1 秒后换刀，
            换刀瞬间§b立刻§r出刀：

            两刀间隔 4 tick ±0.5
            第二刀命中 = §l击退§r +
            削对手 7 架势、回 3 自己

            拔刀术：犹豫即断。""",
            """
            §l§9秘传·龙闪§r

            §l空手§r超过 1 秒后换刀，
            换刀瞬间§b左键§r：

            耗 2 纸人，朝面前轰出
            音波柱（向上 4 格高），
            撞上的人挨打+削架势，
            飞到白圈消散。

            一秒后同方向§l再轰一发§r。""",
            """
            §l§9秘传·一心七连§r

            七刀节奏（tick）：
            §b7 5 5 6 8 10§r ±0.2

            第 3 刀起：落地音+防击退；
            有效命中逐刀追加架势伤
            （3→6→9→12 叠加）。

            §c第七刀必须是「危」§r
            （矛+突进+疾跑）——
            全连成：2 纸人 + 回架势""",
            """
            §l§9战斗的本质§r

            这里没有标准连招。

            何时进攻、何时弹反、
            何时开秘传、何时停手、
            何时值得带资源冒险——

            都由你判断。

            §l规则是固定的，
            打法由你创造。§r

            愿你找到一击必杀的节拍。"""
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
