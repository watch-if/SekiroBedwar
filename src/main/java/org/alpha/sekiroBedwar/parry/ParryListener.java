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
 *   <li>{@link EntityDamageByEntityEvent}（<b>LOW</b>）：只判定完美弹反分支——
 *       命中窗口则取消（弹开的命中不再进入普通格挡模块），未命中窗口则返回（交普通格挡
 *       模块按 NORMAL 处理，绝不误判为完美弹反）；</li>
 *   <li>{@link DuelEndedEvent}（MONITOR）：清除双方格挡窗口与延迟状态；</li>
 *   <li>{@link PlayerQuitEvent}（MONITOR）：清除该玩家窗口与延迟状态。</li>
 * </ul>
 *
 * <p><b>为什么是 LOW（2026-09-27 修）</b>：Bukkit 的执行顺序是
 * {@code LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR}，<b>NORMAL 在 HIGH 之前</b>。
 * 旧注释与旧实现误以为 HIGH 先于 NORMAL，于是 {@code BlockListener}（NORMAL）总是先跑并
 * 按「普通格挡」扣掉弹反者 {@code Dbase × defender-multiplier} 的架势，等到本监听在 HIGH
 * 里 {@code setCancelled(true)} 时已经<b>撤不回那笔架势</b>——表现为「完美弹反了，自己还掉架势」。
 * 改到 LOW 后顺序变为 {@code LOWEST(风弹禁攻/击退守卫) → LOW(弹反 + 封印判定) → NORMAL(格挡换算)}，
 * 弹开 / 被封印的命中在 {@code ignoreCancelled=true} 的格挡模块里自然不可见，与设计文档一致。</p>
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

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
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
