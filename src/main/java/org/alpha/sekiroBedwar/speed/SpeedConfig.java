package org.alpha.sekiroBedwar.speed;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 剑攻速强化配置：封装 <code>duel.yml</code> 的 <code>sword-speed:</code> 段。
 *
 * <p>商店可购买的等级化强化——购买后给玩家实体加一个 {@code ATTACK_SPEED}
 * {@code AttributeModifier}（ADD_NUMBER），作用于本人所有近战武器（剑/斧），
 * 服务端生效、不改客户端。MC 1.21 攻击冷却 = 20 ÷ 攻速；玩家基础攻速属性 4.0，
 * 剑带 -2.4 物品修正 → 持剑总攻速 1.6（冷却 12.5 tick）。本强化在其上加正修正。</p>
 *
 * <p><b>总攻速上限口径</b>（用户确认「总攻速属性上限」）：拿剑时的总攻速
 * （{@code melee-base-attack-speed} + 强化修正）不超过 {@code max-attack-speed}，
 * 即强化修正上限 = {@code max-attack-speed - melee-base-attack-speed}（默认 3.0 - 1.6 = 1.4）。</p>
 */
public final class SpeedConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private int maxLevel;
    private double perLevelAttackSpeed;
    private double maxAttackSpeed;
    private double meleeBaseAttackSpeed;

    private Material categoryMaterial;
    private String categoryName;
    private List<String> categoryLore;

    private final List<SpeedPrice> prices = new ArrayList<>();

    public SpeedConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 sword-speed 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("sword-speed.enabled", true);
        this.maxLevel = Math.max(0, yaml.getInt("sword-speed.max-level", 3));
        this.perLevelAttackSpeed = Math.max(0.0, yaml.getDouble("sword-speed.per-level-attack-speed", 0.3));
        this.maxAttackSpeed = Math.max(0.0, yaml.getDouble("sword-speed.max-attack-speed", 3.0));
        this.meleeBaseAttackSpeed = Math.max(0.0, yaml.getDouble("sword-speed.melee-base-attack-speed", 1.6));

        this.categoryMaterial = parseMaterial(yaml.getString("sword-speed.category.material", "DIAMOND_SWORD"));
        this.categoryName = yaml.getString("sword-speed.category.name", "剑攻速强化");
        this.categoryLore = yaml.getStringList("sword-speed.category.lore");
        if (this.categoryLore.isEmpty()) {
            this.categoryLore = Collections.singletonList("永久提升你的近战攻击速度（本局）");
        }

        this.prices.clear();
        this.prices.addAll(parsePrices(yaml));

        // 有效最大等级 = min(配置 max-level, 价格条数)——价格缺失自动截断，避免无限购买
        this.maxLevel = Math.min(this.maxLevel, this.prices.size());
        if (this.prices.isEmpty()) {
            // 价格完全缺失时给一条兜底，避免整个段不可用（同时 max-level 归零，商店不生成）
            this.prices.add(new SpeedPrice("iron", 1));
            this.maxLevel = 0;
        }
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的剑攻速强化物品类型: " + name);
            return Material.DIAMOND_SWORD;
        }
    }

    private List<SpeedPrice> parsePrices(YamlConfiguration yaml) {
        List<SpeedPrice> result = new ArrayList<>();
        Object raw = yaml.get("sword-speed.prices");
        if (raw instanceof List<?>) {
            // YAML 列表形式：- {currency: iron, amount: 5}。
            // 注意：getConfigurationSection() 对 YAML 列表返回 null（Bukkit 把列表存为 List 而非 Map），
            // 必须走 getMapList / instanceof List 分支，否则价格读空 → max-level 被钳成 0。
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) item;
                Object cur = m.get("currency");
                Object amt = m.get("amount");
                String currency = cur == null ? "iron" : String.valueOf(cur);
                int amount = amt == null ? 1 : toInt(amt, 1);
                result.add(new SpeedPrice(currency, Math.max(1, amount)));
            }
            return result;
        }
        ConfigurationSection section = yaml.getConfigurationSection("sword-speed.prices");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection price = section.getConfigurationSection(key);
            if (price == null) {
                continue;
            }
            String currency = price.getString("currency", "iron");
            int amount = Math.max(1, price.getInt("amount", 1));
            result.add(new SpeedPrice(currency, amount));
        }
        return result;
    }

    /** 任意对象转 int（Number / String），失败返回默认值。 */
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

    /** 该等级的攻速修正量（受总攻速上限钳制）：min(level×每级, max-attack-speed - melee-base)。 */
    public double modifierAmount(int level) {
        double cap = Math.max(0.0, maxAttackSpeed - meleeBaseAttackSpeed);
        return Math.min(level * perLevelAttackSpeed, cap);
    }

    /** 该等级拿剑时的总攻速（melee-base + 强化修正）。 */
    public double effectiveTotalAttackSpeed(int level) {
        return meleeBaseAttackSpeed + modifierAmount(level);
    }

    /** 是否启用剑攻速强化（false 时不注入商店、不拦截购买）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 有效最大强化等级（已按价格条数截断）。 */
    public int maxLevel() {
        return maxLevel;
    }

    /** 每级提升的攻击速度（ADD_NUMBER）。 */
    public double perLevelAttackSpeed() {
        return perLevelAttackSpeed;
    }

    /** 总攻速上限：拿剑时的总攻速不得超过该值。 */
    public double maxAttackSpeed() {
        return maxAttackSpeed;
    }

    /** 近战基础攻速（默认=剑 1.6），用于计算强化上限。 */
    public double meleeBaseAttackSpeed() {
        return meleeBaseAttackSpeed;
    }

    /** 商店类别图标物品类型。 */
    public Material categoryMaterial() {
        return categoryMaterial;
    }

    /** 商店类别 / marker 物品显示名（前缀）。 */
    public String categoryName() {
        return categoryName;
    }

    /** 商店类别图标 lore（拷贝）。 */
    public List<String> categoryLore() {
        return new ArrayList<>(categoryLore);
    }

    /** 第 {@code level} 级（从 1 起）的购买价格；level ∈ [1, maxLevel] 时必有值。 */
    public SpeedPrice price(int level) {
        return prices.get(level - 1);
    }

    /** 单级购买价格值对象。 */
    public static final class SpeedPrice {
        private final String currency;
        private final int amount;

        SpeedPrice(String currency, int amount) {
            this.currency = currency;
            this.amount = amount;
        }

        /** 货币名称（BedWars ItemSpawnerType 名，如 iron / gold / bronze）。 */
        public String currency() {
            return currency;
        }

        /** 数量。 */
        public int amount() {
            return amount;
        }
    }
}
