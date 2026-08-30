package org.alpha.sekiroBedwar.block;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 普通格挡 / 受击架势监听器（薄壳，只把事件转发给 {@link BlockManager}）。
 *
 * <p>NORMAL 优先级 + {@code ignoreCancelled=true}：被完美弹反（ParryManager 在 HIGH 优先级
 * 已 {@code setCancelled(true)}）的命中不会进入本模块；其余命中（含“格挡但未命中完美弹反
 * 窗口”）按普通格挡 / 无格挡处理。</p>
 */
public final class BlockListener implements Listener {
    private final BlockManager manager;

    public BlockListener(BlockManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        manager.handleDamage(event);
    }
}
