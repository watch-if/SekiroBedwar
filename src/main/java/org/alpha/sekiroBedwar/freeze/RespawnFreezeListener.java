package org.alpha.sekiroBedwar.freeze;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 复活冻结监听器：
 * <ul>
 *   <li>{@link DuelEndedEvent}（NORMAL）→ 决斗结束后恢复待复活玩家；</li>
 *   <li>{@link PlayerQuitEvent}（MONITOR）→ 玩家退出服务器时清理冻结条目（不复活）。</li>
 * </ul>
 * 由 {@link RespawnFreezeManager#enable()} 注册。
 */
public final class RespawnFreezeListener implements Listener {
    private final RespawnFreezeManager freeze;

    public RespawnFreezeListener(RespawnFreezeManager freeze) {
        this.freeze = freeze;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDuelEnded(DuelEndedEvent event) {
        freeze.onDuelEnded(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        freeze.unfreeze(event.getPlayer().getUniqueId());
    }
}
