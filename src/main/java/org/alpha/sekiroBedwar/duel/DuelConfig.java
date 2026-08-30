package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 决斗触发配置：封装 <code>duel.yml</code>。
 *
 * <p>所有数值（岛屿半径 x、追溯窗口、内圆 y / 外圆 z 等）都可直接编辑 yml
 * 文件后调用 {@link #reload()} 生效。</p>
 */
public final class DuelConfig {
    private final SekiroBedwar plugin;

    private double radius;
    private long attackRecallSeconds;
    private boolean meleeOnly;
    private double minDamage;
    private double duelCooldownSeconds;
    private boolean requireSolidGround;
    private int solidCheckPoints;
    private double solidMinRatio;
    private double nonVoidMinRatio;

    private double innerRadius;
    private double outerRadius;
    private int particlePoints;
    private int refreshTicks;
    private boolean glow;
    private int thirdPartyHighlightTicks;

    private double duelPendingSeconds;
    private int duelCheckTicks;

    private boolean areaEnabled;
    private int areaCheckTicks;
    private int knockbackGraceTicks;
    private double pullbackMargin;
    private boolean blockPlaceEscaping;
    private Set<PlayerTeleportEvent.TeleportCause> blockedTeleportCauses;

    private final List<DuelIsland> islands = new ArrayList<>();

    public DuelConfig(SekiroBedwar plugin) {
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

        this.radius = yaml.getDouble("radius", 12.0);
        this.attackRecallSeconds = Math.max(1, yaml.getLong("attack-recall-seconds", 5));
        this.meleeOnly = yaml.getBoolean("melee-only", true);
        this.minDamage = Math.max(0.0, yaml.getDouble("min-damage", 0.0));
        this.duelCooldownSeconds = Math.max(0.0, yaml.getDouble("duel-cooldown-seconds", 30.0));
        this.requireSolidGround = yaml.getBoolean("require-solid-ground", true);
        this.solidCheckPoints = Math.max(0, yaml.getInt("solid-check-points", 16));
        this.solidMinRatio = Math.min(1.0, Math.max(0.0, yaml.getDouble("solid-min-ratio", 0.5)));
        this.nonVoidMinRatio = Math.min(1.0, Math.max(0.0, yaml.getDouble("non-void-min-ratio", 0.9)));

        this.innerRadius = Math.max(0.0, yaml.getDouble("visuals.inner-radius", 3.0));
        this.outerRadius = Math.max(0.0, yaml.getDouble("visuals.outer-radius", 6.0));
        this.particlePoints = Math.max(4, yaml.getInt("visuals.points", 32));
        this.refreshTicks = Math.max(1, yaml.getInt("visuals.refresh-ticks", 5));
        this.glow = yaml.getBoolean("visuals.glow", true);
        this.thirdPartyHighlightTicks = Math.max(1, yaml.getInt("visuals.third-party-highlight-ticks", 20));

        this.duelPendingSeconds = Math.max(0.0, yaml.getDouble("duel.pending-seconds", 0.5));
        this.duelCheckTicks = Math.max(1, yaml.getInt("duel.check-ticks", 5));

        this.areaEnabled = yaml.getBoolean("area.enabled", true);
        this.areaCheckTicks = Math.max(1, yaml.getInt("area.check-ticks", 5));
        this.knockbackGraceTicks = Math.max(0, yaml.getInt("area.knockback-grace-ticks", 30));
        this.pullbackMargin = Math.max(0.0, yaml.getDouble("area.pullback-margin", 1.0));
        this.blockPlaceEscaping = yaml.getBoolean("area.block-place-escaping", true);
        this.blockedTeleportCauses = parseTeleportCauses(yaml.getString("area.block-teleport-causes", "ENDER_PEARL,CHORUS_FRUIT"));

        this.islands.clear();
        this.islands.addAll(parseIslands(yaml));
    }

    private List<DuelIsland> parseIslands(YamlConfiguration yaml) {
        List<DuelIsland> result = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("islands");
        if (section == null) {
            return result;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection is = section.getConfigurationSection(name);
            if (is == null) {
                continue;
            }
            String worldName = is.getString("world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            double centerX = is.getDouble("center-x");
            double centerZ = is.getDouble("center-z");
            double islandRadius = is.getDouble("radius");
            if (world == null || islandRadius <= 0.0) {
                plugin.getLogger().warning("跳过无效的决斗岛屿配置: " + name + "（world 不存在或 radius <= 0）");
                continue;
            }
            result.add(DuelIsland.configured(world, centerX, centerZ, islandRadius));
        }
        return result;
    }

    /**
     * 由两名玩家位置推导“决斗岛屿”：以两人中点为圆心、半径 {@link #radius()} 的圆。
     * 若配置了岛屿白名单，则中点圆必须整体落在某个白名单岛屿内，否则返回空。
     *
     * @return 命中则返回推导出的岛屿，否则为空
     */
    public Optional<DuelIsland> resolveIsland(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) {
            return Optional.empty();
        }
        double centerX = (a.getX() + b.getX()) / 2.0;
        double centerY = (a.getY() + b.getY()) / 2.0;
        double centerZ = (a.getZ() + b.getZ()) / 2.0;

        // 两名玩家都必须位于同一圆内（互相在半径内，即同一“预先配置的岛屿区域”）
        if (horizontalDistanceSq(a, centerX, centerZ) > radius * radius
                || horizontalDistanceSq(b, centerX, centerZ) > radius * radius) {
            return Optional.empty();
        }

        DuelIsland derived = DuelIsland.derived(a.getWorld(), centerX, centerY, centerZ, radius);
        if (islands.isEmpty()) {
            return Optional.of(derived);
        }
        for (DuelIsland configured : islands) {
            if (configured.getWorld().equals(a.getWorld()) && configured.containsFully(derived)) {
                return Optional.of(derived);
            }
        }
        return Optional.empty();
    }

    private static double horizontalDistanceSq(Location loc, double x, double z) {
        double dx = loc.getX() - x;
        double dz = loc.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static Set<PlayerTeleportEvent.TeleportCause> parseTeleportCauses(String raw) {
        Set<PlayerTeleportEvent.TeleportCause> result = new HashSet<>();
        if (raw == null) {
            return result;
        }
        for (String part : raw.split(",")) {
            String name = part.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                result.add(PlayerTeleportEvent.TeleportCause.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // 无效的传送原因名：忽略
            }
        }
        return result;
    }

    /** 岛屿半径 x（方块）：两人中点向外延伸的圆范围。 */
    public double radius() {
        return radius;
    }

    /** 双方有效主动攻击追溯窗口（秒）。 */
    public long attackRecallSeconds() {
        return attackRecallSeconds;
    }

    /** 有效攻击是否仅限近战直接命中。 */
    public boolean meleeOnly() {
        return meleeOnly;
    }

    /** 有效攻击最低造成伤害。 */
    public double minDamage() {
        return minDamage;
    }

    /** 同一对玩家触发决斗的冷却（秒）。 */
    public double duelCooldownSeconds() {
        return duelCooldownSeconds;
    }

    /** 是否校验岛屿下方为实体方块而非虚空。 */
    public boolean requireSolidGround() {
        return requireSolidGround;
    }

    /** 岛屿实心校验采样点数。 */
    public int solidCheckPoints() {
        return solidCheckPoints;
    }

    /** 采样点中“有方块”的最低比例。 */
    public double solidMinRatio() {
        return solidMinRatio;
    }

    /** 合法岛屿判定附加条件：范围内“非虚空”采样点最低比例（默认 0.9）。 */
    public double nonVoidMinRatio() {
        return nonVoidMinRatio;
    }

    /** 白色粒子内圆半径 y（方块）。 */
    public double innerRadius() {
        return innerRadius;
    }

    /** 红色粒子外圆半径 z（方块），同时兼作岛屿外围排除第三方玩家的半径。 */
    public double outerRadius() {
        return outerRadius;
    }

    /** 每圈粒子采样点数。 */
    public int particlePoints() {
        return particlePoints;
    }

    /** 粒子刷新间隔（tick）。 */
    public int refreshTicks() {
        return refreshTicks;
    }

    /** 是否用 GLOWING 光箭矢高亮标记两名决斗玩家与红圈内第三方。 */
    public boolean glow() {
        return glow;
    }

    /** 第三方跨入红圈后的高亮标记时长（tick；默认 20 = 1 秒）。 */
    public int thirdPartyHighlightTicks() {
        return thirdPartyHighlightTicks;
    }

    /** PENDING → ACTIVE 缓冲时长（秒）。 */
    public double duelPendingSeconds() {
        return duelPendingSeconds;
    }

    /** DuelManager 周期检测间隔（tick）。 */
    public int duelCheckTicks() {
        return duelCheckTicks;
    }

    /** 是否启用决斗区域限制。 */
    public boolean areaEnabled() {
        return areaEnabled;
    }

    /** 区域限制越界检测间隔（tick）。 */
    public int areaCheckTicks() {
        return areaCheckTicks;
    }

    /** 被攻击击退后允许越界的窗口（tick）。 */
    public int knockbackGraceTicks() {
        return knockbackGraceTicks;
    }

    /** 拉回时距边界内的距离（格）。 */
    public double pullbackMargin() {
        return pullbackMargin;
    }

    /** 是否拦截「把方块搭到边界外」的搭路逃离（默认 true）。 */
    public boolean blockPlaceEscaping() {
        return blockPlaceEscaping;
    }

    /** 主动传送拦截原因集合（末影珍珠 / 紫颂果等）。 */
    public Set<PlayerTeleportEvent.TeleportCause> blockedTeleportCauses() {
        return blockedTeleportCauses;
    }
}
