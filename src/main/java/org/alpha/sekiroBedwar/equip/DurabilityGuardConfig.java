package org.alpha.sekiroBedwar.equip;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 取消耐久配置：封装 <code>duel.yml</code> 的 <code>no-durability:</code> 段。
 * 总开关 + 按类别（护甲 / 工具武器 / 盾牌）分别可关。
 */
public final class DurabilityGuardConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private boolean armor;
    private boolean tools;
    private boolean shield;

    public DurabilityGuardConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("no-durability.enabled", true);
        this.armor = yaml.getBoolean("no-durability.armor", true);
        this.tools = yaml.getBoolean("no-durability.tools", false);
        this.shield = yaml.getBoolean("no-durability.shield", false);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean armor() {
        return armor;
    }

    public boolean tools() {
        return tools;
    }

    public boolean shield() {
        return shield;
    }
}
