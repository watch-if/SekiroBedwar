package org.alpha.sekiroBedwar.duel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.screamingsandals.bedwars.api.game.LocalGame;

import java.util.UUID;

/**
 * 一场进行中的决斗实体。
 *
 * <p>只保存玩家的 {@link UUID} 而非 {@link Player} 引用（玩家离线后不持有引用，
 * 避免内存泄漏）；玩家对象通过 {@link Bukkit#getPlayer(UUID)} 惰性解析，可能为 null。</p>
 *
 * <p>可变状态字段（{@link #state} / {@link #endReason}）为 {@code volatile}，
 * 转移经 {@link #transition(DuelState, DuelState)} 的 {@code synchronized} CAS 完成，
 * 配合 {@link DuelManager} 的主线程守卫保证线程安全。</p>
 */
public final class Duel {
    private final UUID id;
    private final UUID playerA;
    private final UUID playerB;
    private final LocalGame game;
    private final DuelIsland island;
    private final long createdAt;
    private final Object stateLock = new Object();

    private volatile DuelState state;
    private volatile EndReason endReason;

    Duel(UUID id, UUID playerA, UUID playerB, LocalGame game, DuelIsland island) {
        this.id = id;
        this.playerA = playerA;
        this.playerB = playerB;
        this.game = game;
        this.island = island;
        this.createdAt = System.currentTimeMillis();
        this.state = DuelState.PENDING;
    }

    /** 决斗唯一标识。 */
    public UUID getId() {
        return id;
    }

    /** 决斗玩家 A 的 UUID。 */
    public UUID getPlayerAUuid() {
        return playerA;
    }

    /** 决斗玩家 B 的 UUID。 */
    public UUID getPlayerBUuid() {
        return playerB;
    }

    /** 两名玩家所属的同一对局（对局结束 / 清理后引用随之释放）。 */
    public LocalGame getGame() {
        return game;
    }

    /** 触发时推导出的决斗岛屿（圆心 / 半径）。 */
    public DuelIsland getIsland() {
        return island;
    }

    /** 决斗创建时间戳（毫秒）。 */
    public long getCreatedAt() {
        return createdAt;
    }

    /** 当前决斗状态。 */
    public DuelState getState() {
        return state;
    }

    /** 结束原因（仅在 ENDING 后非空）。 */
    public EndReason getEndReason() {
        return endReason;
    }

    /** 决斗玩家 A（可能已离线返回 null）。 */
    public Player getPlayerA() {
        return Bukkit.getPlayer(playerA);
    }

    /** 决斗玩家 B（可能已离线返回 null）。 */
    public Player getPlayerB() {
        return Bukkit.getPlayer(playerB);
    }

    /** 该 UUID 是否为本次决斗的参与者。 */
    public boolean contains(UUID uuid) {
        return playerA.equals(uuid) || playerB.equals(uuid);
    }

    /**
     * CAS 状态转移：仅当当前状态为 {@code expected} 时置为 {@code next}。
     *
     * @return 是否成功转移
     */
    boolean transition(DuelState expected, DuelState next) {
        synchronized (stateLock) {
            if (state == expected) {
                state = next;
                return true;
            }
            return false;
        }
    }

    /**
     * 收尾：置 ENDING 并记录结束原因（仅当尚未结束时成功）。
     *
     * @return 是否首次进入 ENDING
     */
    boolean markEnding(EndReason reason) {
        synchronized (stateLock) {
            if (state == DuelState.ENDING) {
                return false;
            }
            state = DuelState.ENDING;
            this.endReason = reason;
            return true;
        }
    }
}
