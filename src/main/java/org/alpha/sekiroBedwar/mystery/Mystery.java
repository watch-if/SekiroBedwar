package org.alpha.sekiroBedwar.mystery;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 秘传武技扩展接口（预留后续秘传的统一接入位）。
 *
 * <p>新秘传 = 实现本接口 + 在 {@link MysteryConfig} 增配置段 + 在
 * {@link MysteryManager} 构造列表中登记一行——近战命中钩子（与巴之雷 / 属性两系同
 * 一注入点：ACTIVE 决斗内的近战命中，含被完美弹反）由宿主统一转发，实现类只写
 * 自己的识别与奖励逻辑。</p>
 */
public interface Mystery {

    /** 配置段 / 日志标识（如 {@code fei-du-fu-zhou}）。 */
    String id();

    /**
     * 近战命中钩子（仅 ACTIVE 决斗内、仅近战；被完美弹反的命中同样到达，
     * {@code parried} 标识）。宿主已在 enable 且本秘传启用时才转发。
     */
    void onAttack(Player attacker, Player victim, boolean parried);

    /** 玩家死亡 / 退出 / 离局 / 决斗结束时清理其连击状态。 */
    default void clear(UUID player) {
    }

    /** 插件禁用时全量清理。 */
    default void clearAll() {
    }
}
