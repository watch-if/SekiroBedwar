package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 决斗期间架势条展示（BossBar，双方互显对方）。
 *
 * <p>玩家 A 的屏幕显示标题为“B 的名字 + 剩余百分比”、进度为 B 架势百分比的 BossBar；
 * 玩家 B 的屏幕对称显示 A 的架势。进度与标题（含剩余百分比数字）都按
 * {@code stance.bossbar.refresh-ticks} 间隔由共享定时任务实时刷新。</p>
 *
 * <p><b>防泄漏</b>：决斗结束 / 玩家下线调用 {@link #hide} / {@link #removePlayer}
 * 移除对应 BossBar（{@link BossBar#removeAll()}）并清理映射；共享刷新任务在 {@link #disable()} 取消。</p>
 */
public final class StanceBossBarDisplay {
    private final SekiroBedwar plugin;
    private final StanceConfig config;
    private final StanceManager stanceManager;

    /** 观看者 UUID → 其屏幕上显示的 BossBar。 */
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** 观看者 UUID → 对手 UUID（用于读取对方架势百分比）。 */
    private final Map<UUID, UUID> opponents = new HashMap<>();

    private BukkitTask refreshTask;

    public StanceBossBarDisplay(SekiroBedwar plugin, StanceConfig config, StanceManager stanceManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
    }

    /** 两名决斗玩家互显对方架势条，并确保共享刷新任务运行。 */
    public void show(Player a, Player b) {
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
            return;
        }
        showFor(a, b);
        showFor(b, a);
        startRefreshIfNeeded();
    }

    /** 隐藏双方架势条（决斗结束）。 */
    public void hide(Player a, Player b) {
        if (a != null) {
            removeBar(a.getUniqueId());
        }
        if (b != null) {
            removeBar(b.getUniqueId());
        }
    }

    /** 玩家下线：移除其屏幕上的架势条。 */
    public void removePlayer(UUID uuid) {
        removeBar(uuid);
    }

    /** 插件禁用：取消刷新任务并移除所有 BossBar。 */
    public void disable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        for (BossBar bar : bars.values()) {
            bar.removeAll();
        }
        bars.clear();
        opponents.clear();
    }

    private void showFor(Player viewer, Player opponent) {
        double pct = stanceManager.getPercentage(opponent.getUniqueId());
        BossBar bar = plugin.getServer().createBossBar(
                formatTitle(opponent.getName(), pct), config.bossbarColor(), config.bossbarStyle());
        bar.setProgress(pct);
        bar.addPlayer(viewer);
        bars.put(viewer.getUniqueId(), bar);
        opponents.put(viewer.getUniqueId(), opponent.getUniqueId());
    }

    /**
     * 按配置标题格式渲染：%s 替换为对方玩家名，& 色码转义为 §，末尾追加灰色剩余百分比
     * （current/max × 100，向下取整）。进度条本身为剩余架势（递减），标题数字同步实时刷新。
     */
    private String formatTitle(String opponentName, double pct) {
        String title = config.bossbarNameFormat().replace("%s", opponentName).replace('&', '§');
        return title + String.format(" §7%.0f%%", pct * 100.0);
    }

    private void removeBar(UUID uuid) {
        BossBar bar = bars.remove(uuid);
        opponents.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void startRefreshIfNeeded() {
        if (refreshTask != null) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L,
                Math.max(1, config.bossbarRefreshTicks()));
    }

    /** 刷新所有进行中的架势条：进度 + 标题（实时剩余百分比），并按架势阶段显示不同提示。 */
    private void refresh() {
        for (Map.Entry<UUID, UUID> entry : opponents.entrySet()) {
            BossBar bar = bars.get(entry.getKey());
            if (bar == null) {
                continue;
            }
            UUID opponentId = entry.getValue();
            Player opponent = plugin.getServer().getPlayer(opponentId);
            String name = opponent == null ? "?" : opponent.getName();
            switch (stanceManager.getStatus(opponentId)) {
                case BROKEN -> {
                    // 崩条：与“临界已空”区分开——明显变色变标题，崩条一击即现、不可错过。
                    bar.setProgress(0.0);
                    bar.setColor(BarColor.WHITE);
                    bar.setTitle("§c§l" + name + " 的架势已崩！处决窗口开启");
                }
                case CRITICAL -> {
                    // 临界（架势空，未崩）：黄字提示“命中即崩”，但尚未崩条。
                    bar.setProgress(0.0);
                    bar.setColor(config.bossbarColor());
                    bar.setTitle("§e§l" + name + " 的架势已空，命中即崩！");
                }
                default -> {
                    double pct = stanceManager.getPercentage(opponentId);
                    bar.setProgress(pct);
                    bar.setColor(config.bossbarColor());
                    bar.setTitle(formatTitle(name, pct));
                }
            }
        }
    }
}
