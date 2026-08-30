package org.alpha.sekiroBedwar.duel;

/**
 * 玩家决斗状态（{@link DuelManager} 状态机）。
 *
 * <ul>
 *   <li>{@link #NONE}：不在任何决斗中（{@code getState} 查询的兜底返回值）；</li>
 *   <li>{@link #PENDING}：决斗已创建（互斥已建立），等待进入 ACTIVE；缓冲窗口内第三方进入排除范围则直接结束；</li>
 *   <li>{@link #ACTIVE}：决斗进行中，同样受第三方检测约束；</li>
 *   <li>{@link #ENDING}：收尾中，广播 {@code DuelEndedEvent} 时状态可观察为 ENDING。</li>
 * </ul>
 */
public enum DuelState {
    /** 不在任何决斗中。 */
    NONE,
    /** 决斗已创建，等待进入 ACTIVE。 */
    PENDING,
    /** 决斗进行中。 */
    ACTIVE,
    /** 收尾中（即将被清理）。 */
    ENDING
}
