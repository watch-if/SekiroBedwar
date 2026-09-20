package org.alpha.sekiroBedwar.welcome;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩法指南书（独立模块）：BedWars 大厅发放、进对局收回。
 *
 * <p>玩家<b>进入服务器落到大厅</b>（Bukkit {@code PlayerJoinEvent}，延迟 1 tick）或
 * <b>离开对局回到大厅</b>（BedWars {@code PlayerLeaveEvent}）时，若背包中还没有指南书，
 * 发放一本成书（WRITTEN_BOOK，18 页，PDC 标记 {@code welcome_book}）；
 * <b>进入对局</b>（BedWars 进局事件）时按标记精确收回（只清自带这本书，不动其他物品）。</p>
 *
 * <p><b>叙事与结构（2026-09-20 重写）</b>：全书走只狼口吻，前 2 页立「这不只是床战」的特色，
 * 3-7 页教核心机制（架势 / 崩条 / 防御 / 危与识破），8-12 页讲忍具与经济，
 * 13-17 页讲秘传与节奏，18 页集中放深数据（参数 / 数值速查）——玩法页只讲「怎么打、
 * 为何值得」，精确参数不散落在玩法页里。</p>
 *
 * <p><b>排版</b>：内容见 {@link #PAGES}（每页一段文本块，块内换行即页内换行）；渲染走
 * {@link #buildPage(String)} → JSON 文本组件，<b>每一行单独成一个 run</b>——这样换行必定生效、
 * 且每行可独立加粗 / 配色（纯字符串靠 {@code \n} 在部分版本不换行）。行宽按成书可视区控制：
 * 单行 ≤ 约 8 个汉字，一页 ≤ 13 行。</p>
 */
public final class WelcomeManager implements Listener {

    /** 18 页玩法指南（只狼口吻；每页一主题，行宽 ≤ 约 8 汉字、≤ 13 行）。 */
    private static final List<String> PAGES = List.of(
            """
            §l§9只狼 · 起床战争
            §8────────
            §7这里是起床战争。
            §7但它多了一条规则：
            §f除了血，你还有架势。
            §8
            §4架势一崩，你就可能
            §4被一刀处决。
            §8
            §7床在，死可复生；
            §4床破，此局即终。
            翻页，我教你杀人。""",
            """
            §l§9这不只是床战
            §8────────
            §7别人拼血，你拼节奏。
            §7攻防之间还有一层：
            §b弹反 §7· §c危 §7· §e识破
            §8
            §7打空血不是唯一的赢法，
            §c削空架势 §7一样能赢。
            §8
            §7每一刀都可以被读、
            §7被反、被惩罚。
            §f这一局，你要读人。""",
            """
            §l§9资源即架势
            §8────────
            §7铁金银钻不只是货币，
            §7它们是§f你的架势上限§7。
            §8
            §7背得越肥，架势条越长；
            §c但被杀时掉得越狠。§7
            §8
            §7这是本插件最核心的
            §7一张赌桌：
            §f富有，本身就是风险。""",
            """
            §l§9架势 = 第二条命
            §8────────
            §7经验条 = 你的架势。
            §7挨打、格挡都会掉。
            §8
            §7回得慢，除非闲着：
            §7越接近满，回得越快；
            §7空槽时像在爬。
            §8
            §c中毒、着火 §7会打断恢复，
            §7别指望拖着回血。""",
            """
            §l§9崩条与处决
            §8────────
            §7架势见底 = §c临界§7。
            §7此时再挨一发命中——
            §4你的架势就崩了。§7
            §8
            §7箭也崩，近战也崩。
            §7崩了会硬直，举不起盾。
            §7对手进入 §c处决窗口§7：
            §7窗口内被杀，掉一半家当；
            §7扛到窗口结束，只掉一半。
            §8
            §f崩了不是死，是只剩
            §f最后一次机会。""",
            """
            §l§9防御：格挡与弹反
            §8────────
            §7右键持盾：
            §8
            §f普通格挡 §7— 免伤，
            §7但仍掉一点架势。
            §f完美弹反 §7— 举盾后
            §b0.17 秒§7 内挡下 = 无伤
            §7+ §b重创对手架势§7。
            §f盾牌弹反 §7— 举盾扣
            §72 纸人：2 秒内全弹开。
            §8
            §c斧头劈中格挡 = 破盾。""",
            """
            §l§9危与识破 · 弓
            §8────────
            §4「危」§7= 突进矛 + 疾跑。
            §7它不能被弹反；
            §7你举盾，就是被破盾。
            §7应对只有一条：
            §e下蹲 §7后 §b0.17 秒§7 内
            §7被「危」打中 = §e识破§7，
            §7免伤，反削对手架势。
            §8
            §f箭可被完美弹反§7；
            §7你也快到临界时，
            §b射箭不被罚§7，尽管压制。""",
            """
            §l§9忍具商店
            §8────────
            §7打开 BedWars 商店，
            §7右下角 §d「只狼忍具」§7。
            §8
            §7长矛 · 剑攻速 · 三级护甲
            §7巴之雷 · 锈丸 · 炎上
            §7纸人 · 雾璃鸦 · 风弹
            §7佛珠 · 糖 · 踩头
            §8
            §7资源就是钱，
            §f价格随局势实时浮动。""",
            """
            §l§9纸人 = 忍具弹药
            §8────────
            §7上限 20，绑定你。
            §8
            §7· 投掷物抵 1 个
            §7· 命中后左键：耗 1
            §7  传送到目标身边
            §7· 举盾耗 2：2 秒全弹反
            §7· 龙闪 2 个 / 落雷 4 个
            §8
            §7手里只剩最后 1 个时，
            §7耗 1 纸人、还你 1 个。""",
            """
            §l§9雾璃鸦 · 风弹
            §8────────
            §d雾璃鸦§7：右键激活，
            §7悬顶 2 秒——这期间
            §7第一次玩家伤害 §f免掉§7，
            §7并把你送到对方身后。
            §8
            §b风弹§7：掷出瞬间掀起
            §7爆风墙，碰到的人
            §c0.5 秒不能攻防§7。
            开路、拆节奏皆宜。""",
            """
            §l§9夜叉戮糖
            §8────────
            §7吃一口，代价换伤害：
            §8
            §7造成架势伤 §b×1.5
            §7力量 II 三十秒
            §7§c但血与架势上限减半
            §8
            §7结束不回血，
            §7只把躯干补回一半。
            §8
            §f赌一把，还是稳一手？""",
            """
            §l§9踩头 · 重锤风暴
            §8────────
            §7滞空时贴到对手身上，
            §7按 §b空格§7：借力弹走。
            §8
            §7谁更低谁被踩；
            §7下落越高，风爆越猛，
            §7超过 1.5 格还挨 1 点。
            §8
            §7§c但对方弹反了，
            §c你就被打回去。§7
            用来跑位，或者赌命。""",
            """
            §l§9秘传 · 节奏的语言
            §8────────
            §7决斗中§f没有无敌帧§7，
            §7每一刀都实打实。
            §8
            §7招式按 §b节拍（tick）§7判：
            §b铁砧落地声 §7= 接上了
            §c铁砧打磨声 §7= 脱拍了
            §8
            §7听到连串「哐当」，
            §f你正走在杀招里。""",
            """
            §l§9秘传·壹 飞渡浮舟
            §8────────
            §7七段连击（拍）：
            §b7 10 6 5 6 16
            §8
            §7第六击有效：再削架势
            §7第三击起：落地音 +
            §f1 秒防击退
            §7七击全成：§b2 纸人
            §b+架势 +1 血
            §8
            §7长间隔可以格挡、投掷。""",
            """
            §l§9秘传·贰 苇名十字斩
            §8────────
            §7先 §f空手半秒以上§7，
            §7再换刀——两刀间隔 4 拍。
            §8
            §7第二刀命中：
            §f击退 §7+ 削对方架势 7
            §7回自身架势 3
            §8
            §7被弹反或脱拍 = 白打，
            §7得重新空手起手。
            §c拔刀术：犹豫即断。""",
            """
            §l§9秘传·叁 龙闪
            §8────────
            §7§f空手两秒以上§7换刀，
            §7换刀瞬间 §b左键§7：
            §8
            §7耗 2 纸人，朝面前
            §7轰出音波柱（高 4 格），
            §7命中 §f2 血 §7+ §f10 架势
            §7飞出白圈才消散。
            §8
            §7一秒后，同方向 §b再一发§7。""",
            """
            §l§9秘传·肆 一心七连
            §8────────
            §7七段（拍）：
            §b7 5 5 6 8 10
            §8
            §7第三击起：落地音 +
            §f1 秒防击退
            §7有效命中逐段加伤：
            §b3 → 6 → 9 → 12
            §8
            §c第七击必须打「危」，
            §7才算完成全段。""",
            """
            §l§9参数速查
            §8────────
            §7弹反窗口 §b170ms
            §7识破窗口 §b170ms
            §7危格挡破盾 §b1 秒
            §7斧破盾 §b3 秒禁格挡
            §8
            §7浮舟 §b7/10/6/5/6/16
            §7一心 §b7/5/5/6/8/10
            §7苇名 §b4 拍
            §7换刀衔接 §b4 拍
            §8
            §7踩头碰撞箱 §b0.8 格
            §7风爆分层 §b1.5 格"""
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
            List<String> pages = new ArrayList<>(PAGES.size());
            for (String page : PAGES) {
                pages.add(buildPage(page));
            }
            meta.setPages(pages);
            meta.setDisplayName("§d" + config.name());
            meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        return book;
    }

    /**
     * 把一页纯文本（{@code §} 颜色码 + 换行）转成 JSON 文本组件：
     * <b>每一行一个 run + 一个显式换行 run</b>。
     *
     * <p>为什么要走组件：纯字符串页里的 {@code \n} 在部分版本不换行、且整页共用一套样式；
     * 逐行成 run 后换行必定生效，每行还能独立加粗 / 配色。行内容仍用 {@code §} 码书写，
     * 这里交给 {@link TextComponent#fromLegacyText(String)} 解析（不依赖 minecraft 版本名解析）。</p>
     *
     * <p><b>注意</b>：每行的 {@code extra} 必须<b>恒为数组</b>——直接把单个
     * {@code BaseComponent} 塞进父组件再序列化时，Gson 会把单元素写成
     * {@code "extra":{...}}（对象而非数组）导致解析失败。所以这里先取整行的
     * {@code ComponentSerializer.toString(parts)}（本身就是一个 JSON 数组）再原样嵌入。</p>
     *
     * <p><b>兜底</b>：万一 JSON 生成失败，直接退化为原始纯文本页（仍有颜色码与换行）。</p>
     */
    private static String buildPage(String page) {
        try {
            StringBuilder sb = new StringBuilder("[\"\"");
            for (String raw : page.split("\n", -1)) {
                String line = raw.stripTrailing();
                String runs = ComponentSerializer.toString(TextComponent.fromLegacyText(line));
                sb.append(",{\"text\":\"\",\"extra\":").append(runs).append("}");
                sb.append(",{\"text\":\"\\n\"}");
            }
            sb.append(']');
            return sb.toString();
        } catch (RuntimeException ex) {
            return page;
        }
    }
}
