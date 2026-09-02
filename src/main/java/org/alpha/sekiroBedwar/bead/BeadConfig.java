package org.alpha.sekiroBedwar.bead;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 佛珠配置：封装 duel.yml 的 bead: 段。
 */
public final class BeadConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private int maxPurchases;
    private int hpPerBead;
    private int basePrice;
    private int priceIncrement;
    private String currency;
    private Material material;
    private String name;

    public BeadConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("bead.enabled", true);
        this.maxPurchases = Math.max(1, yaml.getInt("bead.max-purchases", 4));
        this.hpPerBead = Math.max(1, yaml.getInt("bead.hp-per-bead", 5));
        this.basePrice = Math.max(1, yaml.getInt("bead.base-price", 4));
        this.priceIncrement = Math.max(0, yaml.getInt("bead.price-increment", 2));
        this.currency = yaml.getString("bead.currency", "diamond");
        this.material = parseMaterial(yaml.getString("bead.material", "ENDER_EYE"));
        this.name = yaml.getString("bead.name", "佛珠");
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Material.ENDER_EYE;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxPurchases() {
        return maxPurchases;
    }

    public int hpPerBead() {
        return hpPerBead;
    }

    public int basePrice() {
        return basePrice;
    }

    public int priceIncrement() {
        return priceIncrement;
    }

    public String currency() {
        return currency;
    }

    public Material material() {
        return material;
    }

    public String name() {
        return name;
    }
}
