package org.alpha.sekiroBedwar.crow;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 雾璃鸦配置：封装 <code>duel.yml</code> 的 <code>crow:</code> 段。
 */
public final class CrowConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private Material material;
    private String name;
    private String priceCurrency;
    private int priceAmount;
    private int paperDollCost;
    private long hoverMs;
    private double shatterChance;
    private int maxPerPlayer;
    private long refillDelaySeconds;

    public CrowConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("crow.enabled", true);
        this.material = parseMaterial(yaml.getString("crow.material", "ENDER_EYE"));
        this.name = yaml.getString("crow.name", "雾璃鸦");
        this.priceCurrency = yaml.getString("crow.price-currency", "gold");
        this.priceAmount = Math.max(1, yaml.getInt("crow.price-amount", 20));
        this.paperDollCost = Math.max(0, yaml.getInt("crow.paper-doll-cost", 2));
        this.hoverMs = Math.max(0L, yaml.getLong("crow.hover-ms", 2000L));
        this.shatterChance = clamp01(yaml.getDouble("crow.shatter-chance", 1.0));
        this.maxPerPlayer = Math.max(1, yaml.getInt("crow.max-per-player", 1));
        this.refillDelaySeconds = Math.max(0L, yaml.getLong("crow.refill-delay-seconds", 10L));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的雾璃鸦物品类型: " + name);
            return Material.ENDER_EYE;
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

    public int paperDollCost() {
        return paperDollCost;
    }

    public long hoverMs() {
        return hoverMs;
    }

    /** 悬停到期后的破碎概率（1.0 = 必碎；未碎则返还一只）。 */
    public double shatterChance() {
        return shatterChance;
    }

    /** 每个玩家背包可持有的雾璃鸦上限（默认 1）。 */
    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    /** 激活消耗后自动补发一只雾璃鸦的延迟（秒；0 = 关闭补给）。 */
    public long refillDelaySeconds() {
        return refillDelaySeconds;
    }
}
