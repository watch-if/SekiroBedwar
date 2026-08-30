package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.scheduler.BukkitTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.UUID;

/**
 * 架势崩溃（崩条）管理器（独立模块，与完美弹反 / 普通格挡解耦）。
 *
 * <p><b>临界状态</b>（{@link StanceManager#isCritical}）：已消耗架势比例 ≥ {@code critical-ratio}
 * （默认 1.0 = 架势条空 / current≈0），且未处于崩条状态。到达临界<b>不会自动崩条</b>，
 * 只有满足下列任一触发条件才崩（全部开关配置化，{@code stance.break.trigger.*}）：</p>
 * <ul>
 *   <li><b>条件一（未弹反命中）</b>：临界玩家被对方的<b>近战</b>命中且未成功完美弹反
 *       （普通格挡或无格挡）→ 崩条。由 {@link org.alpha.sekiroBedwar.block.BlockManager} 在
 *       扣架势<b>之前</b>调用 {@link #onIncomingHit}（保证读到命中前的临界状态）。</li>
 *   <li><b>条件二（被弹反）</b>：临界玩家自己的<b>近战</b>攻击被对方完美弹反 → 崩条。
 *       由 {@link org.alpha.sekiroBedwar.parry.ParryManager} 完美弹反成功分支调用 {@link #onAttackParried}。</li>
 *   <li><b>远程命中不崩</b>：弓箭 / 投射物命中临界玩家不触发崩条，只让对方持续维持临界状态
 *       （BlockManager 已对双方 markActive 刷新 idle 计时，阻止自然下降）。</li>
 * </ul>
 *
 * <p><b>崩条后果</b>（{@link StanceManager#breakStance}）：当前架势清零 + 进入结算 / 逃离窗口
 * （{@code execution-seconds}）+ <b>受击状态</b>（{@code stagger.duration-seconds}，
 * 期间无法正常格挡但仍可移动、可攻击）。受击状态是否强制无法格挡由
 * {@code stagger.disable-blocking} 控制（默认 true）：开启时崩条瞬间 {@code setCooldown(SHIELD, …)}
 * 强制盾牌冷却，并由周期任务 {@link #enforceGuard} 按剩余时长持续刷新——受击状态内
 * {@code isBlocking()} 保持 false（完美弹反轮询也随之失效），窗口到期自动恢复。</p>
 *
 * <p><b>低血量拉临界</b>（{@code stance.break.low-health-threshold}，默认 2）：决斗中每次受击后，
 * 血量 ≤ 阈值且未死亡 → 架势强制拉到临界（幂等反复生效），不是崩条——低血量玩家持续处于临界，
 * 下一次未弹反的近战命中即崩条。</p>
 *
 * <p><b>自然回血阻断</b>（{@code stance.health-regen.block-natural}，默认 true）：架势非满
 * （current &lt; max）时取消 SATIATED（饥饿值自然回血）；金苹果 / 药水等主动治疗不受影响。
 * 不在决斗中时 getStance/getMaxStance 均为 0，天然不命中。</p>
 *
 * <p><b>线程安全</b>：所有入口均在 Bukkit 主线程（事件回调 / runTaskTimer），
 * 状态写操作委托给带 {@code ensureMainThread} 守卫的 {@link StanceManager}。</p>
 */
public final class StanceBreakManager {
    private final SekiroBedwar plugin;
    private final StanceConfig config;
    private final StanceManager stanceManager;
    private final StanceBreakListener listener;

    /** 无法格挡强制的周期任务。 */
    private BukkitTask guardTask;

    public StanceBreakManager(SekiroBedwar plugin, StanceConfig config, StanceManager stanceManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.listener = new StanceBreakListener(this);
    }

    /** 注册监听（自然回血阻断）+ 启动受击状态强制任务（disable-blocking 且时长 > 0 时）。 */
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        if (config.disableBlocking() && config.staggerDurationSeconds() > 0.0) {
            guardTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::enforceGuard, 1L,
                    Math.max(1, config.guardCheckTicks()));
        }
    }

    /** 插件禁用：取消强制冷却任务。 */
    public void disable() {
        if (guardTask != null) {
            guardTask.cancel();
            guardTask = null;
        }
    }

    /**
     * 处理一次决斗内<b>未弹反</b>命中（由 {@link org.alpha.sekiroBedwar.block.BlockManager} 在
     * 扣架势之前调用，保证读到命中前的临界状态）。
     *
     * <ol>
     *   <li><b>崩条条件一</b>：命中为<b>近战</b>且受击方处于临界 → 按格挡 / 无格挡开关崩条；
     *       远程命中不崩，只维持临界。</li>
     *   <li><b>低血量拉临界</b>：受击后血量 ≤ 阈值且未死亡 → 架势拉到临界（幂等，不崩条）。</li>
     * </ol>
     */
    public void onIncomingHit(Player victim, EntityDamageByEntityEvent event) {
        Player attacker = CombatUtils.resolveMeleeAttacker(event);
        boolean melee = attacker != null;
        if (melee && stanceManager.isCritical(victim.getUniqueId())) {
            if (victim.isBlocking() && config.breakOnBlockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
            } else if (!victim.isBlocking() && config.breakOnUnblockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
            }
        }
        double postHit = victim.getHealth() - event.getFinalDamage();
        if (postHit > 0.0 && postHit <= config.lowHealthThreshold()) {
            stanceManager.setStance(victim.getUniqueId(), 0.0);
        }
    }

    /**
     * 处理一次完美弹反成功（由 {@link org.alpha.sekiroBedwar.parry.ParryManager} 调用）：
     * 被弹反方（攻击者）若处于临界 → 崩条（弹反仅限近战，天然满足“近战才会崩”）。
     */
    public void onAttackParried(Player attacker) {
        if (config.breakOnParriedAttack() && stanceManager.isCritical(attacker.getUniqueId())) {
            breakStance(attacker);
            if (attacker != null && attacker.isOnline()) {
                attacker.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent("§c§l你的架势被完美弹反崩了！"));
            }
        }
    }

    /**
     * 自然回血阻断：架势非满时取消 SATIATED 自然回血（金苹果 / 药水等主动治疗不受影响）。
     * 不在决斗中时 getStance/getMaxStance 均为 0，天然不命中。
     */
    public void handleRegainHealth(EntityRegainHealthEvent event) {
        if (!config.blockNaturalRegen()) {
            return;
        }
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (stanceManager.getStance(uuid) < stanceManager.getMaxStance(uuid)) {
            event.setCancelled(true);
        }
    }

    /** 触发崩条；若受击状态开启无法格挡，立刻强制盾牌冷却（阻止立即格挡 / 弹反）。 */
    private void breakStance(Player player) {
        stanceManager.breakStance(player.getUniqueId());
        if (config.disableBlocking() && config.staggerDurationSeconds() > 0.0) {
            int ticks = Math.max(1, (int) Math.ceil(config.staggerDurationSeconds() * 20.0));
            player.setCooldown(Material.SHIELD, ticks);
        }
    }

    /** 崩条即时反馈：施加方（攻击方）与崩条者（受击方）各收一条 ActionBar 提示。 */
    private void notifyBreak(Player breaker, Player victim) {
        if (breaker != null && breaker.isOnline()) {
            breaker.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent("§a§l对方架势已崩！处决窗口开启"));
        }
        if (victim != null && victim.isOnline()) {
            victim.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent("§c§l你的架势崩了！"));
        }
    }

    /**
     * 受击状态强制：对处于受击状态（无法格挡窗口）的决斗玩家按剩余时长持续刷新盾牌冷却，
     * 使 {@code isBlocking()} 保持 false（无法格挡也无法完美弹反）；窗口到期
     * （{@code canBlock()} 为真）后停止，冷却随之结束。
     */
    private void enforceGuard() {
        long now = System.currentTimeMillis();
        for (UUID uuid : stanceManager.getActiveUuids()) {
            if (stanceManager.canBlock(uuid)) {
                continue;
            }
            long remaining = stanceManager.getGuardDisabledUntil(uuid) - now;
            if (remaining <= 0L) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int ticks = Math.max(1, (int) Math.ceil(remaining / 50.0));
            player.setCooldown(Material.SHIELD, ticks);
        }
    }
}
