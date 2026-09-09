package org.alpha.sekiroBedwar.api;

/**
 * 决斗阶段（公共 API，稳定枚举）。
 *
 * <p>与内部状态机对应但不暴露其实现：{@code PENDING}（触发缓冲）→ {@code ACTIVE}
 * （进行中，架势 / 弹反 / 秘传等规则生效）→ {@code ENDING}（收尾，仅瞬时可见）。</p>
 */
public enum DuelPhase {
    /** 已触发、缓冲期内（第三方进入可直接取消）。 */
    PENDING,
    /** 进行中。 */
    ACTIVE,
    /** 已结束（收尾广播瞬间的快照值；查询 API 中不会出现）。 */
    ENDING
}
