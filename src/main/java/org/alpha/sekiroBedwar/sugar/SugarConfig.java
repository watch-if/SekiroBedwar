package org.alpha.sekiroBedwar.sugar;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 夜叉戮糖配置：封装 duel.yml 的 <code>sugar:</code> 段。
 */
public final class SugarConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private Material material;
    private String name;
    private String priceCurrency;
    private int priceAmount;
    private int durationSeconds;
    private int strengthAmplifier;
    private double stanceMultiplier;
    private double maxHealthFactor;
    private double maxStanceFactor;
    private double recoverStanceFraction;

    public SugarConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("sugar.enabled", true);
        this.material = parseMaterial(yaml.getString("sugar.material", "MUSIC_DISC_13"));
        this.name = yaml.getString("sugar.name", "夜叉戮糖");
        this.priceCurrency = yaml.getString("sugar.price-currency", "gold");
        this.priceAmount = Math.max(1, yaml.getInt("sugar.price-amount", 20));
        this.durationSeconds = Math.max(1, yaml.getInt("sugar.duration-seconds", 30));
        this.strengthAmplifier = Math.max(0, yaml.getInt("sugar.strength-amplifier", 1));
        this.stanceMultiplier = Math.max(0.1, yaml.getDouble("sugar.stance-multiplier", 1.5));
        this.maxHealthFactor = clamp01(yaml.getDouble("sugar.max-health-factor", 0.5));
        this.maxStanceFactor = clamp01(yaml.getDouble("sugar.max-stance-factor", 0.5));
        this.recoverStanceFraction = clamp01(yaml.getDouble("sugar.recover-stance-fraction", 0.5));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Material.MUSIC_DISC_13;
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public boolean enabled() {
        return enabled;
    }

    public Material material() {
        return material;
    }

    public String name() {
        return name;
    }

    public String priceCurrency() {
        return priceCurrency;
    }

    public int priceAmount() {
        return priceAmount;
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    /** 力量药水效果等级（amplifier，0 = 力量 I，1 = 力量 II）。 */
    public int strengthAmplifier() {
        return strengthAmplifier;
    }

    /** 架势伤害倍率（夜叉戮糖：该玩家造成的架势伤害 × 此值）。 */
    public double stanceMultiplier() {
        return stanceMultiplier;
    }

    /** HP 上限因子（0.5 = 减半）。 */
    public double maxHealthFactor() {
        return maxHealthFactor;
    }

    /** 架势上限因子（0.5 = 减半）。 */
    public double maxStanceFactor() {
        return maxStanceFactor;
    }

    /** 效果结束时恢复的躯干比例（恢复到最大架势的该比例）。 */
    public double recoverStanceFraction() {
        return recoverStanceFraction;
    }
}
