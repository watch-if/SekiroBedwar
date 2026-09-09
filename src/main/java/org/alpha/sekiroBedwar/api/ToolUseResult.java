package org.alpha.sekiroBedwar.api;

/**
 * 忍具使用结果（公共 API；{@link org.alpha.sekiroBedwar.api.events.ShinobiToolUseEvent}）。
 */
public enum ToolUseResult {
    /** 使用成功（效果已生效 / 资源已扣）。 */
    SUCCESS,
    /** 被拒绝：消耗资源不足（如纸人不够）。 */
    INSUFFICIENT_RESOURCE,
    /** 被拒绝：条件不满足（上限 / 冷却 / 状态限制等）。 */
    REJECTED,
    /** 其他 / 未来新增结果。 */
    OTHER
}
