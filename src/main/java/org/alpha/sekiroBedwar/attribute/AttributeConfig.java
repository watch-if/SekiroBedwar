package org.alpha.sekiroBedwar.attribute;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 属性伤害配置：封装 <code>duel.yml</code> 的 <code>rust:</code>（锈丸）、
 * <code>burn:</code>（炎上）、<code>restore:</code>（还原）三段。
 */
public final class AttributeConfig {
    private final SekiroBedwar plugin;

    // 锈丸
    private boolean rustEnabled;
    private String rustName;
    private Material rustIcon;
    private String rustLv1Currency;
    private int rustLv1Amount;
    private String rustLv2Currency;
    private int rustLv2Amount;
    private int rustRequiredHits;
    private long rustMaxIntervalMs;
    private long rustWindowMs;
    private int rustPoisonSeconds;
    private int rustWitherSeconds;

    // 炎上
    private boolean burnEnabled;
    private String burnName;
    private Material burnIcon;
    private String burnLv1Currency;
    private int burnLv1Amount;
    private String burnLv2Currency;
    private int burnLv2Amount;
    private int burnRequiredHits;
    private long burnMaxIntervalMs;
    private long burnWindowMs;
    private int burnFireTicks;
    private double burnOutsideChance;
    private int burnWitherSeconds;

    // 还原
    private boolean restoreEnabled;
    private String restoreName;
    private Material restoreIcon;
    private String restoreCurrency;
    private int restoreAmount;

    public AttributeConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.rustEnabled = yaml.getBoolean("rust.enabled", true);
        this.rustName = yaml.getString("rust.name", "锈丸");
        this.rustIcon = parseMaterial(yaml.getString("rust.icon", "SPIDER_EYE"), Material.SPIDER_EYE);
        this.rustLv1Currency = yaml.getString("rust.lv1-currency", "gold");
        this.rustLv1Amount = Math.max(1, yaml.getInt("rust.lv1-amount", 30));
        this.rustLv2Currency = yaml.getString("rust.lv2-currency", "gold");
        this.rustLv2Amount = Math.max(1, yaml.getInt("rust.lv2-amount", 34));
        this.rustRequiredHits = Math.max(1, yaml.getInt("rust.required-hits", 3));
        this.rustMaxIntervalMs = Math.max(0L, yaml.getLong("rust.max-interval-ms", 1500L));
        this.rustWindowMs = Math.max(0L, yaml.getLong("rust.window-ms", 5000L));
        this.rustPoisonSeconds = Math.max(1, yaml.getInt("rust.poison-duration-seconds", 10));
        this.rustWitherSeconds = Math.max(1, yaml.getInt("rust.wither-duration-seconds", 10));

        this.burnEnabled = yaml.getBoolean("burn.enabled", true);
        this.burnName = yaml.getString("burn.name", "炎上");
        this.burnIcon = parseMaterial(yaml.getString("burn.icon", "FIRE_CHARGE"), Material.FIRE_CHARGE);
        this.burnLv1Currency = yaml.getString("burn.lv1-currency", "gold");
        this.burnLv1Amount = Math.max(1, yaml.getInt("burn.lv1-amount", 30));
        this.burnLv2Currency = yaml.getString("burn.lv2-currency", "gold");
        this.burnLv2Amount = Math.max(1, yaml.getInt("burn.lv2-amount", 34));
        this.burnRequiredHits = Math.max(1, yaml.getInt("burn.required-hits", 3));
        this.burnMaxIntervalMs = Math.max(0L, yaml.getLong("burn.max-interval-ms", 1500L));
        this.burnWindowMs = Math.max(0L, yaml.getLong("burn.window-ms", 5000L));
        this.burnFireTicks = Math.max(1, yaml.getInt("burn.fire-ticks", 80));
        this.burnOutsideChance = clamp01(yaml.getDouble("burn.out-of-window-chance", 0.3));
        this.burnWitherSeconds = Math.max(1, yaml.getInt("burn.wither-duration-seconds", 10));

        this.restoreEnabled = yaml.getBoolean("restore.enabled", true);
        this.restoreName = yaml.getString("restore.name", "还原");
        this.restoreIcon = parseMaterial(yaml.getString("restore.icon", "BARRIER"), Material.BARRIER);
        this.restoreCurrency = yaml.getString("restore.price-currency", "gold");
        this.restoreAmount = Math.max(1, yaml.getInt("restore.price-amount", 20));
    }

    private Material parseMaterial(String name, Material fallback) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的属性伤害图标材质: " + name + "，回退 " + fallback);
            return fallback;
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public boolean rustEnabled() {
        return rustEnabled;
    }

    public String rustName() {
        return rustName;
    }

    public Material rustIcon() {
        return rustIcon;
    }

    public String rustLv1Currency() {
        return rustLv1Currency;
    }

    public int rustLv1Amount() {
        return rustLv1Amount;
    }

    public String rustLv2Currency() {
        return rustLv2Currency;
    }

    public int rustLv2Amount() {
        return rustLv2Amount;
    }

    public int rustRequiredHits() {
        return rustRequiredHits;
    }

    public long rustMaxIntervalMs() {
        return rustMaxIntervalMs;
    }

    public long rustWindowMs() {
        return rustWindowMs;
    }

    public int rustPoisonSeconds() {
        return rustPoisonSeconds;
    }

    public int rustWitherSeconds() {
        return rustWitherSeconds;
    }

    public boolean burnEnabled() {
        return burnEnabled;
    }

    public String burnName() {
        return burnName;
    }

    public Material burnIcon() {
        return burnIcon;
    }

    public String burnLv1Currency() {
        return burnLv1Currency;
    }

    public int burnLv1Amount() {
        return burnLv1Amount;
    }

    public String burnLv2Currency() {
        return burnLv2Currency;
    }

    public int burnLv2Amount() {
        return burnLv2Amount;
    }

    public int burnRequiredHits() {
        return burnRequiredHits;
    }

    public long burnMaxIntervalMs() {
        return burnMaxIntervalMs;
    }

    public long burnWindowMs() {
        return burnWindowMs;
    }

    public int burnFireTicks() {
        return burnFireTicks;
    }

    public double burnOutsideChance() {
        return burnOutsideChance;
    }

    public int burnWitherSeconds() {
        return burnWitherSeconds;
    }

    public boolean restoreEnabled() {
        return restoreEnabled;
    }

    public String restoreName() {
        return restoreName;
    }

    public Material restoreIcon() {
        return restoreIcon;
    }

    public String restoreCurrency() {
        return restoreCurrency;
    }

    public int restoreAmount() {
        return restoreAmount;
    }
}
