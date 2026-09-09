package org.alpha.sekiroBedwar.api;

/**
 * 决斗结束原因（公共 API，稳定枚举；由核心内部原因映射而来）。
 */
public enum DuelEndReason {
    /** 崩条后于处决窗口内被击杀（或普通 / 虚空击杀归类见 {@link #VOID_DEATH}）。 */
    EXECUTED,
    /** 处决窗口到期未击杀 → 按崩条比例半额结算并结束。 */
    EXECUTION_TIMEOUT,
    /** 被击落虚空（视同击杀）。 */
    VOID_DEATH,
    /** 第三方进入决斗排除区 → 结束并按快照回滚（不转移）。 */
    THIRD_PARTY_INTERRUPTED,
    /** 决斗者退出服务器。 */
    PLAYER_QUIT,
    /** 决斗者离开对局。 */
    PLAYER_LEFT_GAME,
    /** 整局游戏结束。 */
    GAME_ENDED,
    /** 插件禁用。 */
    PLUGIN_DISABLE,
    /** 其他 / 未来新增原因。 */
    OTHER
}
