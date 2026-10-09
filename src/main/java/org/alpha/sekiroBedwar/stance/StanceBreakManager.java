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

import java.util.List;
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
    /** 采样任务：决斗中每 5 tick 记一次架势读数（供"决斗结束后仍断回血"的残留读数用）。 */
    private BukkitTask stanceWatchTask;
    /** 玩家最后一次"决斗内"的架势读数缓存：{@code {current, max}}（{@code watchStances()} 每 5 tick 刷新）。 */
    private final java.util.Map<UUID, double[]> lastStanceReading = new java.util.concurrent.ConcurrentHashMap<>();
    /** 每个玩家最近一次决斗结束时间（用于 {@code post-duel-grace-seconds} 保护窗口）。 */
    private final java.util.Map<UUID, Long> lastDuelEndAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** 上次采样到的血量（{@code 回血取证} 的"旁路 setHealth"监测用）。 */
    private final java.util.Map<UUID, Double> lastHealthSeen = new java.util.concurrent.ConcurrentHashMap<>();
    /** 最近一次"走过回血事件"的时间戳（用于给血量上涨打"事件型 / 旁路型"标签）。 */
    private final java.util.Map<UUID, Long> lastEventGainAt = new java.util.concurrent.ConcurrentHashMap<>();

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
        // ★ 采样任务：架势读数缓存（与 guardTask 独立 —— 回血阻断需要它，
        //   而 guardTask 只在"禁格挡"开启时才跑）。
        if (stanceWatchTask == null) {
            stanceWatchTask = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this::watchStances, 1L, 5L);
        }
    }

    /** 插件禁用：取消强制冷却任务。 */
    public void disable() {
        if (stanceWatchTask != null) {
            stanceWatchTask.cancel();
            stanceWatchTask = null;
        }
        lastStanceReading.clear();
        lastDuelEndAt.clear();
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
                rewardBreaker(attacker);   // ★ 打进处决窗口者：架势回满 + 回血
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker.getUniqueId());
            } else if (!victim.isBlocking() && config.breakOnUnblockedHit()) {
                breakStance(victim);
                rewardBreaker(attacker);   // ★ 打进处决窗口者：架势回满 + 回血
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
                rewardBreaker(attacker);   // ★ 打进处决窗口者：架势回满 + 回血
                notifyBreak(attacker, victim);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.stanceBreak(
                        victim.getUniqueId(), attacker == null ? null : attacker.getUniqueId());
            } else if (!victim.isBlocking() && config.breakOnUnblockedHit()) {
                breakStance(victim);
                rewardBreaker(attacker);   // ★ 打进处决窗口者：架势回满 + 回血
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
            // ★ 弹反成功把对方打进处决窗口 → 弹反者获得奖励（架势回满 + 回血）
            rewardBreaker(parryer);
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
    /**
     * 自然回血阻断：按 {@code stance.health-regen.block-when} 口径取消<b>自然回血</b>
     * （金苹果 / 药水 / 秘传奖励等主动治疗不受影响）。
     *
     * <p><b>SATIATED 与 REGEN 一视同仁</b>：实机取证发现只拦 SATIATED 是不够的 ——
     * 玩家/机器人还在以 {@code reason=REGEN 量=1.0} 每秒回血，那条根本没被拦住
     * （1508 条 SATIATED 全部取消 ✓ vs 444 条 REGEN 全部放行 ✗），
     * 表现就是"架势没满却还能自然回血"。用 {@code name()} 比较以兼容旧 API。</p>
     */
    public void handleRegainHealth(EntityRegainHealthEvent event) {
        if (!config.blockNaturalRegen()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        boolean naturalReason = event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || "REGEN".equals(event.getRegainReason().name());
        if (config.blockNaturalRegenLog()) {
            plugin.getLogger().info("[回血取证] " + player.getName()
                    + " reason=" + event.getRegainReason()
                    + " 量=" + String.format(java.util.Locale.ROOT, "%.1f", event.getAmount())
                    + (naturalReason ? ""
                            : "（★ 非自然回血：不受架势阻断管辖 —— 药水/金苹果/秘传奖励等）"));
        }
        if (!naturalReason) {
            return;
        }
        if (shouldBlockNaturalRegen(player)) {
            event.setCancelled(true);
            return;
        }
        // 真正放行了一次"事件型"回血 → 记时间戳，供旁路监测区分
        // 「事件型」与「旁路 setHealth 型」（对**被取消**的事件不记，否则旁路行会被误标）。
        lastEventGainAt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** 按配置口径判断这次自然回血该不该被取消。 */
    private boolean shouldBlockNaturalRegen(Player player) {
        UUID uuid = player.getUniqueId();
        boolean hasStance = stanceManager.hasStance(uuid);
        double cur = stanceManager.getStance(uuid);
        double max = stanceManager.getMaxStance(uuid);

        // ★ 根因修复：决斗结束时 endDuel 会把架势状态整个清掉
        //   （hasStance=false、cur=max=0），原判定 `cur < max` 立刻不成立 → 自然回血放行。
        //   于是"架势没满却还能回血"。这里改成：读不到实时架势时，**沿用决斗内最后一次读数**
        //   （watchStances 每 5 tick 采一次，最多保留 post-duel-grace-seconds 秒）——
        //   只要那会儿架势没满，回血就继续被拦。
        boolean cachedNotFull = false;
        double[] last = lastStanceReading.get(uuid);
        if (!hasStance && last != null && last[1] > 0.0 && last[0] < last[1]) {
            Long endedAt = lastDuelEndAt.get(uuid);
            long graceMillis = (long) (config.postDuelRegenGraceSeconds() * 1000.0);
            if (endedAt == null || graceMillis <= 0 || System.currentTimeMillis() - endedAt < graceMillis) {
                cachedNotFull = true;
            }
        }

        boolean block = decideNaturalRegenBlock(config.blockNaturalRegenWhen(),
                hasStance, cur, max, inConfiguredRegenGame(player), cachedNotFull);

        if (config.blockNaturalRegenLog()) {
            plugin.getLogger().info("[回血取证] " + player.getName() + " reason=SATIATED 架势="
                    + String.format(java.util.Locale.ROOT, "%.1f/%.1f", cur, max)
                    + " 决斗中=" + (hasStance ? "是" : "否")
                    + " 残留读数=" + (last == null ? "无"
                            : String.format(java.util.Locale.ROOT, "%.1f/%.1f", last[0], last[1])
                              + (last[1] > 0.0 && last[0] < last[1] ? "(未满)" : "(满)"))
                    + " 口径=" + config.blockNaturalRegenWhen()
                    + " → " + (block ? "取消回血" : "放行"));
        }
        return block;
    }

    /**
     * 自然回血阻断的<b>判定表</b>（纯函数，便于单测/推演；不含任何 Bukkit 状态）。
     *
     * @param scope            配置口径
     * @param hasStance        此刻是否在 ACTIVE 决斗内（架势状态存在）
     * @param cur, max         此刻的架势值（不在决斗时为 0/0）
     * @param inConfiguredGame 是否身处 {@code stance.health-regen.games} 列出的对局/世界
     * @param cachedNotFull    是否"刚打完决斗、且最后一次读到的架势没满"（残留读数）
     * @return 是否取消这次自然回血
     */
    static boolean decideNaturalRegenBlock(StanceConfig.NaturalRegenScope scope,
                                           boolean hasStance, double cur, double max,
                                           boolean inConfiguredGame, boolean cachedNotFull) {
        boolean block = switch (scope) {
            case IN_DUEL -> hasStance;
            case GAMES -> inConfiguredGame;
            default -> hasStance && cur < max;
        };
        // 残留读数在任何口径下都生效：它就是"架势没满"这个事实本身，
        // 不该因为 endDuel 把状态清了就消失。
        return block || cachedNotFull;
    }

    /** 决斗结束：记下时间戳，供"刚打完的保护"判断。 */
    public void onDuelEnded(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        if (a != null) {
            lastDuelEndAt.put(a, now);
        }
        if (b != null) {
            lastDuelEndAt.put(b, now);
        }
    }

    /**
     * {@code games} 口径：玩家当前所在对局名或所在世界名命中配置列表即算"该断"。
     *
     * <p>对局名走 BedWars API；读不到（不在对局 / ScreamingBedWars 缺失）时用世界名兜底。
     * API 不可用时保守返回 false（不误断）。</p>
     */
    private boolean inConfiguredRegenGame(Player player) {
        List<String> games = config.blockNaturalRegenGames();
        if (games == null || games.isEmpty()) {
            return false;
        }
        if (player.getWorld() != null && matchesAny(games, player.getWorld().getName())) {
            return true;
        }
        try {
            return org.screamingsandals.bedwars.api.BedwarsAPI.getInstance().getPlayerManager()
                    .getPlayer(player.getUniqueId())
                    .map(org.screamingsandals.bedwars.api.player.BWPlayer::getGame)
                    .map(game -> game != null && matchesAny(games, game.getName()))
                    .orElse(false);
        } catch (RuntimeException | LinkageError ex) {
            return false;   // 保守：读不到就当没命中（不误断）
        }
    }

    /** 列表命中（大小写不敏感，去空格）。 */
    private static boolean matchesAny(List<String> games, String value) {
        if (value == null) {
            return false;
        }
        for (String g : games) {
            if (g != null && g.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 采样：决斗中每 5 tick 记一次 {@code current/max}；决斗结束后保留读数到
     * {@code post-duel-grace-seconds} 过期为止（过期即丢弃）。
     *
     * <p>{@code blockNaturalRegenLog} 开启时额外做"血量上涨旁路监测"：血量涨了却
     * 没有对应的回血事件 → 是有人直接改血量（秘传奖励/复活/其它插件），不受架势阻断管辖。</p>
     */
    private void watchStances() {
        long now = System.currentTimeMillis();
        long graceMillis = (long) (config.postDuelRegenGraceSeconds() * 1000.0);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (config.blockNaturalRegenLog()) {
                double hp = p.getHealth();
                Double prev = lastHealthSeen.get(id);
                lastHealthSeen.put(id, hp);
                if (prev != null && hp > prev + 1.0e-4) {
                    Long evAt = lastEventGainAt.get(id);
                    boolean byEvent = evAt != null && now - evAt < 5000L;
                    plugin.getLogger().info("[回血取证·血量] " + p.getName()
                            + " 血量 " + String.format(java.util.Locale.ROOT, "%.1f→%.1f", prev, hp)
                            + "（+" + String.format(java.util.Locale.ROOT, "%.1f", hp - prev) + "）"
                            + " 架势=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                                    stanceManager.getStance(id), stanceManager.getMaxStance(id))
                            + " 决斗中=" + (stanceManager.hasStance(id) ? "是" : "否")
                            + (byEvent ? " (事件型)" : " (旁路 setHealth 型 —— 不受架势阻断管辖，"
                                    + "查秘传奖励/复活/其它插件)"));
                }
            }
            if (stanceManager.hasStance(id)) {
                lastStanceReading.put(id, new double[]{
                        stanceManager.getStance(id), stanceManager.getMaxStance(id)});
                continue;
            }
            Long endedAt = lastDuelEndAt.get(id);
            if (endedAt == null || (graceMillis > 0 && now - endedAt > graceMillis)
                    || (graceMillis <= 0)) {
                lastStanceReading.remove(id);
                lastDuelEndAt.remove(id);
            }
        }
    }

    /**
     * <b>触发崩条</b>，并把目标打入「<b>破盾状态</b>」（处决窗口内不可举任何盾）。
     *
     * <p>不可举盾（盾与剑都冷却）的时长取 {@code stagger.duration-seconds} 与
     * {@code execution-seconds} 的<b>较大者</b>，即覆盖<b>整个处决窗口</b> ——
     * 崩条 = 门户大开，此时还能举盾格挡自相矛盾。</p>
     */
    private void breakStance(Player player) {
        stanceManager.breakStance(player.getUniqueId());
        if (!config.disableBlocking()) {
            return;
        }
        double seconds = Math.max(config.staggerDurationSeconds(), config.executionSeconds());
        if (seconds <= 0.0) {
            return;
        }
        // 走正规通道：窗口内 canBlock() 为假、isStaggered() 为真，
        // 且周期任务会按剩余时长持续刷新冷却（强制收盾，含已举起的盾与剑）。
        stanceManager.disableBlocking(player.getUniqueId(), seconds);
        CombatUtils.disableBlockingItems(player, Math.max(1, (int) Math.ceil(seconds * 20.0)));
    }

    /**
     * <b>打进处决窗口者的奖励</b>：<b>自身架势回满</b> + <b>回血</b>（默认 5 HP）。
     *
     * <p><b>为什么要给</b>：把对手打崩是进攻成功的成果，但处决窗口本身是<b>对方的逃离窗口</b>——
     * 若没有正反馈，"抢先手压架势"就只替对手创造机会，收益不对称。
     * 奖励方是<b>造成崩条的一方</b>（未格挡命中 / 举盾击破 / 完美弹反）。</p>
     *
     * <p><b>边界</b>：若奖励方自己也在崩条状态（双方几乎同时崩），跳过奖励 ——
     * 否则刚把架势设满就被自身崩条逻辑清零，观感是"奖励没生效"。</p>
     *
     * @param breaker 造成本次崩条的一方；null / 离线时不做任何事
     */
    private void rewardBreaker(Player breaker) {
        if (breaker == null || !breaker.isOnline()) {
            return;
        }
        if (!config.breakerRewardEnabled()) {
            return;
        }
        UUID uuid = breaker.getUniqueId();
        if (stanceManager.isBroken(uuid)) {
            return;
        }
        boolean did = false;
        // ① 架势回满
        if (config.breakerRewardRefillStance()) {
            double max = stanceManager.getMaxStance(uuid);
            if (max > 0.0) {
                stanceManager.setStance(uuid, max);
                did = true;
            }
        }
        // ② 回血（封顶到最大生命值）
        double heal = config.breakerRewardHealHp();
        if (heal > 0.0) {
            double maxHp = 20.0;
            try {
                var attr = breaker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null) {
                    maxHp = attr.getValue();
                }
            } catch (RuntimeException ignored) {
                // 取不到就退回默认 20，不因为"读不到属性"而丢掉奖励
            }
            double after = Math.min(maxHp, breaker.getHealth() + heal);
            if (after > breaker.getHealth()) {
                breaker.setHealth(after);
                did = true;
            }
        }
        if (did) {
            breaker.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent("§a§l你把对方打进了处决窗口！§f架势回满"
                            + (heal > 0.0 ? " §7+§f" + String.format(java.util.Locale.ROOT, "%.1f", heal) + " 血" : "")));
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
            CombatUtils.disableBlockingItems(player, ticks);   // 处决窗口/受击状态：盾与剑都禁
        }
    }
}
