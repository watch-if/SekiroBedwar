package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.Team;
import org.screamingsandals.bedwars.api.events.GameEndEvent;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 决斗生命周期管理器（独立模块，与 {@link DuelTriggerManager} 触发判定解耦）。
 *
 * <p>状态机：{@code createDuel} → PENDING →（pending-seconds 后自动）→ ACTIVE → ENDING → 移除（回到 NONE）。</p>
 *
 * <ul>
 *   <li>创建决斗：{@link #createDuel}（任一方已处于决斗中则拒绝，保证一名玩家不能同时属于多个决斗）；</li>
 *   <li>查询：{@link #isInDuel} / {@link #getState} / {@link #getDuel}（只读，并发安全）；</li>
 *   <li>结束：{@link #endDuel} / {@link #endAll} / {@link #purgePlayer}；</li>
 *   <li>第三方进入排除范围（岛屿半径 + 外圆半径 z）→ 结束决斗（周期性 {@code tick} 检测）；</li>
 *   <li>决斗者下线 / 离局 / 死亡成观战 / 对局结束 → 自动结束。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：所有写操作通过 {@link #ensureMainThread} 守卫强制在 Bukkit 主线程执行
 * （事件回调与调度任务天然主线程）；状态容器用 {@link ConcurrentHashMap}，读操作任意线程安全；
 * {@link Duel#getState()} 等可变字段为 volatile，转移经 synchronized CAS。</p>
 *
 * <p><b>内存泄漏防护</b>：全部退出路径移除双方登记与 {@link #duels} 引用；单一 {@code checkTask}
 * 于 {@link #disable()} 时取消；{@link Duel} 只存 UUID 不持有 Player 引用。</p>
 */
public final class DuelManager {
    private final SekiroBedwar plugin;
    private final DuelConfig config;
    private final DuelLifecycleListener listener;

    /** 玩家 UUID → 其唯一所属决斗。 */
    private final ConcurrentMap<UUID, Duel> playerDuels = new ConcurrentHashMap<>();
    /** 全部进行中的决斗。 */
    private final Set<Duel> duels = ConcurrentHashMap.newKeySet();

    private BukkitTask checkTask;

    public DuelManager(SekiroBedwar plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.listener = new DuelLifecycleListener(this);
    }

    /** 注册 Bukkit 监听与 BedWars API 事件，启动周期检测。 */
    public void enable() {
        ensureMainThread("enable");
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        if (Bukkit.getPluginManager().getPlugin("ScreamingBedWars") == null) {
            plugin.getLogger().warning("未检测到 ScreamingBedWars，DuelManager 对局生命周期事件未注册（第三方/离局检测不生效）");
        } else {
            // BedWars API 事件：玩家离局 / 整局结束 时结束相关决斗
            PlayerLeaveEvent.handle(plugin, ev -> purgePlayer(ev.getPlayer().getUuid(), EndReason.PLAYER_LEFT_GAME));
            GameEndEvent.handle(plugin, ev -> endAll(EndReason.GAME_ENDED));
        }
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, Math.max(1, config.duelCheckTicks()));
    }

    /** 插件禁用：取消检测任务并结束所有决斗。 */
    public void disable() {
        ensureMainThread("disable");
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        endAll(EndReason.PLUGIN_DISABLE);
    }

    /**
     * 创建一场决斗（状态 PENDING）。
     *
     * @return 是否创建成功；任一方已处于决斗中、离线、自伤或参数不完整时返回 false
     */
    public boolean createDuel(Player a, Player b, LocalGame game, DuelIsland island) {
        ensureMainThread("createDuel");
        if (a == null || b == null || a.equals(b) || game == null || island == null
                || !a.isOnline() || !b.isOnline()) {
            return false;
        }
        UUID ua = a.getUniqueId();
        UUID ub = b.getUniqueId();
        // 单决斗互斥：任一方已处于决斗中则拒绝
        if (playerDuels.containsKey(ua) || playerDuels.containsKey(ub)) {
            return false;
        }
        Duel duel = new Duel(UUID.randomUUID(), ua, ub, game, island);
        if (playerDuels.putIfAbsent(ua, duel) != null) {
            return false;
        }
        if (playerDuels.putIfAbsent(ub, duel) != null) {
            playerDuels.remove(ua, duel);
            return false;
        }
        duels.add(duel);
        return true;
    }

    /** 玩家是否正在决斗。 */
    public boolean isInDuel(UUID uuid) {
        return playerDuels.containsKey(uuid);
    }

    /** 玩家是否正在决斗。 */
    public boolean isInDuel(Player player) {
        return player != null && isInDuel(player.getUniqueId());
    }

    /** 玩家当前决斗状态；不在任何决斗中返回 {@link DuelState#NONE}。 */
    public DuelState getState(UUID uuid) {
        Duel duel = playerDuels.get(uuid);
        return duel == null ? DuelState.NONE : duel.getState();
    }

    /** 玩家当前决斗状态；不在任何决斗中返回 {@link DuelState#NONE}。 */
    public DuelState getState(Player player) {
        return player == null ? DuelState.NONE : getState(player.getUniqueId());
    }

    /** 玩家所属的决斗；不在决斗中返回空。 */
    public Optional<Duel> getDuel(UUID uuid) {
        return Optional.ofNullable(playerDuels.get(uuid));
    }

    /** 玩家所属的决斗；不在决斗中返回空。 */
    public Optional<Duel> getDuel(Player player) {
        return player == null ? Optional.empty() : getDuel(player.getUniqueId());
    }

    /** 全部进行中的决斗（只读快照，供 {@link DuelAreaGuard} 等枚举）。 */
    public List<Duel> getDuels() {
        return List.copyOf(duels);
    }

    /** 结束指定决斗。 */
    public boolean endDuel(Duel duel, EndReason reason) {
        ensureMainThread("endDuel");
        if (duel == null || !duels.contains(duel)) {
            return false;
        }
        finishDuel(duel, reason);
        return true;
    }

    /** 结束某玩家所在的决斗。 */
    public boolean endDuel(Player player, EndReason reason) {
        if (player == null) {
            return false;
        }
        Duel duel = playerDuels.get(player.getUniqueId());
        return duel != null && endDuel(duel, reason);
    }

    /** 结束全部进行中的决斗（对局结束 / 插件禁用）。 */
    public void endAll(EndReason reason) {
        ensureMainThread("endAll");
        for (Duel duel : List.copyOf(duels)) {
            finishDuel(duel, reason);
        }
    }

    /** 玩家下线清理（{@code PlayerQuitEvent}），结束其所在决斗。 */
    public void purgePlayer(UUID uuid) {
        purgePlayer(uuid, EndReason.PLAYER_QUIT);
    }

    /** 玩家离开对局 / 下线时结束其所在决斗（可指定结束原因）。 */
    public void purgePlayer(UUID uuid, EndReason reason) {
        ensureMainThread("purgePlayer");
        Duel duel = playerDuels.get(uuid);
        if (duel != null) {
            finishDuel(duel, reason);
        }
    }

    /**
     * 周期检测（主线程，由 {@code checkTask} 驱动）：
     * 决斗者失效 / 对局结束 → 结束；PENDING 到期 → ACTIVE；第三方进入排除范围 → 结束。
     */
    private void tick() {
        for (Duel duel : List.copyOf(duels)) {
            if (duel.getState() == DuelState.ENDING || !duels.contains(duel)) {
                continue;
            }
            Player pa = duel.getPlayerA();
            Player pb = duel.getPlayerB();
            if (!isDuelistActive(pa)) {
                finishDuel(duel, EndReason.PLAYER_QUIT);
                continue;
            }
            if (!isDuelistActive(pb)) {
                finishDuel(duel, EndReason.PLAYER_QUIT);
                continue;
            }
            LocalGame game = duel.getGame();
            if (game == null || game.getStatus() != GameStatus.RUNNING) {
                finishDuel(duel, EndReason.GAME_ENDED);
                continue;
            }
            // PENDING → ACTIVE（缓冲到期自动推进）
            if (duel.getState() == DuelState.PENDING
                    && System.currentTimeMillis() - duel.getCreatedAt() >= config.duelPendingSeconds() * 1000.0) {
                duel.transition(DuelState.PENDING, DuelState.ACTIVE);
            }
            // 第三方进入排除范围（PENDING 与 ACTIVE 都检查）
            if (hasThirdParty(duel, game)) {
                finishDuel(duel, EndReason.THIRD_PARTY_ENTERED);
            }
        }
    }

    /** 决斗者是否仍然有效：在线、在对局中、非观战、所在队伍存活。 */
    private boolean isDuelistActive(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        Optional<? extends BWPlayer> opt = BedwarsAPI.getInstance().getPlayerManager().getPlayer(player.getUniqueId());
        if (opt.isEmpty()) {
            return false;
        }
        BWPlayer bw = opt.get();
        if (bw.isSpectator() || !bw.isInGame()) {
            return false;
        }
        LocalGame game = bw.getGame();
        if (game == null) {
            return false;
        }
        Team team = game.getTeamOfPlayer(bw);
        return team != null && team.isAlive();
    }

    /** 岛屿外围（半径 radius + 外圆 z）是否存在在线第三方玩家。 */
    private boolean hasThirdParty(Duel duel, LocalGame game) {
        DuelIsland island = duel.getIsland();
        double limit = island.getRadius() + config.outerRadius();
        UUID a = duel.getPlayerAUuid();
        UUID b = duel.getPlayerBUuid();
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
            if (island.horizontalDistanceTo(bukkit.getLocation()) <= limit) {
                return true;
            }
        }
        return false;
    }

    /** 收尾：置 ENDING → 广播 DuelEndedEvent → 移除双方登记与决斗集合。 */
    private void finishDuel(Duel duel, EndReason reason) {
        if (!duel.markEnding(reason)) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new DuelEndedEvent(duel));
        playerDuels.remove(duel.getPlayerAUuid());
        playerDuels.remove(duel.getPlayerBUuid());
        duels.remove(duel);
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败，防止异步线程篡改状态）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("DuelManager." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
