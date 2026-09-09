package org.alpha.sekiroBedwar.api;

/**
 * 秘传「已有进度被打断清除」的原因（公共 API；出现在
 * {@link org.alpha.sekiroBedwar.api.events.SecretTechniqueCancelEvent}，
 * 与 {@link TechniqueFailReason}（节奏性失败）区分：取消 = 外部生命周期事件强制中止）。
 */
public enum TechniqueCancelReason {
    /** 玩家死亡。 */
    DEATH,
    /** 所在决斗结束（任意原因）。 */
    DUEL_ENDED,
    /** 离开对局 / 退出服务器。 */
    LEFT,
    /** 插件禁用。 */
    PLUGIN_DISABLE,
    /** 其他 / 未来新增原因。 */
    OTHER
}
