package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 决斗触发后的视觉效果：
 * <ul>
 *   <li>双方高亮用“光箭矢效果” = {@link PotionEffectType#GLOWING} 药水效果（队伍颜色由
 *       BedWars 广播的原版队伍数据包自动着色，见 GameSidebar 的 ClientboundSetPlayerTeamPacket）；
 *       持续到决斗结束（{@link #clearDuel} / {@link #clearAll} 移除，不再受 duration 限制）；</li>
 *   <li>以岛屿中心为圆心，白色 {@link Particle.DUST} 画半径为 <code>visuals.inner-radius</code>(y) 的内圆；</li>
 *   <li>红色 {@link Particle.DUST} 画半径为 <code>visuals.outer-radius</code>(z) 的外圆；
 *       双圈粒子按 <code>visuals.refresh-ticks</code> 间隔刷新，<b>持续到决斗结束</b>（圆心在触发时锁定，
 *       整场不变）；</li>
 *   <li>第三方跨入红圈（岛屿半径 + 外圆半径 z 的排除范围）时，用其 BedWars 队伍颜色的
 *       GLOWING 高亮标记 <code>visuals.third-party-highlight-ticks</code>（默认 20 tick = 1 秒）。</li>
 * </ul>
 */
public final class DuelVisualizer {
    private final SekiroBedwar plugin;
    private final DuelConfig config;

    private final Set<UUID> glowingPlayers = new HashSet<>();
    /** 第三方高亮节流：UUID → 上次标记时间戳（标记窗口内不重复标记）。 */
    private final Map<UUID, Long> lastMarkedAt = new HashMap<>();
    private final Set<BukkitTask> activeTasks = new HashSet<>();
    /** 决斗玩家 UUID → 该场决斗的岛屿粒子任务（用于决斗提前结束时按玩家取消，防止粒子残留）。 */
    private final Map<UUID, BukkitTask> islandTasks = new HashMap<>();

    /** 决斗双方 GLOWING 时长（30 分钟；决斗结束由 clearDuel/clearAll 主动移除）。 */
    private static final int DUELIST_GLOW_TICKS = 20 * 60 * 30;
    /** 粒子任务安全兜底（10 分钟；正常由 clearDuel 结束，仅防异常情况下任务泄漏）。 */
    private static final long SAFETY_TICKS = 20 * 60 * 10L;

    public DuelVisualizer(SekiroBedwar plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * 在两名决斗玩家之间展示决斗特效（圆心 = 触发时锁定的岛屿中心，整场不变）。
     *
     * @param a      决斗玩家 A
     * @param b      决斗玩家 B
     * @param island 推导出的决斗岛屿（圆心/半径）
     * @param game   两名玩家所属对局（用于枚举红圈内的第三方玩家）
     */
    public void show(Player a, Player b, DuelIsland island, LocalGame game) {
        // 防御：同一玩家再次 show 时先取消旧的岛屿粒子任务，防止双圈粒子残留
        cancelStale(a.getUniqueId());
        cancelStale(b.getUniqueId());

        if (config.glow()) {
            setDuelistGlow(a);
            setDuelistGlow(b);
        }

        // 粒子双圈：白色内圆(y) + 红色外圆(z)，以锁定圆心为中心，持续到决斗结束
        World world = island.getWorld();
        double centerX = island.getCenterX();
        double centerY = island.getCenterY();
        double centerZ = island.getCenterZ();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            drawCircle(world, centerX, centerY, centerZ, config.innerRadius(), Color.WHITE);
            drawCircle(world, centerX, centerY, centerZ, config.outerRadius(), Color.RED);
            // 第三方跨入红圈（排除范围）→ 队伍色高亮 1 秒
            if (config.glow()) {
                markThirdParties(game, island, a.getUniqueId(), b.getUniqueId());
            }
        }, 0L, config.refreshTicks());
        activeTasks.add(task);
        islandTasks.put(a.getUniqueId(), task);
        islandTasks.put(b.getUniqueId(), task);

        // 安全兜底：若决斗异常结束路径缺失（clearDuel 未触发），超时后强制清理，防止特效残留
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeTasks.contains(task)) {
                task.cancel();
                activeTasks.remove(task);
                islandTasks.remove(a.getUniqueId());
                islandTasks.remove(b.getUniqueId());
                unglowIfTracked(a.getUniqueId());
                unglowIfTracked(b.getUniqueId());
            }
        }, SAFETY_TICKS);
    }

    /** 取消某玩家旧的岛屿粒子任务（若有）。 */
    private void cancelStale(UUID uuid) {
        BukkitTask task = islandTasks.remove(uuid);
        if (task != null) {
            task.cancel();
            activeTasks.remove(task);
        }
    }

    /**
     * 第三方跨入红圈（圆心向外 岛屿半径 + 外圆半径 的排除范围）时，用其队伍颜色高亮标记。
     * 直接施加 {@link PotionEffectType#GLOWING}：BedWars 已向对局内玩家广播带颜色的原版队伍数据包，
     * 客户端据此给该玩家渲染其队伍颜色的高亮（未收到队伍包时退化为白色）。
     */
    private void markThirdParties(LocalGame game, DuelIsland island, UUID a, UUID b) {
        double limit = island.getRadius() + config.outerRadius();
        int markTicks = Math.max(1, config.thirdPartyHighlightTicks());
        long now = System.currentTimeMillis();
        long markMs = markTicks * 50L;
        // 节流清理：过期条目移除，防止状态无限增长
        lastMarkedAt.entrySet().removeIf(e -> now - e.getValue() >= markMs);

        for (BWPlayer other : game.getConnectedPlayers()) {
            UUID id = other.getUuid();
            if (id.equals(a) || id.equals(b) || other.isSpectator()) {
                continue;
            }
            Player bukkit = Bukkit.getPlayer(id);
            if (bukkit == null || !bukkit.isOnline()) {
                continue;
            }
            // 世界切换：仅在岛屿同一世界内判断水平距离，异世界玩家不算第三方
            if (!bukkit.getWorld().equals(island.getWorld())) {
                continue;
            }
            if (island.horizontalDistanceTo(bukkit.getLocation()) > limit) {
                continue;
            }
            // 标记窗口内不重复标记
            if (lastMarkedAt.containsKey(id)) {
                continue;
            }
            lastMarkedAt.put(id, now);
            bukkit.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, markTicks, 0, false, false, false));
        }
    }

    private void drawCircle(World world, double cx, double cy, double cz, double radius, Color color) {
        int points = config.particlePoints();
        double step = 2.0 * Math.PI / points;
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int i = 0; i < points; i++) {
            double theta = step * i;
            double px = cx + radius * Math.cos(theta);
            double pz = cz + radius * Math.sin(theta);
            world.spawnParticle(Particle.DUST, px, cy, pz, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    /** 光箭矢效果：施加 GLOWING 药水效果（队伍颜色由 BedWars 队伍数据包自动着色）。 */
    private void setDuelistGlow(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, DUELIST_GLOW_TICKS, 0, false, false, false));
        glowingPlayers.add(player.getUniqueId());
    }

    private void unglowIfTracked(UUID uuid) {
        if (glowingPlayers.remove(uuid)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
    }

    /**
     * 决斗结束（任意原因）时移除两名玩家的 GLOWING 高亮并取消该场决斗的岛屿粒子任务，
     * 使双方立即恢复决斗前的可见状态（不再依赖 duration 自然到期）。
     *
     * @param a 决斗玩家 A（可能已离线为 null）
     * @param b 决斗玩家 B（可能已离线为 null）
     */
    public void clearDuel(Player a, Player b) {
        if (a != null) {
            unglowIfTracked(a.getUniqueId());
        }
        if (b != null) {
            unglowIfTracked(b.getUniqueId());
        }
        // 取消该场决斗的岛屿粒子任务（任一方 UUID 都能查到同一任务）
        BukkitTask task = null;
        if (a != null) {
            task = islandTasks.remove(a.getUniqueId());
        }
        if (task == null && b != null) {
            task = islandTasks.remove(b.getUniqueId());
        }
        if (task != null) {
            task.cancel();
            activeTasks.remove(task);
        }
        if (b != null) {
            islandTasks.remove(b.getUniqueId());
        }
    }

    /** 清理所有进行中的特效与高亮（插件禁用、对局结束时调用）。 */
    public void clearAll() {
        for (BukkitTask task : activeTasks) {
            task.cancel();
        }
        activeTasks.clear();
        islandTasks.clear();
        for (UUID uuid : glowingPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        glowingPlayers.clear();
        lastMarkedAt.clear();
    }

    public void disable() {
        clearAll();
    }
}
