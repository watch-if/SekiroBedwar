package org.alpha.sekiroBedwar.shop;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 忍具商店配置：封装 <code>duel.yml</code> 的 <code>shop:</code> 段。
 * 入口按钮固定注入 {@code price: 0 of iron}（结构性免费，真实购买全在插件 GUI 内）。
 */
public final class SekiroShopConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private boolean pageForwardTakeover;
    private Material entryMaterial;
    private String entryName;
    private List<String> entryLore;
    private String guiTitle;

    public SekiroShopConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("shop.enabled", true);
        this.pageForwardTakeover = yaml.getBoolean("shop.page-forward-takeover", true);
        this.entryMaterial = parseMaterial(yaml.getString("shop.entry.material", "WRITABLE_BOOK"));
        this.entryName = yaml.getString("shop.entry.name", "只狼忍具");
        this.entryLore = yaml.getStringList("shop.entry.lore");
        if (this.entryLore.isEmpty()) {
            this.entryLore = Collections.singletonList("忍具与强化，尽在掌握");
        }
        this.guiTitle = yaml.getString("shop.gui-title", "&8只狼忍具").replace('&', '§');
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的忍具商店入口物品类型: " + name);
            return Material.WRITABLE_BOOK;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** true = 入口接管商店主页右下角槽位（默认）；false = 入口为 shop.yml 注入的免费物品。 */
    public boolean pageForwardTakeover() {
        return pageForwardTakeover;
    }

    public Material entryMaterial() {
        return entryMaterial;
    }

    public String entryName() {
        return entryName;
    }

    public List<String> entryLore() {
        return new ArrayList<>(entryLore);
    }

    public String guiTitle() {
        return guiTitle;
    }
}
