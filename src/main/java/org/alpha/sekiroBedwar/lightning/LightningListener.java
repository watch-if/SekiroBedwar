package org.alpha.sekiroBedwar.lightning;

import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

/**
 * 巴之雷监听器（薄壳）：三叉戟远程命中 → 交给 {@link LightningManager#handleTridentHit}。
 *
 * <p>投射物不参与完美弹反（弹反仅近战），故三叉戟命中天然「未被弹反」；命中玩家即记录衔接窗口，
 * 后续由 {@code LightningManager#onAttack} 判断有效架势命中 / 跳击。</p>
 */
public final class LightningListener implements Listener {
    private final LightningManager manager;

    public LightningListener(LightningManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }
        if (!(trident.getShooter() instanceof Player shooter)) {
            return;
        }
        if (!(event.getHitEntity() instanceof Player victim)) {
            return;
        }
        manager.handleTridentHit(shooter, victim);
    }
}
