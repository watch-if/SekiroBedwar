package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 结算监听器：
 * <ul>
 *   <li>{@link PlayerDeathEvent}（HIGHEST，先于掉落 / 观战流程）→ 死亡结算
 *       （虚空死亡按虚空比例；崩条内被杀按崩条比例；普通击杀按普通比例）；</li>
 *   <li>{@link DuelEndedEvent}（NORMAL，先于 {@link StanceListener} 的 MONITOR 清理架势状态）→
 *       第三方介入结算（双方资源回滚到决斗开始快照）。</li>
 * </ul>
 * 由 {@link SettlementManager#enable()} 注册。
 */
public final class SettlementListener implements Listener {
    private final SettlementManager settlement;

    public SettlementListener(SettlementManager settlement) {
        this.settlement = settlement;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        settlement.onPlayerDeath(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        settlement.onDuelEnded(event);
    }
}
