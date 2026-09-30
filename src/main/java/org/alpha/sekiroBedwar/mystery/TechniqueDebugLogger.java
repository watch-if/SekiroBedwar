package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.api.TechniqueId;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCancelEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCompleteEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueFailEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueHitEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueStartEvent;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

/**
 * 取证日志器（{@code mystery.debug-log=true} 时由 {@link MysteryManager} 注册；默认不注册）。
 *
 * <p>覆盖两类取证，都只监听 / 只读 / 只打印，不改任何战斗逻辑：</p>
 *
 * <p><b>① 秘传判定</b>：把插件<b>自己已经发出</b>的秘传生命周期事件（Start / Hit / Complete / Fail / Cancel）
 * 打到 console，形成「插件侧权威判定」日志，用来和外部 bot（Node decide.js）的<b>客户端自评</b>
 * 「我完成了飞渡 / 一心」对照——划清两条日志的界限：</p>
 * <ul>
 *   <li>console 出现 {@code COMPLETE fei-du-fu-zhou} ⇒ 插件确实判完成（纸人应当在同一方法里发放）；</li>
 *   <li>console 出现 {@code FAIL ... reason=OUT_OF_RHYTHM reached=N} ⇒ 插件在第 N 击判脱拍，
 *       bot 的「完成」是它自己客户端节奏的误判（服务端 tick 与客户端 ms 漂移 / 节奏不对）；</li>
 *   <li>console <b>完全没有</b>该玩家该式的 Start/Hit ⇒ 命中根本没进连段（多半是当时不在 ACTIVE 决斗内，
 *       秘传命中钩子只在 ACTIVE 决斗近战推进）。</li>
 * </ul>
 *
 * <p><b>② 伤害失效</b>（{@code [伤害取证]}）：MONITOR 记录「没产生效果」的 PvP 命中（被取消 / 最终伤害≈0 /
 * 受击方仍在保护帧内），附 {@code cancelled / finalDamage / noDamageTicks/maximumNoDamageTicks / blocking}，
 * 用来定位「长段无敌 / 攻击没反应」的真凶（保护帧没关？被哪个插件取消？护甲吃光？）。</p>
 */
public final class TechniqueDebugLogger implements Listener {

    private final SekiroBedwar plugin;

    public TechniqueDebugLogger(SekiroBedwar plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStart(SecretTechniqueStartEvent e) {
        log("START " + key(e.technique()) + " by=" + name(e.player()) + duel(e.duelId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SecretTechniqueHitEvent e) {
        log("HIT   " + key(e.technique()) + " by=" + name(e.player())
                + " 段=" + e.hitIndex() + (e.parried() ? "(被弹反)" : "")
                + " 目标=" + name(e.target()) + duel(e.duelId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onComplete(SecretTechniqueCompleteEvent e) {
        log("COMPLETE " + key(e.technique()) + " by=" + name(e.player())
                + " 总段=" + e.totalHits() + " 有效=" + e.validHits() + duel(e.duelId())
                + "  ← 插件判完成，奖励（纸人/架势/HP）应已发放");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFail(SecretTechniqueFailEvent e) {
        log("FAIL  " + key(e.technique()) + " by=" + name(e.player())
                + " reason=" + e.reason() + " reached=" + e.reachedHits() + duel(e.duelId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCancel(SecretTechniqueCancelEvent e) {
        log("CANCEL " + key(e.technique()) + " by=" + name(e.player())
                + " reason=" + e.reason() + " reached=" + e.reachedHits() + duel(e.duelId()));
    }

    private void log(String msg) {
        plugin.getLogger().info("[秘传取证] " + msg);
    }

    // ==================== 取消点分段探测 ====================
    // 本日志器须【最后注册】（SekiroBedwar.onEnable 末尾），使其各优先级探针在同一优先级内
    // 排在所有取消者之后 → 每段探针都能读到「本段及之前」的取消，归因准确：
    //   LOWEST 段取消 = 风弹禁攻(WindChargeManager)；LOW 段 = 完美弹反 / 连续弹反封印(ParryManager)；
    //   NORMAL 段 = (无已知取消者，出现即异常)；HIGH 段 = 炎上TNT对非玩家(AttributeManager)；
    //   HIGHEST 段 = 雾璃鸦护身(CrowManager) / 识破(DangerManager)。
    /** 本次事件首个观察到 isCancelled 的优先级段（主线程顺序处理，单字段即可）。 */
    private EventPriority cancelBand;

    @EventHandler(priority = EventPriority.LOWEST)
    public void bandLowest(EntityDamageByEntityEvent e) {
        cancelBand = null; // 新事件开始，重置
        probe(e, EventPriority.LOWEST);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void bandLow(EntityDamageByEntityEvent e) {
        probe(e, EventPriority.LOW);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void bandNormal(EntityDamageByEntityEvent e) {
        probe(e, EventPriority.NORMAL);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void bandHigh(EntityDamageByEntityEvent e) {
        probe(e, EventPriority.HIGH);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void bandHighest(EntityDamageByEntityEvent e) {
        probe(e, EventPriority.HIGHEST);
    }

    private void probe(EntityDamageByEntityEvent e, EventPriority band) {
        if (cancelBand == null && e.isCancelled()) {
            cancelBand = band;
        }
    }

    /** 取消段 → 嫌疑机制（便于直接读懂）。 */
    private static String bandSuspect(EventPriority band) {
        if (band == null) {
            return "?";
        }
        return switch (band) {
            case LOWEST -> "风弹禁攻(WindCharge LOWEST)";
            case LOW -> "完美弹反/连续弹反封印(Parry LOW)";
            case NORMAL -> "异常:NORMAL段取消(无已知取消者)";
            case HIGH -> "炎上TNT对非玩家(Attribute HIGH)";
            case HIGHEST -> "雾璃鸦护身/识破(Crow|Danger HIGHEST)";
            default -> band.name();
        };
    }

    /**
     * 伤害取证（MONITOR，事件链最末，读到最终状态）：只记录「这一击没产生效果」的 PvP 命中——
     * 被取消 / 最终伤害≈0 / 受击方仍在保护帧内，并附【取消发生在哪个优先级段】以指认机制。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = CombatUtils.resolveAttacker(e);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        boolean cancelled = e.isCancelled();
        double fin = e.getFinalDamage();
        int noDmg = victim.getNoDamageTicks();
        int maxNoDmg = victim.getMaximumNoDamageTicks();
        boolean noEffect = cancelled || fin <= 0.001 || noDmg > 0;
        if (!noEffect) {
            return; // 正常命中不记录
        }
        plugin.getLogger().info("[伤害取证] " + attacker.getName() + "→" + victim.getName()
                + " final=" + String.format("%.2f", fin)
                + " cancelled=" + cancelled
                + " noDmgTicks=" + noDmg + "/" + maxNoDmg
                + " blocking=" + victim.isBlocking()
                + (cancelled ? "  取消段=" + cancelBand + " ⇒ " + bandSuspect(cancelBand) : "")
                + (!cancelled && maxNoDmg > 0 ? "  ← 保护帧开着(iframe没关?)" : ""));
    }

    private static String key(TechniqueId id) {
        return id == null ? "?" : id.key();
    }

    private static String name(UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString().substring(0, 8);
    }

    private static String duel(UUID duelId) {
        return duelId == null ? " 决斗=无" : " 决斗=" + duelId.toString().substring(0, 8);
    }
}
