package org.alpha.sekiroBedwar.danger;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 危攻击 / 识破配置：封装 duel.yml 的 danger: 段。
 */
public final class DangerConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private double stancePenalty;
    private long mikiriWindowMs;
    private double shieldBreakSeconds;
    private String shopCurrency;
    private int shopAmount;

    public DangerConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("danger.enabled", true);
        this.stancePenalty = Math.max(0.0, yaml.getDouble("danger.stance-penalty", 15.0));
        this.mikiriWindowMs = Math.max(0L, yaml.getLong("danger.mikiri-window-ms", 170L));
        this.shieldBreakSeconds = Math.max(0.0, yaml.getDouble("danger.shield-break-seconds", 1.0));
        this.shopCurrency = yaml.getString("danger.shop.currency", "diamond");
        this.shopAmount = Math.max(1, yaml.getInt("danger.shop.amount", 1));
    }

    public boolean enabled() {
        return enabled;
    }

    public double stancePenalty() {
        return stancePenalty;
    }

    public long mikiriWindowMs() {
        return mikiriWindowMs;
    }

    public double shieldBreakSeconds() {
        return shieldBreakSeconds;
    }

    /** 长矛商店价格货币（BedWars 资源名）。 */
    public String shopCurrency() {
        return shopCurrency;
    }

    /** 长矛商店价格数量。 */
    public int shopAmount() {
        return shopAmount;
    }
}
