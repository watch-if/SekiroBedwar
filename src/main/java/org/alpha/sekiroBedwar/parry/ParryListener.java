package org.alpha.sekiroBedwar.parry;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 弹反系统的 Bukkit 事件监听器（薄壳，只负责把事件转发给管理器，不承载逻辑）。
 *
 * <ul>
 *   <li>{@link EntityDamageByEntityEvent}（<b>HIGH</b>）：只判定完美弹反分支——
 *       命中窗口则取消（弹开的命中不再进入普通格挡模块），未命中窗口则返回（交普通格挡
 *       模块按 NORMAL 处理，绝不误判为完美弹反）；</li>
 *   <li>{@link DuelEndedEvent}（MONITOR）：清除双方格挡窗口与延迟状态；</li>
 *   <li>{@link PlayerQuitEvent}（MONITOR）：清除该玩家窗口与延迟状态。</li>
 * </ul>
 */
public final class ParryListener implements Listener {
    private final ParryManager manager;
    private final ParryWindowManager window;
    private final LatencyCompensationManager latency;

    public ParryListener(ParryManager manager, ParryWindowManager window, LatencyCompensationManager latency) {
        this.manager = manager;
        this.window = window;
        this.latency = latency;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        manager.handleDamage(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        purge(event.getDuel().getPlayerAUuid());
        purge(event.getDuel().getPlayerBUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        purge(event.getPlayer().getUniqueId());
    }

    private void purge(UUID uuid) {
        if (uuid == null) {
            return;
        }
        window.purge(uuid);
        latency.purge(uuid);
    }
}
