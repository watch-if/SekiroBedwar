package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.alpha.sekiroBedwar.event.DuelTriggeredEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 架势系统生命周期监听器：
 * <ul>
 *   <li>{@link DuelTriggeredEvent}（MONITOR）→ 初始化双方架势状态 + 互显架势条 + 经验条显示自己的架势；</li>
 *   <li>{@link DuelEndedEvent}（MONITOR）→ 移除架势条 / 恢复经验条 + 清理双方状态；</li>
 *   <li>{@link PlayerQuitEvent}（MONITOR）→ 清理该玩家状态与架势条（防泄漏）。</li>
 * </ul>
 */
public final class StanceListener implements Listener {
    private final StanceManager stanceManager;
    private final StanceBossBarDisplay display;
    private final StanceXpDisplay xpDisplay;

    public StanceListener(StanceManager stanceManager, StanceBossBarDisplay display, StanceXpDisplay xpDisplay) {
        this.stanceManager = stanceManager;
        this.display = display;
        this.xpDisplay = xpDisplay;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelTriggered(DuelTriggeredEvent event) {
        Player a = event.getPlayerA();
        Player b = event.getPlayerB();
        stanceManager.beginDuel(a, b);
        display.show(a, b);
        xpDisplay.show(a, b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        Player a = event.getPlayerA();
        Player b = event.getPlayerB();
        display.hide(a, b);
        xpDisplay.hide(a, b);
        stanceManager.endDuel(a == null ? null : a.getUniqueId(), b == null ? null : b.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        stanceManager.purgePlayer(event.getPlayer().getUniqueId());
        display.removePlayer(event.getPlayer().getUniqueId());
        xpDisplay.removePlayer(event.getPlayer().getUniqueId());
    }
}
