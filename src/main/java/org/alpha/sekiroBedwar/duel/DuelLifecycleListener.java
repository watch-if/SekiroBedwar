package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.event.DuelTriggeredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 决斗生命周期监听器：
 * <ul>
 *   <li>{@link DuelTriggeredEvent}（触发成功）→ 创建决斗（PENDING）；</li>
 *   <li>{@link PlayerQuitEvent}（玩家下线）→ 结束其所在决斗。</li>
 * </ul>
 * BedWars 生命周期事件（PlayerLeaveEvent / GameEndEvent）在
 * {@link DuelManager#enable()} 中通过 API 静态 <code>handle</code> 订阅。
 */
public final class DuelLifecycleListener implements Listener {
    private final DuelManager manager;

    public DuelLifecycleListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelTriggered(DuelTriggeredEvent event) {
        manager.createDuel(event.getPlayerA(), event.getPlayerB(), event.getGame(), event.getIsland());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.purgePlayer(event.getPlayer().getUniqueId());
    }
}
