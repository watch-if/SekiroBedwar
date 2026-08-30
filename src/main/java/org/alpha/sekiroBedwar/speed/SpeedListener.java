package org.alpha.sekiroBedwar.speed;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 剑攻速强化监听器（薄壳）：重进清理残留修正 / 退出清理等级与修正。
 */
public final class SpeedListener implements Listener {
    private final SpeedManager manager;

    public SpeedListener(SpeedManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }
}
