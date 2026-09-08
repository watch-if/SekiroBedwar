package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    public MysteryManager(SekiroBedwar plugin, MysteryConfig config,
                          StanceManager stanceManager, PaperDollManager paperDollManager) {
        this.plugin = plugin;
        // 已实现的秘传（新增秘传：构造 + 在此 add 一行）
        if (config.fdfzEnabled()) {
            arts.add(new FeiduFuzhou(config, stanceManager, paperDollManager));
        }
        if (config.yameEnabled()) {
            arts.add(new YamedoCrossSlash(plugin, config, stanceManager));
        }
    }

    public void enable() {
        if (arts.isEmpty()) {
            plugin.getLogger().info("秘传已启用：（无启用的秘传武技）");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid()));
        for (Mystery art : arts) {
            plugin.getLogger().info("秘传已启用：" + art.id());
        }
    }

    public void disable() {
        for (Mystery art : arts) {
            art.clearAll();
            art.shutdown();
        }
    }

    /** BlockManager / ParryManager 的近战命中转发（parried = 该击被完美弹反）。 */
    public void onAttack(Player attacker, Player victim, boolean parried) {
        for (Mystery art : arts) {
            art.onAttack(attacker, victim, parried);
        }
    }

    private void clearPlayer(UUID uuid) {
        for (Mystery art : arts) {
            art.clear(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        clearPlayer(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    /** 决斗结束：双方连击状态清零（跨场不残留；下一场从第一式重新起连）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        clearPlayer(event.getDuel().getPlayerAUuid());
        clearPlayer(event.getDuel().getPlayerBUuid());
    }
}
