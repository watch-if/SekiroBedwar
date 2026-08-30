package org.alpha.sekiroBedwar.stance;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/**
 * 架势崩溃系统监听器（薄壳）：只监听 {@link EntityRegainHealthEvent}，
 * 在架势非满时阻断 SATIATED 自然回血（HIGH 优先级，忽略已取消事件）。
 *
 * <p>崩条触发不在此监听——由 {@link org.alpha.sekiroBedwar.block.BlockManager}（未弹反命中）
 * 与 {@link org.alpha.sekiroBedwar.parry.ParryManager}（被弹反）直接调用 {@link StanceBreakManager}。</p>
 */
public final class StanceBreakListener implements Listener {
    private final StanceBreakManager manager;

    public StanceBreakListener(StanceBreakManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        manager.handleRegainHealth(event);
    }
}
