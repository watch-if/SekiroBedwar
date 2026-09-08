package org.alpha.sekiroBedwar.equip;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 自动装备配置：封装 <code>duel.yml</code> 的 <code>auto-equip:</code> 段。
 */
public final class AutoEquipConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;

    public AutoEquipConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        this.enabled = yaml.getBoolean("auto-equip.enabled", true);
    }

    /** 是否启用购买护甲后自动装备。 */
    public boolean enabled() {
        return enabled;
    }
}
