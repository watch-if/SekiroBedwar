package org.alpha.sekiroBedwar.mystery;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 防击退护身（{@code mystery/} 组件，供飞渡浮舟 / 一心七连共用）：
 * 第三击起每次成功命中刷新 {@code mystery.knockback-guard-seconds}(1) 秒的
 * <b>不被击退</b>状态——<b>刷新</b>截止时间而非叠加时长。
 *
 * <p><b>实现</b>：{@code EntityDamageByEntityEvent} 在 <b>LOWEST</b> 记录受击玩家
 * 受击前速度快照，<b>MONITOR</b>（原版 / 弹反 / 危等全部击退逻辑应用完之后）把速度
 * 回写为快照——玩家攻击、投射物、TNT 等实体来源的击退一并抵消；只处理 by-entity
 * 伤害，避免误吞重力 / 下坠速度。被取消的伤害（如完美弹反）回写原值，等价无损。
 * 效果期间不排除伤害本身——只防<b>位移</b>，血量与架势照常结算。</p>
 */
public final class KnockbackGuard implements Listener {

    private final MysteryConfig config;
    /** 玩家 → 防击退截止时刻（单调毫秒）。 */
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    /** 玩家 → 本次伤害结算前的速度快照（LOWEST 写入、MONITOR 消费）。 */
    private final Map<UUID, Vector> preVelocity = new HashMap<>();

    public KnockbackGuard(MysteryConfig config) {
        this.config = config;
    }

    /** 刷新护身：截止时间推到 now + {@code knockback-guard-seconds}（不叠加）。 */
    public void refresh(UUID player) {
        double seconds = config.knockbackGuardSeconds();
        if (seconds <= 0.0) {
            return;
        }
        immuneUntil.put(player, System.nanoTime() / 1_000_000L + Math.round(seconds * 1000.0));
    }

    public boolean isImmune(UUID player) {
        Long until = immuneUntil.get(player);
        return until != null && System.nanoTime() / 1_000_000L < until;
    }

    public void clear(UUID player) {
        immuneUntil.remove(player);
        preVelocity.remove(player);
    }

    public void clearAll() {
        immuneUntil.clear();
        preVelocity.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamagePre(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!isImmune(victim.getUniqueId())) {
            return;
        }
        preVelocity.put(victim.getUniqueId(), victim.getVelocity().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamagePost(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        UUID id = victim.getUniqueId();
        Vector pre = preVelocity.remove(id);
        if (pre == null || !isImmune(id)) {
            return;
        }
        if (victim.isValid() && !victim.isDead()) {
            victim.setVelocity(pre); // 击退位移被抵消；伤害与架势结算照常生效
        }
    }
}
