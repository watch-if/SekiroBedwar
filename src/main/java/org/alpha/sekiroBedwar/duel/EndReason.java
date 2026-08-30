package org.alpha.sekiroBedwar.duel;

/** 决斗结束原因（随 {@code DuelEndedEvent} 广播）。 */
public enum EndReason {
    /** 第三方玩家进入岛屿外围排除范围。 */
    THIRD_PARTY_ENTERED,
    /** 决斗玩家下线。 */
    PLAYER_QUIT,
    /** 决斗玩家离开对局 / 死亡成观战 / 队伍失效。 */
    PLAYER_LEFT_GAME,
    /** 对局结束。 */
    GAME_ENDED,
    /** 主动调用 {@code DuelManager#endDuel}。 */
    MANUAL,
    /** 插件禁用。 */
    PLUGIN_DISABLE,
    /** 决斗中一方被对方击杀（崩条内被杀 / 普通被杀），按结算比例转移资源。 */
    EXECUTED,
    /** 处决窗口到期未击杀对方，按崩条比例结算对方物资，决斗立即结束。 */
    EXECUTION_TIMEOUT,
    /** 决斗中一方坠落虚空死亡（被击落虚空 = 决斗死亡），按虚空比例结算并记录胜者。 */
    VOID_DEATH
}
