package org.alpha.sekiroBedwar.welcome;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 玩法指南书配置：封装 <code>duel.yml</code> 的 <code>welcome:</code> 段。
 */
public final class WelcomeConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private String name;

    public WelcomeConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        this.enabled = yaml.getBoolean("welcome.enabled", true);
        this.name = yaml.getString("welcome.name", "起床战争 · 只狼玩法指南");
    }

    /** 是否启用（大厅 / 服务器进入时发放指南书，进入对局时收回）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 成书显示名 / 标题。 */
    public String name() {
        return name;
    }
}
