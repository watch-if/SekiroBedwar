package org.alpha.sekiroBedwar.lightning;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 巴之雷（雷击 / 雷反）配置：封装 <code>duel.yml</code> 的 <code>lightning:</code> 段。
 *
 * <p>全部数值配置化（{@code lightning:} 段）：
 * <ul>
 *   <li>雷击伤害 {@code lightning-damage}（默认 5.0，×4/×3.5/×3 乘数的基准）；</li>
 *   <li>雷击架势扣除倍率 {@code lightning-stance-multiplier}（默认 4.0）；</li>
 *   <li>三连击（{@code combo:}）：{@code required-hits} 次数、{@code max-interval-ms} 间隔上限、
 *       {@code jump-window-ms} 跳击窗口；</li>
 *   <li>三叉戟衔接（{@code trident:}）：{@code hit-window-ms} 远程命中后窗口、{@code jump-window-ms} 跳击窗口；</li>
 *   <li>雷反（{@code reversal:}）：{@code window-ms} 反击窗口、{@code heal-hp} / {@code heal-hp-wood} 恢复血量、
 *       {@code return-multiplier} / {@code return-multiplier-wood} 返还架势倍率；</li>
 *   <li>商店（{@code shop:}）：两级货币与数量、类别图标与显示名。</li>
 * </ul>
 */
public final class LightningConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private double lightningDamage;
    private double lightningStanceMultiplier;

    private int comboRequiredHits;
    private long comboMaxIntervalMs;
    private long comboJumpWindowMs;

    private long tridentHitWindowMs;
    private long tridentJumpWindowMs;
    private int tridentCompensateDelaySeconds;

    private long reversalWindowMs;
    private double reversalHealHp;
    private double reversalHealHpWood;
    private double reversalReturnMultiplier;
    private double reversalReturnMultiplierWood;

    private boolean shopEnabled;
    private Material categoryMaterial;
    private String categoryName;
    private String level1Currency;
    private int level1Amount;
    private String level2Currency;
    private int level2Amount;

    public LightningConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 lightning 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("lightning.enabled", true);
        this.lightningDamage = Math.max(0.0, yaml.getDouble("lightning.lightning-damage", 5.0));
        this.lightningStanceMultiplier = Math.max(0.0, yaml.getDouble("lightning.lightning-stance-multiplier", 4.0));

        this.comboRequiredHits = Math.max(1, yaml.getInt("lightning.combo.required-hits", 3));
        this.comboMaxIntervalMs = Math.max(0L, yaml.getLong("lightning.combo.max-interval-ms", 700L));
        this.comboJumpWindowMs = Math.max(0L, yaml.getLong("lightning.combo.jump-window-ms", 1000L));

        this.tridentHitWindowMs = Math.max(0L, yaml.getLong("lightning.trident.hit-window-ms", 2000L));
        this.tridentJumpWindowMs = Math.max(0L, yaml.getLong("lightning.trident.jump-window-ms", 1000L));
        this.tridentCompensateDelaySeconds = Math.max(0, yaml.getInt("lightning.trident.compensate-delay-seconds", 10));

        this.reversalWindowMs = Math.max(0L, yaml.getLong("lightning.reversal.window-ms", 170L));
        this.reversalHealHp = Math.max(0.0, yaml.getDouble("lightning.reversal.heal-hp", 2.5));
        this.reversalHealHpWood = Math.max(0.0, yaml.getDouble("lightning.reversal.heal-hp-wood", 3.0));
        this.reversalReturnMultiplier = Math.max(0.0, yaml.getDouble("lightning.reversal.return-multiplier", 3.5));
        this.reversalReturnMultiplierWood = Math.max(0.0, yaml.getDouble("lightning.reversal.return-multiplier-wood", 3.0));

        this.shopEnabled = yaml.getBoolean("lightning.shop.enabled", true);
        this.categoryMaterial = parseMaterial(yaml.getString("lightning.shop.category-material", "TRIDENT"));
        this.categoryName = yaml.getString("lightning.shop.category-name", "巴之雷");
        this.level1Currency = yaml.getString("lightning.shop.level-1-currency", "diamond");
        this.level1Amount = Math.max(1, yaml.getInt("lightning.shop.level-1-amount", 1));
        this.level2Currency = yaml.getString("lightning.shop.level-2-currency", "emerald");
        this.level2Amount = Math.max(1, yaml.getInt("lightning.shop.level-2-amount", 1));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的巴之雷图标物品类型: " + name);
            return Material.TRIDENT;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public double lightningDamage() {
        return lightningDamage;
    }

    public double lightningStanceMultiplier() {
        return lightningStanceMultiplier;
    }

    public int comboRequiredHits() {
        return comboRequiredHits;
    }

    public long comboMaxIntervalMs() {
        return comboMaxIntervalMs;
    }

    public long comboJumpWindowMs() {
        return comboJumpWindowMs;
    }

    public long tridentHitWindowMs() {
        return tridentHitWindowMs;
    }

    public long tridentJumpWindowMs() {
        return tridentJumpWindowMs;
    }

    public int tridentCompensateDelaySeconds() {
        return tridentCompensateDelaySeconds;
    }

    public long reversalWindowMs() {
        return reversalWindowMs;
    }

    public double reversalHealHp() {
        return reversalHealHp;
    }

    public double reversalHealHpWood() {
        return reversalHealHpWood;
    }

    public double reversalReturnMultiplier() {
        return reversalReturnMultiplier;
    }

    public double reversalReturnMultiplierWood() {
        return reversalReturnMultiplierWood;
    }

    public boolean shopEnabled() {
        return shopEnabled;
    }

    public Material categoryMaterial() {
        return categoryMaterial;
    }

    public String categoryName() {
        return categoryName;
    }

    public String level1Currency() {
        return level1Currency;
    }

    public int level1Amount() {
        return level1Amount;
    }

    public String level2Currency() {
        return level2Currency;
    }

    public int level2Amount() {
        return level2Amount;
    }
}
