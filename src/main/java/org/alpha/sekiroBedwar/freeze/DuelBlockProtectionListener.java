package org.alpha.sekiroBedwar.freeze;

import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Optional;

/**
 * 决斗双方方块保护：
 * <ul>
 *   <li>{@link BlockBreakEvent}（NORMAL）：决斗者在<b>决斗范围内</b>禁止破坏方块
 *       （{@code area-only=false} 时决斗期间全图禁止）；</li>
 *   <li>{@link BlockPlaceEvent}（NORMAL）：决斗期间两名决斗者<b>全图</b>禁止搭建方块
 *       （比 {@code DuelAreaGuard} 的「只拦搭出边界外」更宽，二者不冲突）。</li>
 * </ul>
 * 只作用于两名决斗者（{@link DuelManager#isInDuel}），其余玩家搭建 / 破坏不受任何影响。
 * 由 {@code SekiroBedwar.onEnable} 注册。
 */
public final class DuelBlockProtectionListener implements Listener {
    private final FreezeConfig config;
    private final DuelManager duelManager;

    public DuelBlockProtectionListener(FreezeConfig config, DuelManager duelManager) {
        this.config = config;
        this.duelManager = duelManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.blockBreakEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!duelManager.isInDuel(player)) {
            return;
        }
        if (config.blockBreakAreaOnly()) {
            Optional<Duel> duel = duelManager.getDuel(player);
            if (duel.isEmpty() || !duel.get().getIsland().contains(event.getBlock().getLocation())) {
                // 决斗范围外：允许破坏
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.blockPlaceEnabled()) {
            return;
        }
        if (duelManager.isInDuel(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
