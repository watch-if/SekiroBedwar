package org.alpha.sekiroBedwar.api;

import java.util.Optional;
import java.util.UUID;

/**
 * 一场决斗的只读快照（公共 API）。
 *
 * <p>事件携带的实例是<b>事件发生时刻</b>的快照；{@link SekiroBedwarApi#duelOf} 返回的实例
 * 每次调用即时生成。不暴露核心内部对象 / 计时器 / 状态机。</p>
 */
public interface DuelInfo {

    /** 决斗唯一标识。 */
    UUID id();

    /** 玩家 A。 */
    UUID playerA();

    /** 玩家 B。 */
    UUID playerB();

    /** 决斗者集合（A、B）。 */
    default java.util.Set<UUID> players() {
        return java.util.Set.of(playerA(), playerB());
    }

    /** 该玩家的对手机会；非本决斗成员返回空。 */
    default Optional<UUID> opponentOf(UUID player) {
        if (playerA().equals(player)) {
            return Optional.of(playerB());
        }
        if (playerB().equals(player)) {
            return Optional.of(playerA());
        }
        return Optional.empty();
    }

    /** 决斗阶段。 */
    DuelPhase phase();

    /** 所属 BedWars 对局 id。 */
    UUID gameId();

    /** 决斗创建时间（epoch 毫秒）。 */
    long startedAtEpochMillis();

    /** 快照生成时已进行的时长（毫秒）。 */
    long durationMillis();

    /** 已结束决斗的结束原因（{@link #phase()} 非 ENDING 时为 null）。 */
    DuelEndReason endReason();
}
