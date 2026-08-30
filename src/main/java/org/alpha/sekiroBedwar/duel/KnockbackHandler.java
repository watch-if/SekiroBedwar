package org.alpha.sekiroBedwar.duel;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 击退检测与处理接口：区分「被攻击击退的合法越界」与「玩家主动越界」。
 *
 * <p>默认实现为 {@link DuelAreaGuard}：受害者在决斗中受到攻击时记录击退方向与有效窗口，
 * 越界判定时若位移方向与击退方向一致（窗口内）视为合法击退、不拉回；
 * 供 {@link DuelAreaListener} 区分攻击产生的击退与主动移动 / 传送 / 搭方块逃离。</p>
 *
 * <p>接口化便于未来以其他实现替换 / 扩展击退检测口径，而无需改动守卫逻辑。</p>
 */
public interface KnockbackHandler {
    /** 记录一次攻击击退（受害者在决斗中、攻击方非空且非自伤时有效）。 */
    void recordKnockback(Player victim, Entity damager);

    /** 当前越界位移是否来自「被攻击击退」（窗口内 + 位移方向与击退方向一致）。 */
    boolean isKnockbackDisplacement(Player player, DuelIsland island);

    /** 玩家下线 / 决斗结束：清理其击退记录（防泄漏）。 */
    void purge(UUID uuid);

    /** 插件禁用：清空全部击退记录。 */
    void clear();
}
