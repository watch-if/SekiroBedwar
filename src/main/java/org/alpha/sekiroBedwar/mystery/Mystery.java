package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.api.TechniqueCancelReason;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    /**
     * 快捷栏切换钩子（宿主监听 {@code PlayerItemHeldEvent} 统一转发，切换已实际发生）：
     * {@code previous} / {@code current} = 切换前 / 后主手物品（可空）。
     * 需要「空手换刀」起手的武技在此即时武装（起手动作须在衔接窗内衔接），默认不关心。
     */
    default void onSlotSwitch(Player player, ItemStack previous, ItemStack current) {
    }

    /**
     * 左键挥臂钩子（宿主监听 {@code PlayerAnimationEvent} 统一转发，不取消原版攻击）：
     * 以左键为「释放」动作的武技（如龙闪）在此消费武装态，默认不关心。
     */
    default void onLeftClick(Player player) {
    }

    /**
     * 玩家死亡 / 退出 / 离局 / 决斗结束时清理其连击状态。
     * 实现中若存在进行中的连段 / 武装，先经公共 API 广播 Cancel 事件（携带 {@code reason}）再清除。
     */
    default void clear(UUID player, TechniqueCancelReason reason) {
    }

    /** 宿主禁用时全量清理状态。 */
    default void clearAll() {
    }

    /**
     * 当前连段进度（已完成击数；未处于连段 = 0）。用于「领先者发声」：同一玩家并行多式
     * 时，只有进度领先者播段成功 / 脱拍音，落后的式静默重开——宏练一式时不混入别式
     * 的脱拍音（完成音 / 释放音不受此限制）。
     */
    default int comboProgress(UUID player) {
        return 0;
    }

    /** 宿主禁用时注销自建周期任务（无自建任务的武技不用覆写）。 */
    default void shutdown() {
    }
}
