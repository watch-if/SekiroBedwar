package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.api.TechniqueCancelReason;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 秘传宿主（独立模块）：注册 / 转发 / 生命周期清理的统一入口。
 *
 * <p>近战命中钩子由 BlockManager（普通格挡 / 无格挡）与 ParryManager（完美弹反成功）
 * 在同一注入点转发（ACTIVE 决斗内、近战 only，与巴之雷 / 属性两系结构一致）；
 * 后续新秘传实现 {@link Mystery} 并加入 {@link #arts} 即接入，无需再动两个战斗模块。</p>
 *
 * <p>死亡 / 退出 / 离局 / 决斗结束清各秘传的连击状态（跨局 / 跨场不残留）。</p>
 */
public final class MysteryManager implements Listener {

    private final SekiroBedwar plugin;
    private final List<Mystery> arts = new ArrayList<>();
    /** 第三击起防击退护身（飞渡浮舟 / 一心七连共用）。 */
    private final KnockbackGuard guard;

    public MysteryManager(SekiroBedwar plugin, MysteryConfig config,
                          StanceManager stanceManager, PaperDollManager paperDollManager,
                          org.alpha.sekiroBedwar.duel.DuelManager duelManager,
                          org.alpha.sekiroBedwar.duel.DuelConfig duelConfig) {
        this.plugin = plugin;
        this.guard = new KnockbackGuard(config);
        // 已实现的秘传（新增秘传：构造 + 在此 add 一行）
        if (config.fdfzEnabled()) {
            arts.add(new FeiduFuzhou(config, stanceManager, paperDollManager, guard));
        }
        if (config.yameEnabled()) {
            arts.add(new YamedoCrossSlash(config, stanceManager));
        }
        if (config.lsEnabled()) {
            arts.add(new LongShan(plugin, config, stanceManager, paperDollManager, duelManager, duelConfig));
        }
        if (config.isshinEnabled()) {
            arts.add(new IsshinSevenStrike(config, stanceManager, paperDollManager, guard));
        }
    }

    public void enable() {
        if (arts.isEmpty()) {
            plugin.getLogger().info("秘传已启用：（无启用的秘传武技）");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(guard, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid(), TechniqueCancelReason.LEFT));
        for (Mystery art : arts) {
            plugin.getLogger().info("秘传已启用：" + art.id());
        }
    }

    public void disable() {
        for (Mystery art : arts) {
            art.clearAll();
            art.shutdown();
        }
        guard.clearAll();
    }

    /** BlockManager / ParryManager 的近战命中转发（parried = 该击被完美弹反）。 */
    public void onAttack(Player attacker, Player victim, boolean parried) {
        for (Mystery art : arts) {
            art.onAttack(attacker, victim, parried);
        }
    }

    /** 左键挥臂转发（龙闪释放；不取消原版攻击动作）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClick(org.bukkit.event.player.PlayerAnimationEvent event) {
        for (Mystery art : arts) {
            art.onLeftClick(event.getPlayer());
        }
    }

    /** 快捷栏切换转发（空手换刀起手的武技即时武装；取消的切换不转发）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotSwitch(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack current = player.getInventory().getItem(event.getNewSlot());
        for (Mystery art : arts) {
            art.onSlotSwitch(player, previous, current);
        }
    }

    private void clearPlayer(UUID uuid, TechniqueCancelReason reason) {
        guard.clear(uuid);
        for (Mystery art : arts) {
            art.clear(uuid, reason);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        clearPlayer(event.getEntity().getUniqueId(), TechniqueCancelReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId(), TechniqueCancelReason.LEFT);
    }

    /** 决斗结束：双方连击状态清零（跨场不残留；下一场从第一式重新起连）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        clearPlayer(event.getDuel().getPlayerAUuid(), TechniqueCancelReason.DUEL_ENDED);
        clearPlayer(event.getDuel().getPlayerBUuid(), TechniqueCancelReason.DUEL_ENDED);
    }
}
