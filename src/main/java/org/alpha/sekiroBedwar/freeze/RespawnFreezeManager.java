package org.alpha.sekiroBedwar.freeze;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.Team;
import org.screamingsandals.bedwars.api.events.GameEndEvent;
import org.screamingsandals.bedwars.api.events.PlayerKilledEvent;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.game.target.TargetBlock;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队伍复活冻结（独立模块）。
 *
 * <p>当某队伍<b>床</b>位于任一决斗的白色内圈范围内（触发判定与 {@link ResourceFreezeManager}
 * 共用同一 {@link DuelConfig#innerRadius()} 白圈），该队成员在决斗期间死亡时<b>不开始
 * 正常复活倒计时</b>，进入待复活（旁观挂起）状态；决斗结束后恢复正常复活流程。</p>
 *
 * <p>实现依赖两个 BedWars 内部时序（均不改源码）：
 * <ul>
 *   <li><b>冻结</b>：{@link PlayerKilledEvent}（{@code PlayerListener}:206-207）在复活倒计时判定
 *       （:291-293，条件 {@code ... && !isSpectator()}）<b>之前</b>同步触发。此时经反射把
 *       {@code spectator} 置 true → 倒计时被跳过；随后 vanilla respawn（3 tick）走
 *       {@code onPlayerRespawn} 旁观分支 → 玩家成为待复活旁观者（挂起状态成立）。</li>
 *   <li><b>恢复</b>：反射调用 {@code GameImpl.makePlayerFromSpectator}（:1163-1216，不在 API 上）——
 *       与倒计时到 0 的恢复路径完全一致（setSpectator(false) + 传送队伍出生点 + 重生物品 +
 *       fire PlayerRespawnedEvent）=「恢复正常复活流程」。</li>
 * </ul>
 * </p>
 *
 * <p>边界处理：床存在 → 决斗结束正常恢复；死亡时床已无 → 已是 spectator，冻结逻辑跳过；
 * 冻结期间床被拆 / 队伍灭亡 → 视为最终死亡，移出队伍保持观战不复活；
 * 决斗被第三方解除 / 玩家下线 / 离局 / 主动结束 / 对局结束 → 全部经 {@link DuelEndedEvent} 触发恢复判定。</p>
 *
 * <p>线程安全：全部写操作经事件回调 / 调度在主线程执行；{@code frozen} 用 ConcurrentHashMap 兜底。</p>
 */
public final class RespawnFreezeManager {
    private final SekiroBedwar plugin;
    private final FreezeConfig config;
    private final DuelConfig duelConfig;
    private final DuelManager duelManager;

    /** 被冻结（待复活）玩家 UUID → 其对局。 */
    private final Map<UUID, LocalGame> frozen = new ConcurrentHashMap<>();

    public RespawnFreezeManager(SekiroBedwar plugin, FreezeConfig config, DuelConfig duelConfig,
                                DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.duelConfig = duelConfig;
        this.duelManager = duelManager;
    }

    /** 注册监听与 BedWars API 事件。 */
    public void enable() {
        ensureMainThread("enable");
        plugin.getServer().getPluginManager().registerEvents(new RespawnFreezeListener(this), plugin);
        if (!config.respawnEnabled()) {
            return;
        }
        PlayerKilledEvent.handle(plugin, this::onPlayerKilled);
        PlayerLeaveEvent.handle(plugin, ev -> unfreeze(ev.getPlayer().getUuid()));
        GameEndEvent.handle(plugin, ev -> frozen.clear());
    }

    /** 插件禁用：清空冻结状态（先于 {@code DuelManager.disable()} 调用，避免关闭时误恢复）。 */
    public void disable() {
        ensureMainThread("disable");
        frozen.clear();
    }

    /**
     * 死亡时冻结：床位于任一进行中决斗白圈内、且非决斗者本人的死亡 → 置 spectator(true) 挂起复活。
     * 同步早于 BedWars 的复活倒计时判定执行。
     */
    private void onPlayerKilled(PlayerKilledEvent ev) {
        if (!config.respawnEnabled()) {
            return;
        }
        BWPlayer victim = ev.getPlayer();
        if (victim == null || victim.isSpectator()) {
            // 床已无 / 已最终死亡：BedWars 已走旁观流程，不冻结
            return;
        }
        LocalGame game = ev.getGame();
        if (game == null || duelManager.isInDuel(victim.getUuid())) {
            // 决斗者本人死亡：其决斗随之结束，走正常复活流程，不冻结
            return;
        }
        Team team = game.getTeamOfPlayer(victim);
        if (team == null || !bedInActiveDuelCircle(game, team)) {
            return;
        }
        if (setSpectator(victim, true)) {
            frozen.put(victim.getUuid(), game);
        }
    }

    /**
     * 决斗结束：对同一对局的所有冻结玩家重新判定「床是否仍在某进行中决斗白圈内」。
     * 仍覆盖 → 保持冻结；不再覆盖 → 走恢复门控。
     */
    void onDuelEnded(DuelEndedEvent ev) {
        if (!config.respawnEnabled()) {
            return;
        }
        LocalGame game = ev.getGame();
        if (game == null) {
            return;
        }
        for (Map.Entry<UUID, LocalGame> entry : frozen.entrySet()) {
            if (entry.getValue() != game) {
                continue;
            }
            UUID uuid = entry.getKey();
            if (!resume(uuid, game)) {
                frozen.remove(uuid, game);
            }
        }
    }

    /** 尝试恢复一名冻结玩家；返回 true 表示已恢复（或保持冻结）。 */
    private boolean resume(UUID uuid, LocalGame game) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return false; // 已下线：移除冻结条目，不复活
        }
        Optional<? extends BWPlayer> opt = BedwarsAPI.getInstance().getPlayerManager().getPlayer(uuid);
        if (opt.isEmpty()) {
            return false;
        }
        BWPlayer bw = opt.get();
        if (!bw.isInGame() || bw.getGame() != game) {
            return false; // 已离开本对局
        }
        if (game.getStatus() != GameStatus.RUNNING) {
            return false; // 对局结束 / 队伍灭亡整体出局：不复活
        }
        Team team = game.getTeamOfPlayer(bw);
        if (team == null) {
            return false; // 已被移除队伍（最终死亡）
        }
        if (bedInActiveDuelCircle(game, team)) {
            return true; // 另一场决斗仍覆盖床：保持冻结
        }
        resumePlayer(bw, game, team);
        return true;
    }

    /** 实际恢复：床仍存在且队伍存活 → makePlayerFromSpectator；床被拆 / 队伍灭亡 → 移出队伍不复活。 */
    private void resumePlayer(BWPlayer bw, LocalGame game, Team team) {
        if (!team.getTarget().isValid() || team.isDead()) {
            // 冻结期间床被拆 / 队伍灭亡：视为最终死亡——从队伍列表移除（防幽灵队伍），保持观战不复活
            team.getPlayers().remove(bw);
            return;
        }
        Player player = Bukkit.getPlayer(bw.getUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        // 1 tick 延迟规避「死亡后 vanilla respawn（3 tick）尚未完成」的竞态；两条路径最终都导向
        // 队伍出生点 + PlayerRespawnedEvent，重复无害。
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (setSpectator(bw, false)) {
                makePlayerFromSpectator(game, bw);
            }
        }, 1L);
    }

    /** 床是否位于任一进行中决斗的白色内圈内（PENDING 与 ACTIVE 都算「决斗期间」）。 */
    private boolean bedInActiveDuelCircle(LocalGame game, Team team) {
        if (!(team.getTarget() instanceof TargetBlock targetBlock)) {
            return false;
        }
        Location bed = targetBlock.getTargetBlock().as(Location.class);
        if (bed == null) {
            return false;
        }
        double inner = duelConfig.innerRadius();
        for (Duel duel : duelManager.getDuels()) {
            if (duel.getGame() != game || duel.getState() == DuelState.ENDING) {
                continue;
            }
            DuelIsland island = duel.getIsland();
            if (island.getWorld().equals(bed.getWorld()) && island.horizontalDistanceTo(bed) <= inner) {
                return true;
            }
        }
        return false;
    }

    /** 玩家离开对局 / 退出服务器：移除冻结条目（不复活）。 */
    void unfreeze(UUID uuid) {
        frozen.remove(uuid);
    }

    /**
     * 反射设置 spectator（{@code BWPlayer} 只读；运行时对象是 {@code BedWarsPlayer}，
     * 其 {@code @Setter private boolean spectator} 生成 public setter，不经编译期依赖内部类）。
     */
    private boolean setSpectator(BWPlayer bw, boolean value) {
        try {
            bw.getClass().getMethod("setSpectator", boolean.class).invoke(bw, value);
            return true;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("反射设置 spectator 失败: " + bw.getUuid() + " -> " + ex);
            return false;
        }
    }

    /**
     * 反射调用 {@code GameImpl.makePlayerFromSpectator(BedWarsPlayer)}（不在 {@link LocalGame} API 上，
     * 是倒计时到 0 的权威恢复路径）。按方法名 + 单参数匹配，规避对插件内部类的编译期依赖。
     */
    private boolean makePlayerFromSpectator(LocalGame game, BWPlayer bw) {
        try {
            for (Method m : game.getClass().getMethods()) {
                if (m.getName().equals("makePlayerFromSpectator") && m.getParameterCount() == 1) {
                    m.invoke(game, bw);
                    return true;
                }
            }
            plugin.getLogger().warning("未找到 makePlayerFromSpectator 方法（ScreamingBedWars 版本不匹配？）");
            return false;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("反射调用 makePlayerFromSpectator 失败: " + bw.getUuid() + " -> " + ex);
            return false;
        }
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RespawnFreezeManager." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
