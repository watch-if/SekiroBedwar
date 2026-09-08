package org.alpha.sekiroBedwar.windcharge;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 风弹配置：封装 <code>duel.yml</code> 的 <code>wind-charge:</code> 段。
 *
 * <p>全部数值可改：半椭圆几何（depth/width/height）、扫过与停留时长、采样密度、
 * 触碰半径、封印时长与不可叠加窗口、商店价格。</p>
 */
public final class WindChargeConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private Material material;
    private String name;
    private String priceCurrency;
    private int priceAmount;
    private Particle particle;
    private double depth;
    private double width;
    private double height;
    private long sweepMs;
    private long lingerMs;
    private int points;
    private double touchRadius;
    private double disableSeconds;
    private long disableMs;
    private long noStackMs;

    public WindChargeConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("wind-charge.enabled", true);
        this.material = parseMaterial(yaml.getString("wind-charge.material", "WIND_CHARGE"));
        this.name = yaml.getString("wind-charge.name", "风弹");
        this.priceCurrency = yaml.getString("wind-charge.price-currency", "gold");
        this.priceAmount = Math.max(1, yaml.getInt("wind-charge.price-amount", 10));
        this.particle = parseParticle(yaml.getString("wind-charge.particle", "EXPLOSION"));
        this.depth = positiveOr(yaml.getDouble("wind-charge.depth", 1.5), 1.5);
        this.width = positiveOr(yaml.getDouble("wind-charge.width", 2.5), 2.5);
        this.height = positiveOr(yaml.getDouble("wind-charge.height", 3.0), 3.0);
        this.sweepMs = toMillis(yaml.getDouble("wind-charge.sweep-seconds", 1.0));
        this.lingerMs = toMillis(yaml.getDouble("wind-charge.linger-seconds", 1.0));
        this.points = Math.max(2, Math.min(64, yaml.getInt("wind-charge.points", 16)));
        this.touchRadius = Math.max(0.1, yaml.getDouble("wind-charge.touch-radius", 0.6));
        this.disableSeconds = Math.max(0.0, yaml.getDouble("wind-charge.disable-seconds", 0.5));
        this.disableMs = toMillis(this.disableSeconds);
        this.noStackMs = toMillis(Math.max(0.0, yaml.getDouble("wind-charge.no-stack-seconds", 6.0)));
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的风弹物品类型: " + name);
            return Material.WIND_CHARGE;
        }
    }

    private Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的风弹粒子名: " + name);
            return Particle.EXPLOSION;
        }
    }

    private static double positiveOr(double value, double fallback) {
        return value > 0.0 ? value : fallback;
    }

    private static long toMillis(double seconds) {
        return Math.max(0L, Math.round(seconds * 1000.0));
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

    /** 爆风墙粒子（默认 EXPLOSION = TNT 爆炸特效；非法名回退）。 */
    public Particle particle() {
        return particle;
    }

    /** 半椭圆短半轴：面朝方向延伸（格）。 */
    public double depth() {
        return depth;
    }

    /** 半椭圆长半轴：两侧各延伸（格）。 */
    public double width() {
        return width;
    }

    /** 爆风墙高度（格）。 */
    public double height() {
        return height;
    }

    /** 从左向右扫过播放时长（毫秒）。 */
    public long sweepMs() {
        return sweepMs;
    }

    /** 扫完后整面停留时长（毫秒）。 */
    public long lingerMs() {
        return lingerMs;
    }

    /** 弧面采样点数（粒子密度 / 触碰判定精度）。 */
    public int points() {
        return points;
    }

    /** 触碰判定半径（格）。 */
    public double touchRadius() {
        return touchRadius;
    }

    /** 触碰后不能防御与攻击的时长（秒）。 */
    public double disableSeconds() {
        return disableSeconds;
    }

    /** {@link #disableSeconds()} 的毫秒值。 */
    public long disableMs() {
        return disableMs;
    }

    /** 不可叠加窗口（毫秒）：施加后该时长内再次触碰不重复施加。 */
    public long noStackMs() {
        return noStackMs;
    }
}
