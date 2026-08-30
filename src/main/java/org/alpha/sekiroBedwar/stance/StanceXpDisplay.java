package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 决斗期间用经验条（快捷栏上方绿条）显示<b>自己</b>的架势（独立模块）。
 *
 * <p>与 {@link StanceBossBarDisplay}（互显对手架势）互补：每位决斗玩家自己的经验条
 * 填充 = 自己架势剩余比例（0.0~1.0），等级数字隐藏（{@code setLevel(0)}），由
 * {@code stance.xp-bar.refresh-ticks} 间隔的共享定时任务实时刷新。</p>
 *
 * <p><b>经验值保护</b>：进入决斗时保存玩家原本的等级 / 经验条进度，决斗结束
 * （{@link #hide}）或插件禁用（{@link #disable}）时恢复原值——不污染玩家原本经验条。
 * 玩家下线仅移除占用（离线状态无从恢复，忽略）。</p>
 *
 * <p><b>重生兼容</b>：重生重建玩家实体会把经验条清零，本模块的刷新任务每间隔无条件
 * 重设自身架势，无需额外挂重生事件；恢复的仍是进入决斗前的原始值。</p>
 *
 * <p><b>线程安全</b>：{@code active} 仅在 Bukkit 主线程读写（事件回调 / 调度任务）。
 * 所有写操作都是 {@code Player.setLevel/setExp}，天然主线程安全。</p>
 */
public final class StanceXpDisplay {
    private final SekiroBedwar plugin;
    private final StanceConfig config;
    private final StanceManager stanceManager;

    /** 活跃决斗玩家 UUID → 进入决斗前保存的原本经验条状态。 */
    private final Map<UUID, SavedXp> active = new HashMap<>();

    private BukkitTask refreshTask;

    /** 进入决斗时保存的玩家原本经验条状态（等级 + 经验条进度）。 */
    private record SavedXp(int level, float exp) {
    }

    public StanceXpDisplay(SekiroBedwar plugin, StanceConfig config, StanceManager stanceManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
    }

    /** 两名决斗玩家开始用经验条显示自己的架势，并确保刷新任务运行。 */
    public void show(Player a, Player b) {
        if (!config.xpBarEnabled()) {
            return;
        }
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
            return;
        }
        save(a);
        save(b);
        startRefreshIfNeeded();
    }

    /** 隐藏双方经验条（决斗结束）并恢复其原本经验值。 */
    public void hide(Player a, Player b) {
        if (a != null) {
            restore(a.getUniqueId());
        }
        if (b != null) {
            restore(b.getUniqueId());
        }
    }

    /** 玩家下线：移除其经验条占用与保存值（离线状态无从恢复，忽略）。 */
    public void removePlayer(UUID uuid) {
        active.remove(uuid);
    }

    /** 插件禁用：取消刷新任务并恢复全部在线玩家的原本经验值。 */
    public void disable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            restore(uuid);
        }
    }

    /** 记录玩家原本经验条状态并把等级数字隐藏。 */
    private void save(Player player) {
        UUID id = player.getUniqueId();
        active.put(id, new SavedXp(player.getLevel(), player.getExp()));
        player.setLevel(0);
    }

    /** 恢复玩家原本经验条状态（仅当仍在保存值且玩家在线）。 */
    private void restore(UUID uuid) {
        SavedXp saved = active.remove(uuid);
        if (saved == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setLevel(saved.level());
        player.setExp(saved.exp());
    }

    private void startRefreshIfNeeded() {
        if (refreshTask != null) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L,
                Math.max(1, config.xpBarRefreshTicks()));
    }

    /** 刷新所有活跃玩家的经验条：填充 = 自己架势剩余比例，隐藏等级数字。 */
    private void refresh() {
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.setLevel(0);
            player.setExp((float) clamp01(stanceManager.getPercentage(uuid)));
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
