package org.alpha.sekiroBedwar.stomp;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 踩头（重锤风暴）配置：封装 duel.yml 的 <code>stomp:</code> 段。
 *
 * <p>所有数值配置化，不硬编码；标量键全部带代码默认值（服务器旧 duel.yml 是
 * 不随发版更新的旧拷贝，缺键时靠默认值免疫）。</p>
 */
public final class StompConfig {

    private final SekiroBedwar plugin;

    private boolean enabled;
    private String priceCurrency;
    private int priceAmount;

    /** 触发判定的碰撞箱水平间距上限（格）。 */
    private double triggerDistance;
    /** 下落高度分层阈值（格）：不超过该值 = 轻踩（只扣架势）。 */
    private double fallThreshold;
    /** 轻踩的架势扣减。 */
    private double lightStance;
    /** 重踩的生命伤害（只有 1 基础伤害的重锤口径）。 */
    private double heavyDamage;
    /** 风爆竖直弹起的基础速度（下落 0 格时）。 */
    private double windBurstUpBase;
    /** 风爆竖直弹起随下落高度递增的每格增量。 */
    private double windBurstUpPerBlock;
    /** 风爆竖直弹起速度上限（防高处砸下弹飞过头）。 */
    private double windBurstUpMax;
    /** 借力水平位移初速度（击退 I 量级）。 */
    private double bounceHorizontal;
    /** 被完美弹反时的反向击退初速度（击退 II 量级）。 */
    private double parriedKnockback;
    /** 被完美弹反时的反向击退向上分量。 */
    private double parriedUp;
    /** 风爆把周围生物一并弹起的半径（格；0 = 只弹自己）。 */
    private double windBurstRadius;
    /** 触发后该玩家下一次落地前不可再触发（防同一滞空连踩）。 */
    private boolean oncePerAirtime;
    /** 商店图标材质（重锤）。 */
    private Material icon;

    public StompConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("stomp.enabled", true);
        this.priceCurrency = yaml.getString("stomp.price-currency", "iron");
        this.priceAmount = Math.max(1, yaml.getInt("stomp.price-amount", 10));
        this.triggerDistance = Math.max(0.1, yaml.getDouble("stomp.trigger-distance", 0.8));
        this.fallThreshold = Math.max(0.0, yaml.getDouble("stomp.fall-threshold", 1.5));
        this.lightStance = Math.max(0.0, yaml.getDouble("stomp.light-stance", 1.0));
        this.heavyDamage = Math.max(0.0, yaml.getDouble("stomp.heavy-damage", 1.0));
        this.windBurstUpBase = Math.max(0.0, yaml.getDouble("stomp.wind-burst-up-base", 0.42));
        this.windBurstUpPerBlock = Math.max(0.0, yaml.getDouble("stomp.wind-burst-up-per-block", 0.08));
        this.windBurstUpMax = Math.max(this.windBurstUpBase,
                yaml.getDouble("stomp.wind-burst-up-max", 0.9));
        this.bounceHorizontal = Math.max(0.0, yaml.getDouble("stomp.bounce-horizontal", 0.4));
        this.parriedKnockback = Math.max(0.0, yaml.getDouble("stomp.parried-knockback", 0.8));
        this.parriedUp = Math.max(0.0, yaml.getDouble("stomp.parried-up", 0.36));
        this.windBurstRadius = Math.max(0.0, yaml.getDouble("stomp.wind-burst-radius", 3.0));
        this.oncePerAirtime = yaml.getBoolean("stomp.once-per-airtime", true);
        this.icon = parseMaterial(yaml.getString("stomp.icon", "MACE"));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Material.MACE;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String priceCurrency() {
        return priceCurrency;
    }

    public int priceAmount() {
        return priceAmount;
    }

    /** 触发判定的碰撞箱水平间距上限（格）。 */
    public double triggerDistance() {
        return triggerDistance;
    }

    /** 下落高度分层阈值（格）：不超过该值 = 轻踩，只扣架势。 */
    public double fallThreshold() {
        return fallThreshold;
    }

    /** 轻踩扣减的架势值。 */
    public double lightStance() {
        return lightStance;
    }

    /** 重踩的生命伤害（重锤只有 1 基础伤害）。 */
    public double heavyDamage() {
        return heavyDamage;
    }

    /** 风爆竖直弹起的基础速度（下落 0 格时；每次踩中都触发，与伤害分层无关）。 */
    public double windBurstUpBase() {
        return windBurstUpBase;
    }

    /** 风爆竖直弹起随下落高度递增的每格增量。 */
    public double windBurstUpPerBlock() {
        return windBurstUpPerBlock;
    }

    /** 风爆竖直弹起速度上限。 */
    public double windBurstUpMax() {
        return windBurstUpMax;
    }

    /** 借力弹走的水平初速度（击退 I 量级）。 */
    public double bounceHorizontal() {
        return bounceHorizontal;
    }

    /** 被完美弹反时反向击退的初速度（击退 II 量级）。 */
    public double parriedKnockback() {
        return parriedKnockback;
    }

    /** 被完美弹反时反向击退的向上分量。 */
    public double parriedUp() {
        return parriedUp;
    }

    /** 风爆掀起的半径（格）：半径内的非玩家生物一并向上弹起；0 = 只弹自己。 */
    public double windBurstRadius() {
        return windBurstRadius;
    }

    /** 是否限制「一次滞空只能触发一次」（落地才重置）。 */
    public boolean oncePerAirtime() {
        return oncePerAirtime;
    }

    /** 商店图标材质（重锤）。 */
    public Material icon() {
        return icon;
    }
}
