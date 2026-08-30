package org.alpha.sekiroBedwar.block;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.lightning.LightningManager;
import org.alpha.sekiroBedwar.stance.StanceBreakManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Optional;

/**
 * 普通格挡 / 受击架势管理器（独立模块，与完美弹反系统分离）。
 *
 * <p>只处理<b>进行中（ACTIVE）决斗</b>内对方攻击（近战直接命中 或 弓箭/投射物射击者）；
 * 决斗外 / 第三方攻击保持原版。对每次<b>未进入完美弹反分支</b>的命中按 无格挡 / 普通格挡
 * 应用架势换算（Dbase = 攻击方武器面板伤害，Dactual = 实机血量伤害，见 {@code combat/} 包）：
 * <ul>
 *   <li><b>无格挡</b>（ΔS肉）：受击方架势 −= <b>Dactual</b>（实机血量伤害）× {@code hit-multiplier}，
 *       生命伤害按原版；</li>
 *
 * 每次命中处理前对<b>双方</b>调用 {@link StanceManager#markActive}：攻击方“产生攻击”、受击方
 * “受到攻击”均刷新自然下降 idle 计时（攻击方架势值可能不变，仍需维持不自然下降）。</li>
 *   <li><b>普通格挡</b>（ΔS格挡，盾牌格挡、未命中完美弹反窗口）：不完全免架势——
 *       防守方架势 −= <b>Dbase</b>（武器面板伤害）× {@code defender-multiplier}（默认 1.5），
 *       <b>攻击方不扣架势</b>（用户确认移除），生命伤害走原版盾牌减伤；</li>
 *   <li><b>破盾</b>（{@code block.shield-break.*}）：攻击方主手为配置的破盾武器（斧）命中普通格挡时，
 *       防守方架势 −= Dbase × {@code shield-break.stance-multiplier}（更高倍率），
 *       并短暂禁用其格挡（{@link StanceManager#disableBlocking} + 盾牌强制冷却）。</li>
 * </ul></p>
 *
 * <p><b>与完美弹反的分工</b>：{@code ParryManager} 在 <b>HIGH</b> 优先级判定完美弹反并
 * {@code setCancelled(true)}（弹开的命中不再进入本模块）；本模块在 <b>NORMAL</b> 优先级
 * （{@code ignoreCancelled=true}）处理其余全部命中——因此“格挡但未命中完美弹反窗口”的命中
 * 天然按普通格挡处理，绝不会被误判为完美弹反；同理，<b>完美弹反成功的命中绝不触发破盾</b>
 * （已取消，本模块不可见）。</p>
 *
 * <p><b>崩条 / 低血量钩子</b>：在扣架势之前把每次未弹反命中转给
 * {@link StanceBreakManager#onIncomingHit}（临界玩家被近战未弹反命中 → 崩条；
 * 远程命中不崩只维持临界；低血量受击 → 拉满到临界）。</p>
 */
public final class BlockManager {
    private final SekiroBedwar plugin;
    private final BlockConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final StanceBreakManager stanceBreakManager;
    private final LightningManager lightningManager;
    private final BlockListener listener;

    public BlockManager(SekiroBedwar plugin, BlockConfig config,
                        StanceManager stanceManager, DuelManager duelManager,
                        StanceBreakManager stanceBreakManager, LightningManager lightningManager) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.duelManager = duelManager;
        this.stanceBreakManager = stanceBreakManager;
        this.lightningManager = lightningManager;
        this.listener = new BlockListener(this);
    }

    /** 注册 Bukkit 监听。 */
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    /** 插件禁用（本模块无周期性任务，仅无操作）。 */
    public void disable() {
    }

    /**
     * 处理一次决斗内命中（由 {@link BlockListener} 在 NORMAL 优先级、忽略已取消事件下调用）。
     * {@code block.enabled=false} 时不做任何改动（完全原版战斗）。
     */
    public void handleDamage(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = CombatUtils.resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        // 仅 ACTIVE 决斗内对方攻击（第三方 / 决斗外攻击不参与架势变化）
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

        // 崩条 / 低血量规则：在扣架势【之前】判定（保证读到命中前的临界状态）。
        // 能走到这里的命中必是未完美弹反的（弹反的已在 HIGH 优先级被 cancel）——
        // 临界玩家被近战未弹反命中 → 崩条；远程命中不崩只维持临界；低血量受击 → 拉满到临界。
        stanceBreakManager.onIncomingHit(victim, event);

        // 战斗活跃维持：攻击方“产生攻击”、受击方“受到攻击”，都刷新自然下降的 idle 计时
        //（攻击方成功出手时架势值可能不变——无格挡/普通格挡只扣防守方——但持续作战不应被自然下降）。
        stanceManager.markActive(victim.getUniqueId());
        stanceManager.markActive(attacker.getUniqueId());

        if (victim.isBlocking()) {
            // 普通格挡（ΔS格挡）：不完全免架势——防守方架势 -= Dbase（武器面板伤害）× defender-multiplier。
            // 攻击方不扣架势（用户确认移除 attacker-multiplier）。
            // 破盾：攻击方主手为配置的破盾武器（斧）→ 更高倍率扣减 + 短暂禁用防守方格挡。
            double db = CombatUtils.baseDamage(attacker, event);
            if (config.shieldBreakEnabled() && isShieldBreaker(attacker)) {
                stanceManager.disableBlocking(victim.getUniqueId(), config.shieldBreakDisableBlockingSeconds());
                int ticks = Math.max(1, (int) Math.ceil(config.shieldBreakDisableBlockingSeconds() * 20.0));
                victim.setCooldown(Material.SHIELD, ticks);
                stanceManager.reduceStance(victim.getUniqueId(), db * config.shieldBreakStanceMultiplier());
            } else {
                stanceManager.reduceStance(victim.getUniqueId(), db * config.defenderMultiplier());
            }
        } else {
            // 无格挡命中（ΔS肉）：受击方架势 -= Dactual（实机血量伤害）× hit-multiplier
            double actual = event.getFinalDamage();
            if (actual > 0.0) {
                stanceManager.reduceStance(victim.getUniqueId(), actual * config.hitMultiplier());
            }
        }
        if (CombatUtils.resolveMeleeAttacker(event) != null) {
            lightningManager.onAttack(attacker, victim, false);
        }
    }

    /** 攻击方主手是否为破盾武器（配置的斧类集合）。 */
    private boolean isShieldBreaker(Player attacker) {
        return config.shieldBreakMaterials().contains(attacker.getInventory().getItemInMainHand().getType());
    }
}
