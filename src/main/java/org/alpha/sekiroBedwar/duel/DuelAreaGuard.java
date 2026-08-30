package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 决斗区域限制守卫（独立模块，与触发 / 生命周期解耦）。
 *
 * <p>决斗期间（PENDING / ACTIVE）双方<b>不能主动离开</b>规定边界
 * （边界半径 = 白色粒子内圆 {@code visuals.inner-radius}）：
 * <ul>
 *   <li>主动越界（走 / 跑 / 跳）→ 周期任务拉回边界内，<b>不发送提示消息</b>；</li>
 *   <li>被攻击击退越界（窗口 {@code area.knockback-grace-ticks} 内，且位移方向与击退方向一致）→ 不拉回
 *       （击退检测与处理实现 {@link KnockbackHandler}）；</li>
 *   <li>虚空豁免（y &lt; 世界最小高度，被击落虚空坠落中）→ 不拉回；</li>
 *   <li>主动传送（末影珍珠 / 紫颂果等 {@code area.block-teleport-causes}）落点越界 → 取消传送；</li>
 *   <li>搭方块越界（{@code area.block-place-escaping}）→ 拦截 {@code BlockPlaceEvent}（防止搭路逃离）。</li>
 * </ul></p>
 *
 * <p><b>线程安全</b>：越界检测任务由 {@code runTaskTimer} 驱动（主线程）；击退记录用
 * {@link ConcurrentHashMap}，读操作任意线程安全。所有写操作经 {@link #ensureMainThread} 守卫。</p>
 *
 * <p><b>内存泄漏防护</b>：击退记录随玩家下线 / 决斗结束 / 窗口过期清理；检测任务于
 * {@link #disable()} 取消。</p>
 */
public final class DuelAreaGuard implements KnockbackHandler {
    private final SekiroBedwar plugin;
    private final DuelConfig config;
    private final DuelManager duelManager;
    private final DuelAreaListener listener;

    /** 被击退玩家的击退记录：UUID → (窗口过期时间戳, 水平击退方向)。 */
    private final Map<UUID, KnockbackInfo> knockbacks = new ConcurrentHashMap<>();

    /**
     * “处决窗口”逃离豁免谓词：某玩家处于崩条的处决窗口时，双方都可离开决斗场地
     * （不被拉回、不拦截主动传送）。默认恒 false（不豁免）；由 SekiroBedwar 注入。
     */
    private Predicate<UUID> escapeWindowPredicate = uuid -> false;

    private BukkitTask checkTask;

    /** 一次击退记录：窗口到期时间戳 + 攻击者→受害者的水平归一化方向。 */
    private record KnockbackInfo(long expireAt, Vector direction) {
    }

    public DuelAreaGuard(SekiroBedwar plugin, DuelConfig config, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.duelManager = duelManager;
        this.listener = new DuelAreaListener(this, config);
    }

    /** 注册监听 + 启动周期越界检测（{@code area.enabled=false} 时不启动）。 */
    public void enable() {
        ensureMainThread("enable");
        if (!config.areaEnabled()) {
            plugin.getLogger().info("DuelAreaGuard 已禁用（area.enabled=false）");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        checkTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::check, 1L, Math.max(1, config.areaCheckTicks()));
    }

    /**
     * 注入处决窗口逃离豁免谓词（由 SekiroBedwar 传入 {@code uuid -> stanceManager.isBroken(uuid)}）。
     * 谓词为真的玩家处于崩条处决窗口：其所在决斗双方可自由离开场地。
     */
    public void setEscapeWindowPredicate(Predicate<UUID> escapeWindowPredicate) {
        this.escapeWindowPredicate = escapeWindowPredicate == null ? (uuid -> false) : escapeWindowPredicate;
    }

    /** 插件禁用：取消检测任务并清理击退记录。 */
    public void disable() {
        ensureMainThread("disable");
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        clear();
    }

    /**
     * 记录一次击退（由 {@link DuelAreaListener} 在伤害事件中调用）。
     * 仅当受害者正在决斗中、攻击方非空且非自伤时记录。
     */
    @Override
    public void recordKnockback(Player victim, Entity damager) {
        if (victim == null || damager == null || victim.equals(damager)
                || !duelManager.isInDuel(victim.getUniqueId())) {
            return;
        }
        Location victimLoc = victim.getLocation();
        Vector direction = resolveKnockbackDirection(victimLoc, damager);
        if (direction == null) {
            return;
        }
        long expireAt = System.currentTimeMillis() + config.knockbackGraceTicks() * 50L;
        knockbacks.put(victim.getUniqueId(), new KnockbackInfo(expireAt, direction));
    }

    /** 玩家下线：清理其击退记录（防泄漏）。 */
    @Override
    public void purge(UUID uuid) {
        knockbacks.remove(uuid);
    }

    /** 兼容旧入口名（= {@link #purge}）。 */
    public void purgePlayer(UUID uuid) {
        purge(uuid);
    }

    /** 插件禁用：清空全部击退记录。 */
    @Override
    public void clear() {
        knockbacks.clear();
    }

    /** 决斗结束：清理双方击退记录（防泄漏）。 */
    public void onDuelEnded(DuelEndedEvent event) {
        Duel duel = event.getDuel();
        if (duel != null) {
            knockbacks.remove(duel.getPlayerAUuid());
            knockbacks.remove(duel.getPlayerBUuid());
        }
    }

    /**
     * 落点是否越界（用于拦截主动传送）：
     * 玩家处于 PENDING / ACTIVE 决斗中，且目标位置（跨世界或水平距离）超过边界。
     */
    public boolean isBeyondBoundary(Player player, Location to) {
        if (player == null || to == null) {
            return false;
        }
        // 处决窗口：玩家可逃离，主动传送（珍珠/紫颂果）落点越界不拦截
        if (escapeWindowPredicate.test(player.getUniqueId())) {
            return false;
        }
        Optional<Duel> opt = duelManager.getDuel(player.getUniqueId());
        if (opt.isEmpty()) {
            return false;
        }
        DuelState state = opt.get().getState();
        if (state != DuelState.PENDING && state != DuelState.ACTIVE) {
            return false;
        }
        DuelIsland island = opt.get().getIsland();
        if (island == null) {
            return false;
        }
        if (to.getWorld() == null || !to.getWorld().equals(island.getWorld())) {
            return true;
        }
        return island.horizontalDistanceTo(to) > config.innerRadius() + 1.0e-9;
    }

    /** 周期越界检测（主线程）：遍历全部进行中的决斗。 */
    private void check() {
        for (Duel duel : duelManager.getDuels()) {
            DuelState state = duel.getState();
            if (state != DuelState.PENDING && state != DuelState.ACTIVE) {
                continue;
            }
            DuelIsland island = duel.getIsland();
            if (island == null) {
                continue;
            }
            // 处决窗口：任一方崩条 → 双方可逃离决斗场地，跳过越界拉回
            if (escapeWindowPredicate.test(duel.getPlayerAUuid())
                    || escapeWindowPredicate.test(duel.getPlayerBUuid())) {
                continue;
            }
            checkPlayer(duel.getPlayerA(), island);
            checkPlayer(duel.getPlayerB(), island);
        }
    }

    private void checkPlayer(Player player, DuelIsland island) {
        if (player == null || !player.isOnline()) {
            return;
        }
        World islandWorld = island.getWorld();
        if (!player.getWorld().equals(islandWorld)) {
            // 已脱离岛屿世界：直接拉回
            pullBack(player, island);
            return;
        }
        Location loc = player.getLocation();
        // 虚空豁免：被击落虚空坠落中一律不拉回（直至死亡）
        if (loc.getY() < islandWorld.getMinHeight()) {
            return;
        }
        if (island.horizontalDistanceTo(loc) <= config.innerRadius() + 1.0e-9) {
            return;
        }
        // 处于击退窗口且位移方向与击退方向一致 → 视为击退位移，不拉回
        if (isKnockbackDisplacement(player, island)) {
            return;
        }
        pullBack(player, island);
    }

    /** 当前越界位移是否来自“被攻击击退”（窗口内 + 位移方向与击退方向一致）。 */
    @Override
    public boolean isKnockbackDisplacement(Player player, DuelIsland island) {
        KnockbackInfo info = knockbacks.get(player.getUniqueId());
        if (info == null || System.currentTimeMillis() > info.expireAt) {
            knockbacks.remove(player.getUniqueId());
            return false;
        }
        Location loc = player.getLocation();
        Vector offset = loc.toVector()
                .subtract(new Vector(island.getCenterX(), loc.getY(), island.getCenterZ()));
        offset.setY(0.0);
        double len = offset.length();
        if (len < 1.0e-6) {
            return false;
        }
        Vector offsetDir = offset.normalize();
        return info.direction().dot(offsetDir) > 0.0;
    }

    /** 把玩家拉回边界内（距圆心 {@code inner-radius - pullback-margin}，至少 1 格），不发送提示。 */
    private void pullBack(Player player, DuelIsland island) {
        Location loc = player.getLocation();
        double boundary = Math.max(1.0, config.innerRadius() - config.pullbackMargin());
        double dx = loc.getX() - island.getCenterX();
        double dz = loc.getZ() - island.getCenterZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0e-6) {
            return;
        }
        double targetX = island.getCenterX() + dx / dist * boundary;
        double targetZ = island.getCenterZ() + dz / dist * boundary;
        Location target = new Location(island.getWorld(), targetX, loc.getY(), targetZ,
                loc.getYaw(), loc.getPitch());
        player.teleport(target);
    }

    /** 攻击方→受害者的水平击退方向（投射物优先取飞行速度方向）。 */
    private Vector resolveKnockbackDirection(Location victimLoc, Entity damager) {
        if (damager instanceof Projectile projectile) {
            Vector velocity = projectile.getVelocity().clone().setY(0.0);
            double len = velocity.length();
            if (len > 1.0e-6) {
                return velocity.normalize();
            }
        }
        Location damagerLoc = damager.getLocation();
        if (damagerLoc == null || damagerLoc.getWorld() == null
                || !damagerLoc.getWorld().equals(victimLoc.getWorld())) {
            return null;
        }
        Vector dir = victimLoc.toVector().subtract(damagerLoc.toVector()).setY(0.0);
        double len = dir.length();
        if (len < 1.0e-6) {
            return null;
        }
        return dir.normalize();
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败，防止异步线程篡改状态）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("DuelAreaGuard." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
