package org.alpha.sekiroBedwar.shop;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 忍具商店 GUI 的一条登记项：由各 manager 在 enable 时构造并注册到 {@link SekiroShopManager}。
 *
 * <p>{@code render} 每次打开 / 购买后重新调用——动态价格（纸人按存活队伍数）、已购等级、
 * 持有数等状态实时呈现；{@code buy} 是完整购买流程（门槛 → 扣费 → 发放 → 消息），
 * 全部在插件内执行，不经 BedWars 商店流程。</p>
 */
public final class ShopItem {
    private final String id;
    private final int order;
    private final Function<Player, ItemStack> render;
    private final Consumer<BuyContext> buy;

    public ShopItem(String id, int order, Function<Player, ItemStack> render, Consumer<BuyContext> buy) {
        this.id = id;
        this.order = order;
        this.render = render;
        this.buy = buy;
    }

    public String id() {
        return id;
    }

    /** GUI 槽位排序键（小的在前；同 manager 的连续项用十位分组，如攻速 11/12/13）。 */
    public int order() {
        return order;
    }

    public Function<Player, ItemStack> render() {
        return render;
    }

    public Consumer<BuyContext> buy() {
        return buy;
    }
}
