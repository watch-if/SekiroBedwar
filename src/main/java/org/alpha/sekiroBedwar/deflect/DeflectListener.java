package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 盾牌弹反监听器（薄壳）：持盾右键即时触发纸人弹反窗口 + 决斗结束 / 退出清理。
 * 命中处理不在此监听——窗口内的近战命中由 {@code ParryManager}（HIGH）按完美弹反处理。
 */
public final class DeflectListener implements Listener {
    private final DeflectManager manager;

    public DeflectListener(DeflectManager manager) {
        this.manager = manager;
    }

    /**
     * 持盾右键触发（主手或副手，直接读玩家手持，不依赖 event 手位字段）。
     * 右键可交互方块（箱子 / 门 / 工作台等）不触发——防误开方块白扣纸人。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!DeflectManager.hasShieldInHand(player)) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && isInteractable(event.getClickedBlock())) {
            return;
        }
        manager.tryStartDeflect(player);
    }

    private static boolean isInteractable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return block.getState() instanceof Container
                || block.getBlockData() instanceof Openable
                || type == Material.CRAFTING_TABLE
                || type == Material.ENCHANTING_TABLE
                || type == Material.ANVIL
                || type == Material.LECTERN
                || type == Material.SMITHING_TABLE
                || type == Material.STONECUTTER
                || type == Material.GRINDSTONE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        manager.clear(event.getDuel().getPlayerAUuid());
        manager.clear(event.getDuel().getPlayerBUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.clear(event.getPlayer().getUniqueId());
    }
}
