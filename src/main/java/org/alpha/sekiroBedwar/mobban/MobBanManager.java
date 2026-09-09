package org.alpha.sekiroBedwar.mobban;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 红圈生物禁令（独立模块）：维护决斗红圈内的生物隔离。
 *
 * <p><b>范围</b>：每场进行中（PENDING / ACTIVE）决斗的红圈——与第三方玩家排除判定
 * 同半径（岛屿半径 + {@code visuals.outer-radius}），圆心为触发时锁定的岛屿圆心；
 * 垂直方向按 {@code mob-ban.vertical-range}（格）容差，防止从上下绕过。多场决斗重叠时
 * 任一红圈覆盖即生效。</p>
 *
 * <p><b>两条规则</b>（{@code mob-ban.enabled} 总开关）：</p>
 * <ul>
 *   <li><b>禁止生成</b>（blocked-spawns，默认铁傀儡 + 羊 / TNT 羊）：这些生物在红圈内
 *       的任何生成途径（玩家搭建铁傀儡、刷怪蛋、自然刷怪、繁殖…）在
 *       {@code CreatureSpawnEvent} 一律取消——不进入圈内凭空出现；</li>
 *   <li><b>进圈即清除</b>（kill-stray-mobs）：周期巡检（{@code scan-ticks}）红圈内
 *       一切<b>非玩家生物</b>（{@code Creature}：怪物 / 动物 / 傀儡等，玩家与投射物
 *       不受影响），从圈外走进来的直接移除（{@code remove()}：无掉落、无死亡消息，
 *       保持决斗沉浸）。</li>
 * </ul>
 */
public final class MobBanManager implements Listener {

    private final SekiroBedwar plugin;
    private final MobBanConfig config;
    private final DuelConfig duelConfig;
    private final DuelManager duelManager;
    private BukkitTask scanTask;

    public MobBanManager(SekiroBedwar plugin, MobBanConfig config,
                         DuelConfig duelConfig, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.duelConfig = duelConfig;
        this.duelManager = duelManager;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        scanTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::scanStrays, config.scanTicks(), config.scanTicks());
        plugin.getLogger().info("红圈生物禁令已启用：禁生=" + config.blockedSpawns()
                + " 进圈清除=" + config.killStrayMobs() + "（巡检 " + config.scanTicks() + " tick）");
    }

    public void disable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    // ============ 规则一：禁止生成 ============

    /** 禁生类型的生成事件：落点在任一出决斗红圈内 → 取消（一切生成途径统一入口）。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!config.blockedSpawns().contains(event.getEntityType())) {
            return;
        }
        if (inAnyDuelRedCircle(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ============ 规则二：进圈即清除 ============

    /** 巡检：每场进行中决斗的红圈内，一切非玩家生物（Creature）移除。 */
    private void scanStrays() {
        if (!config.killStrayMobs()) {
            return;
        }
        for (Duel duel : duelManager.getDuels()) {
            if (duel.getState() == DuelState.ENDING) {
                continue; // 收尾中的决斗不再扩张禁令
            }
            DuelIsland island = duel.getIsland();
            World world = island.getWorld();
            double limit = island.getRadius() + duelConfig.outerRadius();
            double vertical = config.verticalRange();
            Location center = new Location(world, island.getCenterX(), island.getCenterY(), island.getCenterZ());
            for (Entity entity : world.getNearbyEntities(center, limit, vertical, limit,
                    e -> e instanceof Creature)) {
                if (island.horizontalDistanceTo(entity.getLocation()) <= limit) {
                    entity.remove(); // 无掉落、无死亡消息（沉浸）
                }
            }
        }
    }

    /** 位置是否落在任一出进行中决斗的红圈（水平判定 + 同世界）。 */
    private boolean inAnyDuelRedCircle(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        for (Duel duel : duelManager.getDuels()) {
            if (duel.getState() == DuelState.ENDING) {
                continue;
            }
            DuelIsland island = duel.getIsland();
            if (!island.getWorld().equals(loc.getWorld())) {
                continue;
            }
            double limit = island.getRadius() + duelConfig.outerRadius();
            if (island.horizontalDistanceTo(loc) <= limit) {
                return true;
            }
        }
        return false;
    }
}
