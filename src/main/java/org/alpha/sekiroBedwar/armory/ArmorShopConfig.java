package org.alpha.sekiroBedwar.armory;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 护甲商店配置：封装 <code>duel.yml</code> 的 <code>armor-shop:</code> 段。
 * 劫持 BedWars 商店主页的分类靠显示名匹配（{@code category-name}），套装列表见 {@code sets}。
 */
public final class ArmorShopConfig {

    /** 一套护甲的定价与材质定义（material 为四件套名前缀，如 LEATHER → LEATHER_HELMET 等）。 */
    public static final class ArmorSet {
        public final String id;
        public final String displayName;
        public final String materialBase;
        public final String currency;
        public final int amount;

        ArmorSet(String id, String displayName, String materialBase, String currency, int amount) {
            this.id = id;
            this.displayName = displayName;
            this.materialBase = materialBase;
            this.currency = currency;
            this.amount = amount;
        }

        /** 四件套：盔/胸/腿/靴；任一材质非法则该套装整体作废（返回 null）。 */
        public Material[] pieces() {
            String upper = materialBase.toUpperCase(Locale.ROOT);
            Material[] out = new Material[4];
            out[0] = Material.matchMaterial(upper + "_HELMET");
            out[1] = Material.matchMaterial(upper + "_CHESTPLATE");
            out[2] = Material.matchMaterial(upper + "_LEGGINGS");
            out[3] = Material.matchMaterial(upper + "_BOOTS");
            for (Material m : out) {
                if (m == null || m.isAir()) {
                    return null;
                }
            }
            return out;
        }
    }

    private final SekiroBedwar plugin;

    private boolean enabled;
    private String categoryName;
    private String guiTitle;
    private final List<ArmorSet> sets = new ArrayList<>();

    public ArmorShopConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("armor-shop.enabled", true);
        this.categoryName = yaml.getString("armor-shop.category-name", "护甲");
        this.guiTitle = yaml.getString("armor-shop.gui-title", "&8护甲商店").replace('&', '§');

        sets.clear();
        Object raw = yaml.get("armor-shop.sets");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = str(m.get("id"), "set");
                String name = str(m.get("name"), id);
                String base = str(m.get("material"), null);
                String currency = str(m.get("currency"), "iron");
                int amount = Math.max(1, toInt(m.get("amount"), 1));
                if (base == null) {
                    plugin.getLogger().warning("跳过缺少 material 的护甲套装: " + id);
                    continue;
                }
                sets.add(new ArmorSet(id, name, base, currency, amount));
            }
        }
        // 服务器 duel.yml 是旧拷贝（无 armor-shop 段）时的兜底默认档——保证护甲商店不因缺配置而整体失效；
        // 价格沿用服务器 shop.yml 原单件定价（1 铁 / 40 铁 / 12 金 / 6 绿），整套同价
        if (sets.isEmpty()) {
            sets.add(new ArmorSet("leather", "皮革套装", "LEATHER", "iron", 1));
            sets.add(new ArmorSet("chainmail", "锁链套装", "CHAINMAIL", "iron", 40));
            sets.add(new ArmorSet("iron", "铁质套装", "IRON", "gold", 12));
            sets.add(new ArmorSet("diamond", "钻石套装", "DIAMOND", "emerald", 6));
        }
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String categoryName() {
        return categoryName;
    }

    public String guiTitle() {
        return guiTitle;
    }

    public List<ArmorSet> sets() {
        return List.copyOf(sets);
    }
}
