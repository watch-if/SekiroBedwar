package org.alpha.sekiroBedwar.parry;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.danger.DangerManager;
import org.alpha.sekiroBedwar.deflect.DeflectManager;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.lightning.LightningManager;
import org.alpha.sekiroBedwar.stance.StanceBreakManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Optional;

/**
 * 完美弹反系统核心管理器（独立模块，与普通格挡系统分离，不把逻辑堆进事件监听器）。
 *
 * <p>本管理器<b>只负责完美弹反分支</b>：处理进行中（ACTIVE）决斗内两名对手之间的
 * <b>近战命中</b>与<b>投射物（弓箭）命中</b>（2026-09-20 起弓箭也参与弹反判定，
 * 理由见下「弓的临界特殊用法」）。对每次命中判定是否命中完美弹反窗口：
 * <ul>
 *   <li><b>命中窗口</b>（正在格挡且格挡开始于窗口内）：{@code event.setCancelled(true)}
 *       完整弹开这次攻击（免生命伤害与击退），攻击方架势 −= Dbase（武器面板伤害）×
 *       {@code parry-attacker-multiplier}（默认 3.0，惩罚攻击方），受击方（弹反者）自身 −=
 *       <b>固定值</b> {@code parry-victim-cost}（默认 5.0，不是乘数）；</li>
 *   <li><b>完美弹反失败</b>（未格挡，或格挡但超出窗口）：本管理器不改动、不取消，
 *       交由 {@code block/} 包的普通格挡 / 无格挡分支处理——格挡但超出窗口 → 普通格挡，
 *       不会被误判为完美弹反。</li>
 * </ul></p>
 *
 * <p><b>优先级分工</b>：本管理器在 <b>HIGH</b> 优先级监听（先于普通格挡模块 NORMAL），
 * 取消完美弹反的命中；其余命中由普通格挡模块处理，二者天然互斥不重复。</p>
 *
 * <p><b>连续 / 快速攻击</b>：弹反成功后调用 {@link ParryWindowManager#consumeBlockStart}
 * 消耗本次“格挡开始”记录——一次按下只弹反一击，同一按住中的后续命中按普通格挡处理，
 * 防止连击被误判为连续完美弹反。</p>
 *
 * <p><b>临界状态语义</b>：完美弹反成功后弹反者无论是否临界都进入维持态
 * （{@link StanceManager#markActive}）——刷新 idle 计时、防止架势自然恢复，把当前架势"锁"住；
 * 自身扣费为固定值 {@code parry-victim-cost}（0 = 不扣）。被弹反方（攻击者）仅在
 * <b>本就处于临界</b>（扣架势<b>之前</b>评估，弹反惩罚扣到 0 不算临界）时，由
 * {@link StanceBreakManager#onAttackParried} 触发架势崩溃——临界玩家近战攻击被完美弹反才崩。</p>
 *
 * <p><b>弹反窗口判定</b>（服务器单调时钟，无攻击前 / 攻击后之分、无网络延迟补偿）：
 * <pre>
 *   完美弹反 ⟺ 举盾时刻 ≤ 命中时刻 ≤ 举盾时刻 + base-window-ms
 * </pre>
 * 举盾时刻 = 格挡轮询（{@code poll-ticks}）记录到的格挡开始；命中落在「举盾后 base-window-ms 内」
 * （默认 170ms）即完美弹反。同 tick 刚举盾即命中由 {@code eagerStart} 兜底（0 延时）。</p>
 *
 * <p><b>弓的临界特殊用法（2026-09-20 新增，用户拍板）</b>：双方都处于临界是原设计的死结
 * （谁先出手被弹反谁先崩）。弓被用作打破死结的手段——<b>临界（架势见底、尚未崩条）的弓手</b>
 * 射出的箭仍可被对方完美弹反，但弹反成功<b>不给弓手任何架势惩罚</b>，只给弓手叠加
 * <b>维持态</b>（禁自然恢复），于是弓手能持续射击压制对方（对方举盾也要吃架势扣减并最终崩条），
 * 自己却不至于「一次被弹反就崩条」。弓手不处于临界时按常规给完整惩罚；
 * <b>已崩条（处决窗口）时不享</b>此优待。远程被弹反不计入连续弹反封印（只统计近战）。</p>
 *
 * <p><b>纸人盾牌弹反（{@code deflect/}）</b>：主手举盾消耗纸人后 {@code deflect-window-ms}（默认 2s）
 * 内为强制完美弹反窗口——窗口内每一记命中（近战<b>或箭矢</b>）都直接走本管理器的完美弹反分支
 * （免 170ms 窗口与「一次按住只弹反一击」限制，且不消耗格挡记录）；危攻击仍不可弹反。</p>
 */
public final class ParryManager {
    private final SekiroBedwar plugin;
    private final ParryConfig config;
    private final ParryWindowManager window;
    private final LatencyCompensationManager latency;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final StanceBreakManager stanceBreakManager;
    private final ParrySealManager sealManager;
    private final LightningManager lightningManager;
    private final DangerManager dangerManager;
    private final DeflectManager deflectManager;
    private final org.alpha.sekiroBedwar.attribute.AttributeManager attributeManager;
    private final org.alpha.sekiroBedwar.mystery.MysteryManager mysteryManager;
    private final ParryMarker parryMarker;
    private final ParryListener listener;

    public ParryManager(SekiroBedwar plugin, ParryConfig config,
                        StanceManager stanceManager, DuelManager duelManager,
                        StanceBreakManager stanceBreakManager, ParrySealManager sealManager,
                        LightningManager lightningManager, DangerManager dangerManager,
                        DeflectManager deflectManager,
                        org.alpha.sekiroBedwar.attribute.AttributeManager attributeManager,
                        org.alpha.sekiroBedwar.mystery.MysteryManager mysteryManager,
                        ParryMarker parryMarker) {
        this.plugin = plugin;
        this.config = config;
        this.latency = new LatencyCompensationManager(config);
        this.window = new ParryWindowManager(plugin, config, latency, duelManager);
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.stanceBreakManager = stanceBreakManager;
        this.sealManager = sealManager;
        this.lightningManager = lightningManager;
        this.dangerManager = dangerManager;
        this.deflectManager = deflectManager;
        this.attributeManager = attributeManager;
        this.mysteryManager = mysteryManager;
        this.parryMarker = parryMarker;
        this.listener = new ParryListener(this, window, latency);
    }

    /** 注册 Bukkit 监听并启动格挡轮询任务。 */
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        window.enable();
    }

    /** 插件禁用：取消轮询任务并清空延迟 / 窗口状态。 */
    public void disable() {
        window.disable();
        latency.clear();
    }

    /**
     * 处理一次决斗内近战命中（由 {@link ParryListener} 在 <b>HIGH</b> 优先级调用）。
     * {@code parry.enabled=false} 时不做任何改动（完全原版战斗）。
     *
     * <p>只有命中完美弹反窗口才在此分支处理（取消 + 架势换算 + 消耗格挡记录）；
     * 其余情况直接返回，交给普通格挡模块（NORMAL 优先级）处理。</p>
     */
    public void handleDamage(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        // 攻击方：近战直接命中，或投射物（弓箭）射击者——连续弹反计数 / 封印需覆盖弓箭
        Player attacker = CombatUtils.resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        // 仅 ACTIVE 决斗内对方攻击（第三方 / 决斗外攻击不参与弹反）
        Optional<Duel> opt = duelManager.getDuel(victim.getUniqueId());
        if (opt.isEmpty()) {
            return;
        }
        Duel duel = opt.get();
        if (duel.getState() != DuelState.ACTIVE || !duel.contains(attacker.getUniqueId())) {
            return;
        }
        double eventBase = event.getDamage();
        if (eventBase <= 0.0) {
            return;
        }
        // 连续弹反计数器窗口（seal）：攻击方处于“攻势无效”封印期间，所有攻击（近战与弓箭）
        // 一律无伤害、无作用——直接取消（免伤害与击退），不进入普通格挡 / 弹反换算。
        // block 模块 NORMAL + ignoreCancelled 自然跳过已取消的命中。
        if (sealManager.isSealed(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // 危攻击（矛 + 突进附魔 + 疾跑）不可被完美弹反：打断连续被弹反计数，直接交 block 模块处理。
        if (dangerManager.isDangerAttack(event)) {
            sealManager.onHitLanded(attacker);
            return;
        }
        // 近战 or 投射物分流：投射物（弓箭）2026-09-20 起也参与完美弹反判定，
        // 但被弹反时【不给弓手架势惩罚】——改为只给弓手叠加维持态（详见下方 handleParried）。
        boolean melee = CombatUtils.resolveMeleeAttacker(event) != null;
        // 纸人盾牌弹反窗口在此【两条路径都适用】：窗口内每一记命中（近战或箭矢）都按完美弹反处理，
        // 绕过「举盾后 170ms」窗口与「一次按住只弹反一击」限制。危攻击判定在更前面（不受窗口影响）；
        // 受击状态（无法格挡）期间不授予强制弹反。
        boolean forced = deflectManager.isDeflecting(victim.getUniqueId()) && stanceManager.canBlock(victim);
        // 完美弹反失败：未格挡 → 无格挡分支；格挡但超出窗口 → 普通格挡分支。
        // 均不由本管理器处理，直接返回（block 模块按普通格挡/无格挡规则应用，绝不误判为完美弹反）。
        // 未弹反的成功命中 → 打断攻击方的连续被弹反计数。
        if (!forced && (!window.isBlocking(victim) || !isPerfectParry(victim))) {
            sealManager.onHitLanded(attacker);
            return;
        }
        handleParried(event, attacker, victim, forced, melee);
    }

    /**
     * 完美弹反成功分支（近战与投射物共用）。
     *
     * <p><b>近战</b>：取消事件（免伤害与击退），攻击方架势 -= {@code Dbase × parry-attacker-multiplier}，
     * 弹反者自身 -= 固定值，并计入连续被弹反封印计数。</p>
     *
     * <p><b>投射物（弓箭，2026-09-20 新增）</b>：<b>弓的临界特殊用法</b>——若弓手此时处于
     * <b>临界（架势见底、尚未崩条）</b>，被完美弹反<b>不给任何架势惩罚</b>，只给弓手叠加
     * <b>维持态</b>（{@link StanceManager#markActive}，刷新 idle 计时、禁自然恢复），
     * 于是弓手可以持续射击压制对方而不至于「一次被弹反就自己崩条」；若弓手不处于临界，
     * 则按常规给完整架势惩罚（与近战一致）。弓手<b>已经崩条（处决窗口）时不享</b>此优待
     * （用户 2026-09-20 拍板「不含」）。</p>
     *
     * <p>无论近战还是投射物，弹反成功都：取消事件、写 {@link ParryMarker}（供踩头等
     * 不走原版伤害事件的模块查询）、双向音效、清除弹反者的破盾状态、把盾牌冷却归零。
     * 封印计数只统计<b>近战</b>（远程被弹反不计入封印，用户拍板口径）。</p>
     */
    private void handleParried(EntityDamageByEntityEvent event, Player attacker, Player victim,
                               boolean forced, boolean melee) {
        // 完美弹反：完整弹开这次攻击（cancel 同时免生命伤害与击退，弹反者不被推走）。
        // 近战 Dbase = 武器面板伤害；投射物 Dbase = 事件基础伤害（弓箭实际基础伤害）。
        double db = CombatUtils.baseDamage(attacker, event);
        event.setCancelled(true);
        // 显式弹反标记：供不走原版伤害事件的模块（踩头轻踩 / 重踩）查询自己那一击是否被弹反
        //——「事件被取消」另有连续弹反封印一个来源，仅凭 isCancelled 无法区分。
        if (parryMarker != null) {
            parryMarker.mark(victim.getUniqueId());
        }
        // 完美弹反成功反馈：向双方各自位置播放音效（默认铁砧放置的清脆金属"叮"声）
        if (config.parrySoundEnabled()) {
            attacker.playSound(attacker.getLocation(), config.parrySound(),
                    config.parrySoundVolume(), config.parrySoundPitch());
            victim.playSound(victim.getLocation(), config.parrySound(),
                    config.parrySoundVolume(), config.parrySoundPitch());
        }
        if (melee) {
            // 连续被完美弹反计数：只统计近战（攻击方 = 被弹反者），在扣架势 / 崩条判定之前更新。
            sealManager.onAttackParried(attacker);
            // 崩条判定：在扣架势【之前】评估攻击方是否本就处于临界（弹反惩罚扣到 0 不算临界）。
            stanceBreakManager.onAttackParried(attacker, victim);
            // 完美弹反惩罚：攻击方架势 -= Dbase × parry-attacker-multiplier。
            // 造成方 = 弹反者（victim），走倍率入口（夜叉戮糖等）。
            stanceManager.reduceStanceBy(victim.getUniqueId(), attacker.getUniqueId(),
                    db * config.parryAttackerMultiplier());
        } else if (stanceManager.isCriticalStance(attacker.getUniqueId())) {
            // 弓的临界特殊用法：不给弓手架势惩罚，只叠加维持态（keeping 当前架势、禁自然恢复）。
            stanceManager.markActive(attacker.getUniqueId());
        } else {
            // 常规弓手：与近战同口径的完整弹反惩罚。
            stanceManager.reduceStanceBy(victim.getUniqueId(), attacker.getUniqueId(),
                    db * config.parryAttackerMultiplier());
        }
        // 弹反者自身架势 - parry-victim-cost（0 = 不扣自身架势）；
        // 无论是否临界都进入维持态（markActive）：刷新 idle 计时、防止架势自然恢复——
        // 成功弹反把当前架势"锁"在当前值，不能靠拖时间 / 弹反来恢复架势。
        stanceManager.reduceStance(victim.getUniqueId(), config.parryVictimCost());
        stanceManager.markActive(victim.getUniqueId());
        // 完美弹反成功 = 立刻清除破盾效果：消除弹反者既有的「无法格挡窗口」（破盾/崩条
        // 受击状态）并把盾牌冷却归零——完美弹反是终极防御动作，防御能力立即恢复，
        // 不会因之前被斧头破盾而仍然举不了盾。
        stanceManager.clearBlockingDisable(victim.getUniqueId());
        victim.setCooldown(Material.SHIELD, 0);
        // 一次按下只弹反一击：消耗本次格挡按住（holdConsumed 置位），
        // 同一按住中的后续命中按普通格挡处理（连击 / 快速攻击不会因同一按下被连续判为完美弹反）。
        // 纸人弹反窗口（forced）不消耗——窗口内每一击都要弹反，且不得污染窗口结束后的普通判定。
        if (!forced) {
            window.consumeBlockStart(victim.getUniqueId());
        }
        lightningManager.onAttack(attacker, victim, true);
        attributeManager.onAttack(attacker, victim, true); // 锈丸/炎上：被完美弹反清零连续计数
        mysteryManager.onAttack(attacker, victim, true);   // 秘传：被弹反仍算打出的一击（第 6 击加成除外）
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.perfectParry(victim.getUniqueId(),
                attacker.getUniqueId(), attacker.getInventory().getItemInMainHand().getType(),
                db * config.parryAttackerMultiplier());
    }

    /**
     * 弹反窗口判定：只检测「举盾后 base-window-ms + 网络补偿内」命中的攻击为完美弹反。
     * 举盾时刻 = 轮询记录的格挡开始 {@code blockStart}；命中落在
     * {@code [blockStart, blockStart + effectiveWindow]} 即完美弹反（默认 170ms + 高延迟补偿）。
     * 无攻击前 / 攻击后窗口之分、无 RTT/2 回溯——网络补偿只在 170ms 窗口上放大。
     *
     * <p><b>0 延时</b>：玩家已处于格挡但轮询尚未记录开始时刻（同 tick 内刚右键举盾，
     * {@code blockStart} 为空）→ 经 {@code eagerStart} 从命中时刻立即起算，举盾即刻生效。</p>
     * <p><b>一次按住只弹反一击</b>：本按住已被弹反消耗（{@code holdConsumed}）→ 不再判完美弹反。</p>
     */
    private boolean isPerfectParry(Player victim) {
        long hit = latency.monotonicMillis();
        Long start = window.getBlockStartMs(victim.getUniqueId());
        if (start == null) {
            // 轮询未记录但确实在格挡：同 tick 内刚举盾（0 延时）——从命中时刻起算
            if (!victim.isBlocking()) {
                return false;
            }
            start = window.eagerStart(victim.getUniqueId(), hit);
            if (start == null) {
                return false;
            }
        }
        // 一次按住只弹反一击：本次按住已被弹反消耗 → 按普通格挡处理
        if (window.isHoldConsumed(victim.getUniqueId())) {
            return false;
        }
        // 举盾后 base-window-ms + 网络补偿 内命中的攻击 = 完美弹反
        double rtt = latency.smoothRtt(victim);
        long elapsed = hit - start;
        return elapsed >= 0L && elapsed <= (long) latency.effectiveWindow(rtt);
    }
}
