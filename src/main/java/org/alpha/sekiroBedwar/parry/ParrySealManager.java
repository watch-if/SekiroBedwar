package org.alpha.sekiroBedwar.parry;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 连续弹反计数器窗口（独立模块）：同一决斗中，玩家攻击连续被对方完美弹反
 * {@code required-parries}（{@code parry.seal.required-parries}）次后，接下来
 * {@code duration-seconds}（{@code parry.seal.duration-seconds}）秒内该玩家的所有攻击
 * （近战与弓箭）对对手<b>无伤害、无作用</b>（攻势无效，攻击一律被取消，不进入普通格挡 / 弹反换算）。
 *
 * <p><b>“连续”的严格判定</b>：
 * <ul>
 *   <li><b>时间窗</b>：相邻两次被完美弹反的间隔 ≤ {@code max-interval-seconds}
 *       （{@code parry.seal.max-interval-seconds}，默认 0.7s）才累计；间隔更长或首次弹反 → 重新计 1；</li>
 *   <li><b>中间不能有普通弹反</b>：一次普通格挡（对方格挡但未命中完美弹反窗口）或成功命中
 *       （近战或弓箭）都会打断连续计数（{@link #onHitLanded} 清零）。</li>
 * </ul>
 * 计数由 {@link ParryManager} 在同一个 HIGH 回调里驱动，避免新增同优先级监听依赖注册顺序：
 * <ul>
 *   <li><b>完美弹反成功</b> → {@link #onAttackParried}：被弹反方（攻击者）连续次数按时间窗累计 +1；
 *       达到阈值则施加封印并重置计数（此后重新累计）；封印期间不累计；</li>
 *   <li><b>未被完美弹反的命中</b>（近战普通格挡 / 无格挡，或弓箭）→ {@link #onHitLanded}：打断连续计数；</li>
 *   <li><b>封印期间</b> → {@link #isSealed} 为 true，{@link ParryManager} 直接取消该攻击。</li>
 * </ul>
 * 封印状态在 {@code isSealed} 惰性过期；决斗结束 / 玩家下线清空该玩家状态。</p>
 */
public final class ParrySealManager implements Listener {
    private final SekiroBedwar plugin;
    private final ParryConfig config;

    /** 玩家连续被完美弹反的次数（连续 = 时间窗内且期间未被打断）。 */
    private final Map<UUID, Integer> parriedStreak = new HashMap<>();
    /** 玩家上次被完美弹反的时刻（服务器毫秒；用于连续时间窗判定）。 */
    private final Map<UUID, Long> lastParryTime = new HashMap<>();
    /** 玩家“攻势无效”封印的到期时刻（服务器毫秒；不存在 = 未封印）。 */
    private final Map<UUID, Long> sealUntil = new HashMap<>();

    public ParrySealManager(SekiroBedwar plugin, ParryConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** 注册清理监听（决斗结束 / 下线清空状态）。 */
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** 插件禁用：清空全部状态。 */
    public void disable() {
        parriedStreak.clear();
        lastParryTime.clear();
        sealUntil.clear();
    }

    /**
     * 一次完美弹反成功（由 {@link ParryManager} 在近战命中被弹开时调用）。
     * 连续判定：距上次被完美弹反 ≤ {@code max-interval-seconds} 才 +1，否则（首次或超窗）重新计 1。
     * 达到阈值 → 施加封印（攻势无效窗口）并重置计数；封印期间不累计。不发送任何文字提示（保持战斗沉浸）。
     */
    public void onAttackParried(Player attacker) {
        if (!config.sealEnabled() || isSealed(attacker.getUniqueId())) {
            return;
        }
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        long maxGapMs = (long) (config.sealMaxIntervalSeconds() * 1000.0);
        Long last = lastParryTime.get(uuid);
        // 时间窗判定：上次弹反距本次超过 maxGapMs（或尚无上次）→ 连续断开，重新计 1
        int count = (last != null && now - last <= maxGapMs)
                ? parriedStreak.getOrDefault(uuid, 0) + 1
                : 1;
        lastParryTime.put(uuid, now);
        if (count >= config.sealRequiredParries()) {
            long durationMs = (long) (config.sealDurationSeconds() * 1000.0);
            sealUntil.put(uuid, now + durationMs);
            parriedStreak.remove(uuid);
            lastParryTime.remove(uuid);
        } else {
            parriedStreak.put(uuid, count);
        }
    }

    /**
     * 一次未被完美弹反的命中（近战普通格挡 / 无格挡，或弓箭，由 {@link ParryManager} 在非弹反命中时调用）。
     * 普通弹反 / 成功命中 = 连续被打断 → 清空连续被弹反计数。
     */
    public void onHitLanded(Player attacker) {
        parriedStreak.remove(attacker.getUniqueId());
        lastParryTime.remove(attacker.getUniqueId());
    }

    /**
     * 该玩家当前是否处于“攻势无效”封印状态（期间所有攻击被取消）。
     * 惰性过期：到期条目的状态在被查询时清除。
     */
    public boolean isSealed(UUID uuid) {
        Long until = sealUntil.get(uuid);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            sealUntil.remove(uuid);
            parriedStreak.remove(uuid);
            lastParryTime.remove(uuid);
            return false;
        }
        return true;
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
        parriedStreak.remove(uuid);
        lastParryTime.remove(uuid);
        sealUntil.remove(uuid);
    }
}
