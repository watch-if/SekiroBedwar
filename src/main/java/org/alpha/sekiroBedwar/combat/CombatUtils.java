package org.alpha.sekiroBedwar.combat;

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
}
