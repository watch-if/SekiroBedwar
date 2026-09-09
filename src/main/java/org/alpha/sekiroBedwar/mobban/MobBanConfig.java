package org.alpha.sekiroBedwar.mobban;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 红圈生物禁令配置：封装 <code>duel.yml</code> 的 <code>mob-ban:</code> 段。
 *
 * <p>「红圈」半径 = 决斗岛屿半径 + {@code visuals.outer-radius}（与第三方玩家排除判定
 * 同半径，决斗圈的玩家 / 生物隔离边界保持一致）。</p>
 */
public final class MobBanConfig {

    /** 内置默认禁生列表（列表型配置必须有代码默认——服务器旧 duel.yml 缺段即静默失效的教训）。 */
    static final String[] DEFAULT_BLOCKED = {"IRON_GOLEM", "SHEEP"};

    private final SekiroBedwar plugin;

    private boolean enabled;
    private boolean killStrayMobs;
    private final Set<EntityType> blockedSpawns = EnumSet.noneOf(EntityType.class);
    private double verticalRange;
    private long scanTicks;

    public MobBanConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("mob-ban.enabled", true);
        this.killStrayMobs = yaml.getBoolean("mob-ban.kill-stray-mobs", true);
        parseBlocked(yaml.getStringList("mob-ban.blocked-spawns"));
        this.verticalRange = Math.max(1.0, yaml.getDouble("mob-ban.vertical-range", 10.0));
        this.scanTicks = Math.max(1L, yaml.getLong("mob-ban.scan-ticks", 5L));
    }

    private void parseBlocked(List<String> raw) {
        this.blockedSpawns.clear();
        List<String> names = (raw == null || raw.isEmpty()) ? new ArrayList<>(List.of(DEFAULT_BLOCKED)) : raw;
        for (String name : names) {
            try {
                blockedSpawns.add(EntityType.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                plugin.getLogger().warning("mob-ban 忽略无效生物类型: " + name);
            }
        }
        if (blockedSpawns.isEmpty()) {
            for (String name : DEFAULT_BLOCKED) {
                blockedSpawns.add(EntityType.valueOf(name));
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 是否清除红圈内的非玩家生物（进圈即移除）。 */
    public boolean killStrayMobs() {
        return killStrayMobs;
    }

    /** 禁止在红圈生成的生物类型（默认铁傀儡 + 羊 / TNT 羊）。 */
    public Set<EntityType> blockedSpawns() {
        return EnumSet.copyOf(blockedSpawns);
    }

    /** 进圈判定的垂直容差（格，圆心 Y ± 该值）。 */
    public double verticalRange() {
        return verticalRange;
    }

    /** 进圈清除的巡检间隔（tick）。 */
    public long scanTicks() {
        return scanTicks;
    }
}
