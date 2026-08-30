package org.alpha.sekiroBedwar.freeze;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 决斗冻结系统配置：封装 <code>duel.yml</code> 的 <code>freeze:</code> 段。
 *
 * <p>三块子配置：
 * <ul>
 *   <li><code>freeze.resource</code>：物资刷新冻结（白圈内暂停实际生成 + 缓存窗口）；</li>
 *   <li><code>freeze.respawn</code>：队伍复活冻结（床在白圈期间死亡挂起复活）；</li>
 *   <li><code>freeze.block-break</code> / <code>freeze.block-place</code>：决斗双方方块保护。</li>
 * </ul>
 * 白圈半径不在此处重复定义，统一复用 {@link org.alpha.sekiroBedwar.duel.DuelConfig#innerRadius()}。
 */
public final class FreezeConfig {
    private final SekiroBedwar plugin;

    private boolean resourceEnabled;
    private long resourceMaxCacheSeconds;
    private boolean respawnEnabled;
    private boolean blockBreakEnabled;
    private boolean blockBreakAreaOnly;
    private boolean blockPlaceEnabled;

    public FreezeConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.resourceEnabled = yaml.getBoolean("freeze.resource.enabled", true);
        this.resourceMaxCacheSeconds = Math.max(0, yaml.getLong("freeze.resource.max-cache-seconds", 120));
        this.respawnEnabled = yaml.getBoolean("freeze.respawn.enabled", true);
        this.blockBreakEnabled = yaml.getBoolean("freeze.block-break.enabled", true);
        this.blockBreakAreaOnly = yaml.getBoolean("freeze.block-break.area-only", true);
        this.blockPlaceEnabled = yaml.getBoolean("freeze.block-place.enabled", true);
    }

    /** 是否启用物资刷新冻结。 */
    public boolean resourceEnabled() {
        return resourceEnabled;
    }

    /** 缓存时间窗口（秒）：决斗期间只缓存窗口内的待生成资源，窗口外的溢出丢弃。 */
    public long resourceMaxCacheSeconds() {
        return resourceMaxCacheSeconds;
    }

    /** 是否启用队伍复活冻结。 */
    public boolean respawnEnabled() {
        return respawnEnabled;
    }

    /** 是否启用「决斗双方禁止破坏方块」。 */
    public boolean blockBreakEnabled() {
        return blockBreakEnabled;
    }

    /** 破坏禁止是否仅限决斗范围内（false = 决斗期间全图禁止破坏）。 */
    public boolean blockBreakAreaOnly() {
        return blockBreakAreaOnly;
    }

    /** 是否启用「决斗双方禁止搭建方块」（决斗期间全图）。 */
    public boolean blockPlaceEnabled() {
        return blockPlaceEnabled;
    }
}
