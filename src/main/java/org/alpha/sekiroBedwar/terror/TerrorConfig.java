package org.alpha.sekiroBedwar.terror;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 僵尸头颅 / 恐怖条配置：封装 duel.yml 的 terror: 段。
 */
public final class TerrorConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private double dropChance;
    private int paperDollCost;
    private double radius;
    private long durationMs;
    private double terrorPerSecond;
    private double decayPerSecond;
    private double damagePerSecond;
    private double maxTerror;

    public TerrorConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("terror.enabled", true);
        this.dropChance = clamp01(yaml.getDouble("terror.drop-chance", 0.5));
        this.paperDollCost = Math.max(1, yaml.getInt("terror.paper-doll-cost", 3));
        this.radius = Math.max(1.0, yaml.getDouble("terror.radius", 3.0));
        this.durationMs = Math.max(0L, yaml.getLong("terror.duration-ms", 2000L));
        this.terrorPerSecond = Math.max(0.0, yaml.getDouble("terror.terror-per-second", 30.0));
        this.decayPerSecond = Math.max(0.0, yaml.getDouble("terror.decay-per-second", 10.0));
        this.damagePerSecond = Math.max(0.0, yaml.getDouble("terror.damage-per-second", 1.0));
        this.maxTerror = Math.max(1.0, yaml.getDouble("terror.max-terror", 100.0));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public boolean enabled() {
        return enabled;
    }

    public double dropChance() {
        return dropChance;
    }

    public int paperDollCost() {
        return paperDollCost;
    }

    public double radius() {
        return radius;
    }

    public long durationMs() {
        return durationMs;
    }

    public double terrorPerSecond() {
        return terrorPerSecond;
    }

    public double decayPerSecond() {
        return decayPerSecond;
    }

    public double damagePerSecond() {
        return damagePerSecond;
    }

    public double maxTerror() {
        return maxTerror;
    }
}
