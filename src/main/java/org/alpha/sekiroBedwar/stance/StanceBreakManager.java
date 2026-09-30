package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
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
 *   <li><b>条件一（未弹反命中）</b>：临界玩家被对方的命中且未成功完美弹反
 *       （普通格挡或无格挡）→ 崩条——<b>近战与远程一视同仁</b>（用户 2026-09-20 拍板：
 *       弓箭虽然正常情况下打不出完美弹反，但「未被弹反的命中」同样崩条）。
 *       由 {@link org.alpha.sekiroBedwar.block.BlockManager} 在
 *       扣架势<b>之前</b>调用 {@link #onIncomingHit}（保证读到命中前的临界状态）。</li>
 *   <li><b>条件二（被弹反）</b>：临界玩家自己的<b>近战</b>攻击被对方完美弹反 → 崩条。
 *       由 {@link org.alpha.sekiroBedwar.parry.ParryManager} 完美弹反成功分支调用 {@link #onAttackParried}。</li>
 * </ul>
 *
 * <p><b>远程口径（2026-09-20 改）</b>：弓箭 / 投射物命中临界玩家<b>照常崩条</b>——旧口径
 * 「远程不崩只维持临界」已废除。弓在临界对决里是压制手段：临界一方用弓持续射击，
 * 对方举盾也只能吃普通格挡的架势扣减并最终崩条（远程不可弹反，这也是
 * {@code ParryManager} 给弓开「被完美弹反」分支的原因）。</p>
 *
 * <p><b>崩条后果</b>（{@link StanceManager#breakStance}）：当前架势清零 + 进入结算 / 逃离窗口
 * （{@code execution-seconds}）+ <b>受击状态</b>（{@code stagger.duration-seconds}，
 * 期间无法正常格挡但仍可移动、可攻击）。受击状态是否强制无法格挡由
 * {@code stagger.disable-blocking} 控制（默认 true）：开启时崩条瞬间 {@code setCooldown(SHIELD, …)}
 * 强制盾牌冷却，并由周期任务 {@link #enforceGuard} 按剩余时长持续刷新——受击状态内
 * {@code isBlocking()} 保持 false（完美弹反轮询也随之失效），窗口到期自动恢复。</p>
 *
 * <p><b>处决窗口破甲</b>（{@code stance.break.execution-armor.*}，默认开启，2026-09-27 追加）：
 * 崩条期间（处决窗口开启）<b>被处决者的护甲无效</b>——命中的护甲类减免被归零，伤害按「纯血伤」
 * 结算（见 {@link #applyExecutionArmorBypass}）。设计意图：崩条 = 门户大开的处决时机，护甲不该
 * 再把处决的收益吃掉；被处决方唯一的活路是趁窗口逃离。</p>
 *
 * <p><b>低血量拉临界</b>（{@code stance.break.low-health-threshold}，默认 2）：决斗中每次受击后，
 * 血量 ≤ 阈值且未死亡 → 架势强制拉到临界（幂等反复生效），不是崩条——低血量玩家持续处于临界，
 * 下一次未弹反的命中即崩条。</p>
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
     * <b>处决窗口破甲</b>（{@code stance.break.execution-armor.*}，默认开启）：崩条（处决窗口开启）
     * 期间<b>被处决者的护甲无效</b>——把本次命中的护甲类减免直接归零，伤害按「纯血伤」结算。
     *
     * <p><b>归零项</b>：{@code ARMOR}（护甲点减伤）与 {@code HARD_HAT}（仅头盔覆盖时的减伤）
     * 无条件归零；{@code MAGIC}（保护类附魔的减伤——1.21.11 的 {@link DamageModifier} 里没有单独的
     * {@code ARMOR_ENCHANTMENTS}，保护走 MAGIC）由 {@code strip-enchant} 控制，默认一并归零。
     * 抗性药水（{@code RESISTANCE}）、吸收之心（{@code ABSORPTION}）、盾牌格挡（{@code BLOCKING}）
     * <b>不动</b>——本机制只针对「护甲」。</p>
     *
     * <p><b>为什么用修饰符置零而不是按比例反推基础伤害</b>：{@code getFinalDamage()/getDamage()} 反推
     * 会把抗性 / 吸收一起卷进来（多扣），而 {@code setDamage(DamageModifier, 0)} 只精确移除指定减伤项，
     * 语义清晰、无副作用。API 已实测：spigot-api 1.21.11 同时具备
     * {@code isApplicable(DamageModifier)} / {@code getDamage(DamageModifier)} / {@code setDamage(DamageModifier,double)}。</p>
     *
     * <p><b>调用时序</b>：由 {@link org.alpha.sekiroBedwar.block.BlockManager} 在「ACTIVE 决斗内对方命中」
     * 判定之后、扣架势<b>之前</b>调用——因此后续的架势换算（无格挡取 {@code getFinalDamage()}）与
     * 低血量拉临界判定读到的都是破甲后的真实伤害。决斗外、非玩家来源（摔落 / 火焰 / 非玩家 TNT 等）
     * 不经过本路径。</p>
     */
    @SuppressWarnings({"deprecation", "removal"})
    public void applyExecutionArmorBypass(EntityDamageByEntityEvent event, Player victim) {
        if (!config.executionArmorEnabled() || event == null || victim == null) {
            return;
        }
        if (!stanceManager.isBroken(victim.getUniqueId())) {
            return; // 只在处决窗口（崩条）期间破甲
        }
        double base = event.getDamage();
        if (base <= 0.0) {
            return;
        }
        zeroModifier(event, DamageModifier.ARMOR);
        zeroModifier(event, DamageModifier.HARD_HAT);
        if (config.executionArmorStripEnchant()) {
            zeroModifier(event, DamageModifier.MAGIC);
        }
        if (config.executionArmorLog()) {
            plugin.getLogger().info("[处决破甲] " + victim.getName()
                    + " 面板=" + round2(base) + " 实收=" + round2(event.getFinalDamage()) + "（护甲减免已归零）");
        }
    }

    /** 该减免项适用时置零（{@code isApplicable} 守卫，避免对不适用的修饰符写入）。 */
    private static void zeroModifier(EntityDamageByEntityEvent event, DamageModifier modifier) {
        if (event.isApplicable(modifier)) {
            event.setDamage(modifier, 0.0);
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 处理一次决斗内<b>未弹反</b>命中（由 {@link org.alpha.sekiroBedwar.block.BlockManager} 在
     * 扣架势之前调用，保证读到命中前的临界状态）。
     *
     * <ol>
     *   <li><b>崩条条件一</b>：命中来自玩家（近战直接命中<b>或</b>投射物射击者）且受击方处于临界
     *       → 按格挡 / 无格挡开关崩条。远程与近战同口径（2026-09-20 改）。</li>
     *   <li><b>低血量拉临界</b>：受击后血量 ≤ 阈值且未死亡 → 架势拉到临界（幂等，不崩条）。</li>
     * </ol>
     */
    public void onIncomingHit(Player victim, EntityDamageByEntityEvent event) {
        // 攻击方 = 近战直接命中 或 投射物射击者：崩条对远/近一视同仁（用户 2026-09-20 拍板
        //「远程命中、只要没有被完美弹反，也能崩条」）。此前只认近战，导致临界玩家被箭矢
        // 命中只维持临界、不崩，与被压制方应有的压力不符。
        Player attacker = CombatUtils.resolveAttacker(event);
        boolean playerSource = attacker != null;
        if (playerSource && stanceManager.isCritical(victim.getUniqueId())) {
            if (victim.isBlocking() && config.breakOnBlockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker.getUniqueId());
            } else if (!victim.isBlocking() && config.breakOnUnblockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker.getUniqueId());
            }
        }
        double postHit = victim.getHealth() - event.getFinalDamage();
        if (postHit > 0.0 && postHit <= config.lowHealthThreshold()) {
            stanceManager.setStance(victim.getUniqueId(), 0.0);
        }
    }

    /**
     * 处理一次<b>不派发伤害事件</b>的近战命中（供自行施加架势伤害的模块调用，如踩头的轻踩）。
     *
     * <p>常规命中由 {@code BlockManager.handleDamage} 调 {@link #onIncomingHit}，而轻踩只扣
     * 固定架势、没有 {@code EntityDamageByEntityEvent}——若不显式走这里，就会出现
     * 「临界中被踩头却不崩条」（用户 2026-09-20 反馈）。判定口径与普通近战命中完全一致：
     * 近战 → 按格挡 / 无格挡开关崩条；随后若血量偏低 → 拉临界。</p>
     *
     * <p>判定必须在扣架势<b>之前</b>调用，保证读到的是命中前的临界状态
     * （踩头先扣 1 架势会把临界打成 0，之后再判就不是临界了）。</p>
     *
     * @param attacker   命中方（可为空，仅用于公共事件对手信息）
     * @param victim     受击方
     * @param postDamage 本次命中后的血量（用于低血量拉临界；轻踩无伤害可传当前血量）
     */
    public void onMeleeHitWithoutEvent(Player attacker, Player victim, double postDamage) {
        if (victim == null) {
            return;
        }
        if (stanceManager.isCritical(victim.getUniqueId())) {
            if (victim.isBlocking() && config.breakOnBlockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker == null ? null : attacker.getUniqueId());
            } else if (!victim.isBlocking() && config.breakOnUnblockedHit()) {
                breakStance(victim);
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker == null ? null : attacker.getUniqueId());
            }
        }
        if (postDamage > 0.0 && postDamage <= config.lowHealthThreshold()) {
            stanceManager.setStance(victim.getUniqueId(), 0.0);
        }
    }

    /**
     * 处理一次完美弹反成功（由 {@link org.alpha.sekiroBedwar.parry.ParryManager} 调用）：
     * 被弹反方（攻击者）若处于临界 → 崩条（弹反仅限近战，天然满足“近战才会崩”）。
     * {@code parryer} 为弹反成功方（仅用于公共事件对手信息，可空）。
     */
    public void onAttackParried(Player attacker, Player parryer) {
        if (config.breakOnParriedAttack() && stanceManager.isCritical(attacker.getUniqueId())) {
            breakStance(attacker);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                    attacker.getUniqueId(), parryer == null ? null : parryer.getUniqueId());
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
