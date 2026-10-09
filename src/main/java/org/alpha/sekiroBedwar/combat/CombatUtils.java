package org.alpha.sekiroBedwar.combat;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;

/**
 * 伤害来源解析工具。
 *
 * <p>普通格挡 / 受击架势模块需要覆盖<b>弓箭</b>（投射物）与近战；完美弹反模块按设计
 * 仅接受<b>近战直接命中</b>（弓箭不参与弹反，按普通格挡处理）。本类把两种解析集中到一处，
 * 避免各模块重复且互不一致的判断。</p>
 *
 * <p>同时提供<b>武器基础伤害 Dbase</b>（= 所用武器数值面板上的伤害）的读取：
 * 近战 = 玩家攻击力属性基础值（默认 1.0）+ 主手物品 {@code ATTACK_DAMAGE} 修正（面板数值）；
 * 投射物（弓箭）无面板，退化为事件基础伤害。普通格挡 / 完美弹反的架势换算均以 Dbase 计。</p>
 */
public final class CombatUtils {
    private CombatUtils() {
    }

    /**
     * 解析攻击方玩家：近战直接命中，或投射物（弓箭/雪球等）的射击者。
     *
     * @return 攻击方玩家；非玩家来源（环境 / 怪物 / 第三方非玩家等）返回 null
     */
    public static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * 解析<b>近战</b>攻击方玩家（完美弹反专用：投射物不参与弹反，返回 null）。
     *
     * @return 近战攻击方玩家；投射物 / 非玩家来源返回 null
     */
    public static Player resolveMeleeAttacker(EntityDamageByEntityEvent event) {
        return event.getDamager() instanceof Player player ? player : null;
    }

    /**
     * 武器基础伤害 Dbase：
     * <ul>
     *   <li><b>近战</b>：玩家攻击力属性基础值（默认 1.0）+ 主手物品 {@code ATTACK_DAMAGE}
     *       修正总和 = 该武器的面板伤害（如钻石剑 1+6=7）；</li>
     *   <li><b>投射物</b>（弓箭）：无面板数值，退化为事件基础伤害（弓箭的实际基础伤害）。</li>
     * </ul>
     */
    public static double baseDamage(Player attacker, EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            return meleePanelDamage(attacker);
        }
        return event.getDamage();
    }

    /** 近战面板伤害 = 攻击力属性基础值 + 主手物品 ATTACK_DAMAGE 修正（含空手 = 基础值）。 */
    private static double meleePanelDamage(Player attacker) {
        double dmg = 1.0;
        Attribute attr = attackDamageAttribute();
        if (attr == null) {
            return dmg;
        }
        AttributeInstance ai = attacker.getAttribute(attr);
        if (ai != null) {
            dmg = ai.getBaseValue();
        }
        ItemStack item = attacker.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasAttributeModifiers()) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(attr);
            if (mods != null) {
                for (AttributeModifier mod : mods) {
                    dmg += mod.getAmount();
                }
            }
        }
        return dmg;
    }

    /**
     * 攻击力属性常量：paper-api 26.2 为 {@code ATTACK_DAMAGE}（新版命名），
     * spigot-api 1.21.1 为 {@code GENERIC_ATTACK_DAMAGE}（旧命名，{@code GENERIC_} 前缀）。
     * 用 {@code Attribute.valueOf} 按名探测，两个编译目标 / 运行环境都能解析，避免硬编码某个 jar 的名字。
     *
     * <p>注：paper-api 26.2 中 {@code valueOf} 已标记 {@code forRemoval}（枚举正被 registry 取代），
     * 这里用 {@code @SuppressWarnings("removal")} 局部压制——属性枚举本身（含 {@code ATTACK_DAMAGE}
     * 常量）仍然存在，运行时不受影响；将来若被移除，本方法返回 null，{@link #baseDamage} 退化为事件伤害。</p>
     *
     * @return 解析到的属性；两者都无（极端情况）返回 null
     */
    @SuppressWarnings("removal")
    private static Attribute attackDamageAttribute() {
        for (String name : new String[]{"ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"}) {
            try {
                return Attribute.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 该名字在当前 API 中不存在，尝试下一个
            }
        }
        return null;
    }

    /**
     * <b>让目标「举不起盾」</b>：把主手与副手的<b>盾牌与剑</b>逐件打入冷却。
     *
     * <p><b>为什么必须逐件、且必须包含剑</b>：本插件给剑追加了 {@code minecraft:blocks_attacks}
     * 组件（{@code SwordBlockingManager}），剑与盾<b>走的是同一套原版格挡机制</b>
     * （{@code Player.isBlocking()} 为真）。而 {@code setCooldown} 是<b>按材质</b>生效的 ——
     * 只冷却 {@code Material.SHIELD} 对剑完全无效，于是"破盾"期间剑照样能右键格挡。</p>
     *
     * <p>Bukkit 没有「停止持盾」API，<b>冷却即强制 {@code isBlocking()} 为假</b>
     * （已举起的盾/剑也会被收掉）。做法与 {@code WindChargeManager} 的"不能防御"一致，
     * 现在收敛到这一处，避免各模块各写一份再漏掉剑。</p>
     *
     * @param player 目标玩家
     * @param ticks  冷却时长（tick；&lt;=0 时不做任何事）
     */
    public static void disableBlockingItems(Player player, int ticks) {
        if (player == null || ticks <= 0) {
            return;
        }
        blockItem(player, player.getInventory().getItemInMainHand(), ticks);
        blockItem(player, player.getInventory().getItemInOffHand(), ticks);
    }

    /**
     * <b>恢复格挡能力</b>：把主手与副手的<b>盾牌与剑</b>冷却归零（{@code setCooldown(type, 0)}）。
     *
     * <p>与 {@link #disableBlockingItems} 对称。用于"完美弹反成功 → 防御能力立即恢复"：
     * 若只清 {@code Material.SHIELD}，则先前被破盾（剑被打入冷却）的玩家<b>剑仍然举不起来</b>。</p>
     */
    public static void clearBlockingItems(Player player) {
        if (player == null) {
            return;
        }
        clearItem(player, player.getInventory().getItemInMainHand());
        clearItem(player, player.getInventory().getItemInOffHand());
    }

    /** 单件：把盾/剑的冷却归零（其余物品本来就没冷却，不动）。 */
    private static void clearItem(Player player, ItemStack item) {
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == Material.SHIELD || type.name().endsWith("_SWORD")) {
            player.setCooldown(type, 0);
        }
    }

    /** 单件：只有盾与剑需要冷却（其余物品没有格挡能力）。 */
    private static void blockItem(Player player, ItemStack item, int ticks) {
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == Material.SHIELD || type.name().endsWith("_SWORD")) {
            player.setCooldown(type, ticks);
        }
    }
}
