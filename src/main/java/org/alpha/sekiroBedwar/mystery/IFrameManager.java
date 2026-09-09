package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/**
 * 无敌帧开关（独立模块）：原版受击保护帧（maximumNoDamageTicks，默认 20 tick）的
 * 全局 / 决斗双开关。
 *
 * <p>语义：玩家期望帧状态 {@code desired = 决斗中 ? iframe.duel-enabled : iframe.global-enabled}
 * （默认：全局<b>有</b>、决斗<b>没有</b>）。关闭 = {@code setMaximumNoDamageTicks(0)} +
 * 清当前帧（受击立即能再受击，拼刀连击不再被保护帧吞伤害——与飞渡浮舟的
 * tick 级连击节奏配套）；开启 = 恢复原版 20 tick。</p>
 *
 * <p>实现为周期巡检（{@code iframe.scan-ticks}，默认 5 tick）+ 幂等应用
 * （状态未变不写）：进 / 出场、重生重建实体（帧参数回默认）等边界一个机制全覆盖，
 * 零事件时序依赖（忍具商店入口同款「巡检 + 幂等」教训）。插件禁用时全员恢复原版。</p>
 */
public final class IFrameManager implements Listener {

    /** 原版默认受击保护帧上限。 */
    private static final int VANILLA_MAX_NO_DAMAGE_TICKS = 20;

    private final SekiroBedwar plugin;
    private final MysteryConfig config;
    private final DuelManager duelManager;
    private BukkitTask scanTask;

    public IFrameManager(SekiroBedwar plugin, MysteryConfig config, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.duelManager = duelManager;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::scan, 1L, config.iframeScanTicks());
        plugin.getLogger().info("无敌帧开关已启用：全局=" + (config.iframeGlobalEnabled() ? "有" : "无")
                + " 决斗中=" + (config.iframeDuelEnabled() ? "有" : "无")
                + "（巡检 " + config.iframeScanTicks() + " tick）");
    }

    public void disable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setMaximumNoDamageTicks(VANILLA_MAX_NO_DAMAGE_TICKS);
        }
    }

    /**
     * 巡检：逐人算期望帧状态并<b>每轮直接应用</b>——maximumNoDamageTicks 是内存字段、
     * 写入无包 / 无副作用；重生重建实体后参数回默认也因此能被自动纠回
     * （幂等表反而会漏掉这种「状态未变、实体已换」的场景）。
     */
    private void scan() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 玩法只在 BedWars 对局内生效：对局外一律还原原版 20 tick；
            // global-enabled 语义 = 对局内（决斗之外）是否有无敌帧
            boolean inGame = org.alpha.sekiroBedwar.combat.BwScope.inGame(player.getUniqueId());
            boolean desired = !inGame ? true
                    : duelManager.isInDuel(player)
                            ? config.iframeDuelEnabled()
                            : config.iframeGlobalEnabled();
            apply(player, desired);
        }
    }

    private static void apply(Player player, boolean withIFrames) {
        player.setMaximumNoDamageTicks(withIFrames ? VANILLA_MAX_NO_DAMAGE_TICKS : 0);
        if (!withIFrames) {
            player.setNoDamageTicks(0); // 清进行中的帧
        }
    }
}
