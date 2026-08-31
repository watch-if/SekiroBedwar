package org.alpha.sekiroBedwar.paperdoll;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 纸人配置：封装 <code>duel.yml</code> 的 <code>paper-doll:</code> 段。
 */
public final class PaperDollConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private int maxPerPlayer;
    private Material material;
    private String name;
    private final PaperPrice[] prices = new PaperPrice[5];

    private int lightningCost;
    private boolean throwEnabled;
    private int throwCost;
    private boolean teleportEnabled;
    private long teleportWindowMs;
    private int teleportCost;

    public PaperDollConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("paper-doll.enabled", true);
        this.maxPerPlayer = Math.max(1, yaml.getInt("paper-doll.max-per-player", 20));
        this.material = parseMaterial(yaml.getString("paper-doll.material", "PAPER"));
        this.name = yaml.getString("paper-doll.name", "纸人");

        prices[4] = parsePrice(yaml, "paper-doll.price-4-teams", "iron", 10);
        prices[3] = parsePrice(yaml, "paper-doll.price-3-teams", "iron", 30);
        prices[2] = parsePrice(yaml, "paper-doll.price-2-teams", "gold", 10);

        this.lightningCost = Math.max(0, yaml.getInt("paper-doll.lightning-cost", 4));
        this.throwEnabled = yaml.getBoolean("paper-doll.throw.enabled", true);
        this.throwCost = Math.max(1, yaml.getInt("paper-doll.throw.cost", 1));
        this.teleportEnabled = yaml.getBoolean("paper-doll.teleport.enabled", true);
        this.teleportWindowMs = Math.max(0L, yaml.getLong("paper-doll.teleport.window-ms", 2000L));
        this.teleportCost = Math.max(1, yaml.getInt("paper-doll.teleport.cost", 1));
    }

    private PaperPrice parsePrice(YamlConfiguration yaml, String key, String defCurrency, int defAmount) {
        String currency = defCurrency;
        int amount = defAmount;
        Object raw = yaml.get(key);
        if (raw instanceof java.util.Map) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) raw;
            Object c = m.get("currency");
            Object a = m.get("amount");
            currency = c == null ? defCurrency : String.valueOf(c);
            amount = a == null ? defAmount : toInt(a, defAmount);
        }
        return new PaperPrice(currency, Math.max(1, amount));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Material.PAPER;
        }
    }

    private static int toInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public PaperPrice priceForAliveTeams(int aliveTeams) {
        if (aliveTeams >= 2 && aliveTeams < prices.length && prices[aliveTeams] != null) {
            return prices[aliveTeams];
        }
        return prices[2] != null ? prices[2] : new PaperPrice("gold", 10);
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public Material material() {
        return material;
    }

    public String name() {
        return name;
    }

    public int lightningCost() {
        return lightningCost;
    }

    public boolean throwEnabled() {
        return throwEnabled;
    }

    public int throwCost() {
        return throwCost;
    }

    public boolean teleportEnabled() {
        return teleportEnabled;
    }

    public long teleportWindowMs() {
        return teleportWindowMs;
    }

    public int teleportCost() {
        return teleportCost;
    }

    public static final class PaperPrice {
        private final String currency;
        private final int amount;

        PaperPrice(String currency, int amount) {
            this.currency = currency;
            this.amount = amount;
        }

        public String currency() {
            return currency;
        }

        public int amount() {
            return amount;
        }
    }
}
