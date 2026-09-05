package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 盾牌弹反配置：封装 <code>duel.yml</code> 的 <code>deflect:</code> 段。
 * 主手举盾消耗纸人 → deflect-window-ms 内全部近战命中按完美弹反窗口处理 → 窗口结束强制收盾。
 */
public final class DeflectConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private int paperDollCost;
    private long deflectWindowMs;

    public DeflectConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("deflect.enabled", true);
        this.paperDollCost = Math.max(1, yaml.getInt("deflect.paper-doll-cost", 2));
        this.deflectWindowMs = Math.max(0L, yaml.getLong("deflect.deflect-window-ms", 2000L));
    }

    public boolean enabled() {
        return enabled;
    }

    public int paperDollCost() {
        return paperDollCost;
    }

    public long deflectWindowMs() {
        return deflectWindowMs;
    }
}
