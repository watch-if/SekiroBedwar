package org.alpha.sekiroBedwar.api.internal;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.api.DuelEndReason;
import org.alpha.sekiroBedwar.api.DuelInfo;
import org.alpha.sekiroBedwar.api.DuelPhase;
import org.alpha.sekiroBedwar.api.StancePhase;
import org.alpha.sekiroBedwar.api.StanceSnapshot;
import org.alpha.sekiroBedwar.api.TechniqueCancelReason;
import org.alpha.sekiroBedwar.api.TechniqueFailReason;
import org.alpha.sekiroBedwar.api.TechniqueId;
import org.alpha.sekiroBedwar.api.ToolId;
import org.alpha.sekiroBedwar.api.ToolUseResult;
import org.alpha.sekiroBedwar.api.events.BlockEvent;
import org.alpha.sekiroBedwar.api.events.DangerAttackEvent;
import org.alpha.sekiroBedwar.api.events.DuelDeathEvent;
import org.alpha.sekiroBedwar.api.events.DuelEndEvent;
import org.alpha.sekiroBedwar.api.events.DuelInterruptEvent;
import org.alpha.sekiroBedwar.api.events.DuelSettlementEvent;
import org.alpha.sekiroBedwar.api.events.DuelStartEvent;
import org.alpha.sekiroBedwar.api.events.HitLandedEvent;
import org.alpha.sekiroBedwar.api.events.MikiriEvent;
import org.alpha.sekiroBedwar.api.events.PerfectParryEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCancelEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCompleteEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueFailEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueHitEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueStartEvent;
import org.alpha.sekiroBedwar.api.events.ShinobiToolUseEvent;
import org.alpha.sekiroBedwar.api.events.ShieldBreakEvent;
import org.alpha.sekiroBedwar.api.events.StanceBreakEvent;
import org.alpha.sekiroBedwar.api.events.StanceChangeEvent;
import org.alpha.sekiroBedwar.api.events.StanceCriticalEnterEvent;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.duel.EndReason;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 公共 API 的核心侧实现（<b>内部类</b>：核心模块经静态 fire* 方法发射事件；
 * 外部插件只依赖 {@code org.alpha.sekiroBedwar.api} 契约包，不应 import 本包）。
 *
 * <p>未 install（如单测裸跑战斗类）时所有 fire 为 no-op，核心行为不受影响。</p>
 */
public final class SekiroApiImpl {

    private static SekiroApiImpl instance;

    private final SekiroBedwar plugin;
    private final DuelManager duelManager;
    private final StanceManager stanceManager;

    private SekiroApiImpl(SekiroBedwar plugin, DuelManager duelManager, StanceManager stanceManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.stanceManager = stanceManager;
    }

    /** 主线程安装（onEnable 装配早期）；重复调用覆盖。 */
    public static void install(SekiroBedwar plugin, DuelManager duelManager, StanceManager stanceManager) {
        instance = new SekiroApiImpl(plugin, duelManager, stanceManager);
    }

    public static void uninstall() {
        instance = null;
    }

    public static SekiroApiImpl get() {
        return instance;
    }

    // ==================== 查询 ====================

    public static boolean isInDuel(UUID uuid) {
        SekiroApiImpl i = instance;
        return i != null && uuid != null && i.duelManager.isInDuel(uuid);
    }

    public static Optional<DuelInfo> duelInfo(UUID uuid) {
        SekiroApiImpl i = instance;
        if (i == null || uuid == null) {
            return Optional.empty();
        }
        return i.duelManager.getDuel(uuid).map(d -> snapshot(d, null));
    }

    public static List<DuelInfo> activeDuels() {
        SekiroApiImpl i = instance;
        if (i == null) {
            return List.of();
        }
        List<DuelInfo> out = new ArrayList<>();
        for (Duel d : i.duelManager.getDuels()) {
            out.add(snapshot(d, null));
        }
        return Collections.unmodifiableList(out);
    }

    public static StanceSnapshot stance(UUID uuid) {
        SekiroApiImpl i = instance;
        if (i == null || uuid == null) {
            return new StanceSnapshot(uuid, 0, 0, StancePhase.NONE, false, System.currentTimeMillis());
        }
        double cur = i.stanceManager.getStance(uuid);
        double max = i.stanceManager.getMaxStance(uuid);
        StancePhase phase = i.phaseOf(uuid);
        boolean guard = i.stanceManager.isStaggered(uuid);
        return new StanceSnapshot(uuid, cur, max, phase, guard, System.currentTimeMillis());
    }

    private StancePhase phaseOf(UUID uuid) {
        if (!stanceManager.hasStance(uuid)) {
            return StancePhase.NONE;
        }
        if (stanceManager.isBroken(uuid)) {
            return StancePhase.BROKEN;
        }
        if (stanceManager.isCritical(uuid)) {
            return StancePhase.CRITICAL;
        }
        return StancePhase.NORMAL;
    }

    // ==================== 内部 helper ====================

    /** 玩家当前所属决斗 id（不在决斗 = null）；未安装返回 null。 */
    public static UUID duelId(UUID player) {
        SekiroApiImpl i = instance;
        if (i == null || player == null) {
            return null;
        }
        return i.duelManager.getDuel(player).map(Duel::getId).orElse(null);
    }

    private static void fire(Event event) {
        SekiroApiImpl i = instance;
        if (i == null) {
            return;
        }
        Bukkit.getPluginManager().callEvent(event);
    }

    static ApiDuelInfo snapshot(Duel duel, DuelEndReason reason) {
        UUID gameId = null;
        try {
            if (duel.getGame() != null) {
                gameId = duel.getGame().getUuid();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 对局对象已随游戏结束释放：gameId 置空即可
        }
        return new ApiDuelInfo(duel.getId(), duel.getPlayerAUuid(), duel.getPlayerBUuid(),
                phaseOf(duel), gameId, duel.getCreatedAt(),
                System.currentTimeMillis() - duel.getCreatedAt(), reason);
    }

    private static DuelPhase phaseOf(Duel duel) {
        DuelState s = duel.getState();
        return switch (s) {
            case ACTIVE -> DuelPhase.ACTIVE;
            case ENDING -> DuelPhase.ENDING;
            default -> DuelPhase.PENDING;
        };
    }

    /** 内部原因 → 公共枚举映射（未知值归 OTHER，保证前向兼容）。 */
    public static DuelEndReason map(EndReason reason) {
        if (reason == null) {
            return DuelEndReason.OTHER;
        }
        return switch (reason) {
            case EXECUTED -> DuelEndReason.EXECUTED;
            case EXECUTION_TIMEOUT -> DuelEndReason.EXECUTION_TIMEOUT;
            case VOID_DEATH -> DuelEndReason.VOID_DEATH;
            case THIRD_PARTY_ENTERED -> DuelEndReason.THIRD_PARTY_INTERRUPTED;
            case PLAYER_QUIT -> DuelEndReason.PLAYER_QUIT;
            case PLAYER_LEFT_GAME -> DuelEndReason.PLAYER_LEFT_GAME;
            case GAME_ENDED -> DuelEndReason.GAME_ENDED;
            case PLUGIN_DISABLE -> DuelEndReason.PLUGIN_DISABLE;
            default -> DuelEndReason.OTHER;
        };
    }

    // ==================== 决斗 / 结算 ====================

    public static void duelStart(Duel duel) {
        fire(new DuelStartEvent(snapshot(duel, null)));
    }

    public static void duelEnd(Duel duel, EndReason internalReason) {
        DuelEndReason reason = map(internalReason);
        fire(new DuelEndEvent(snapshot(duel, reason), reason));
        if (internalReason == EndReason.THIRD_PARTY_ENTERED) {
            fire(new DuelInterruptEvent(snapshot(duel, reason)));
        }
    }

    public static void duelDeath(Duel duel, UUID winner, UUID loser, double ratio, boolean voidDeath) {
        fire(new DuelDeathEvent(snapshot(duel, null), winner, loser, ratio, voidDeath));
    }

    public static void duelSettlement(Duel duel, DuelSettlementEvent.Outcome outcome,
                                      UUID winner, UUID loser, double ratio,
                                      Map<UUID, Map<Material, Integer>> received) {
        fire(new DuelSettlementEvent(snapshot(duel, null), outcome, winner, loser, ratio,
                Collections.unmodifiableMap(received)));
    }

    // ==================== 架势 ====================

    /** StanceManager 观察者入口（数值型变化；自然恢复不进入）。 */
    public void onChange(UUID player, double before, double after, double max) {
        StancePhase phase = phaseOf(player);
        fire(new StanceChangeEvent(player, duelId(player), before, after, max, phase));
        // NORMAL → CRITICAL 跃迁提示（崩条由 StanceBreakEvent 专报）
        if (phase == StancePhase.CRITICAL && before > 0.0 && after < before) {
            fire(new StanceCriticalEnterEvent(player, duelId(player), max));
        }
    }

    public static void stanceChange(UUID player, double before, double after, double max) {
        SekiroApiImpl i = instance;
        if (i != null) {
            i.onChange(player, before, after, max);
        }
    }

    public static void stanceBreak(UUID player, UUID opponent) {
        fire(new StanceBreakEvent(player, opponent, duelId(player)));
    }

    // ==================== 战斗 ====================

    public static void perfectParry(UUID parryer, UUID attacker, Material weapon, double attackerStanceLoss) {
        fire(new PerfectParryEvent(parryer, attacker, duelId(parryer), weapon, attackerStanceLoss));
    }

    public static void block(UUID defender, UUID attacker, Material weapon, double stanceLoss, boolean projectile) {
        fire(new BlockEvent(defender, attacker, duelId(defender), weapon, stanceLoss, projectile));
    }

    public static void shieldBreak(UUID defender, UUID attacker, ShieldBreakEvent.Cause cause, double stanceLoss) {
        fire(new ShieldBreakEvent(defender, attacker, duelId(defender), cause, stanceLoss));
    }

    public static void hitLanded(UUID attacker, UUID victim, Material weapon,
                                 double stanceLoss, double healthDamage, boolean melee) {
        fire(new HitLandedEvent(attacker, victim, duelId(victim), weapon, stanceLoss, healthDamage, melee));
    }

    public static void dangerAttack(UUID attacker, UUID victim, boolean blocked, boolean mikiri) {
        fire(new DangerAttackEvent(attacker, victim, duelId(victim), blocked, mikiri));
    }

    public static void mikiri(UUID performer, UUID attacker) {
        fire(new MikiriEvent(performer, attacker, duelId(performer)));
    }

    // ==================== 秘传 ====================

    public static void techStart(UUID player, TechniqueId id) {
        fire(new SecretTechniqueStartEvent(player, id, duelId(player)));
    }

    public static void techHit(UUID player, TechniqueId id, int hitIndex, boolean parried, UUID target) {
        fire(new SecretTechniqueHitEvent(player, id, duelId(player), hitIndex, parried, target));
    }

    public static void techComplete(UUID player, TechniqueId id, int totalHits, int validHits) {
        fire(new SecretTechniqueCompleteEvent(player, id, duelId(player), totalHits, validHits));
    }

    public static void techFail(UUID player, TechniqueId id, TechniqueFailReason reason, int reachedHits) {
        fire(new SecretTechniqueFailEvent(player, id, duelId(player), reason, reachedHits));
    }

    public static void techCancel(UUID player, TechniqueId id, TechniqueCancelReason reason, int reachedHits) {
        fire(new SecretTechniqueCancelEvent(player, id, duelId(player), reason, reachedHits));
    }

    // ==================== 忍具 ====================

    public static void toolUse(UUID player, ToolId tool, UUID target, ToolUseResult result) {
        fire(new ShinobiToolUseEvent(player, tool, target, duelId(player), result));
    }

    /** {@link DuelInfo} 不可变快照。 */
    record ApiDuelInfo(UUID id, UUID playerA, UUID playerB, DuelPhase phase, UUID gameId,
                       long startedAtEpochMillis, long durationMillis, DuelEndReason endReason)
            implements DuelInfo {
    }
}
