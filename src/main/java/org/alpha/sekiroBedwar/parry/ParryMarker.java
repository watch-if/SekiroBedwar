package org.alpha.sekiroBedwar.parry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 完美弹反标记（{@code parry/} 组件，2026-09-20 新增）：记录「某个玩家刚刚完美弹反成功」，
 * 供<b>不走原版伤害事件</b>的模块查询自己那一击是否被弹反。
 *
 * <p><b>动机</b>：踩头（{@code stomp/}）的轻踩不产生伤害事件、重踩虽然走
 * {@code victim.damage(...)} 但「事件被取消」有<b>两个</b>来源——完美弹反成功，以及
 * 连续弹反封印（{@link ParrySealManager}）取消攻势。仅凭 {@code event.isCancelled()}
 * 无法区分，会把「被封印」误判成「被弹反」并错误地反转位移。因此由
 * {@link ParryManager} 在弹反成功分支写入显式标记，消费方按「本 tick 内是否刚发生」
 * 判定，语义唯一、不受其他取消来源干扰。</p>
 *
 * <p><b>生命周期</b>：由 {@code SekiroBedwar} 创建并同时注入 {@link ParryManager} 与
 * 消费方（同一实例 → 同一份状态）；标记带毫秒有效期，过期自然失效（无需清理任务）。</p>
 */
public final class ParryMarker {

    /** 标记有效期（毫秒）：足够覆盖同一 tick 内的伤害事件往返，又不至于跨 tick 误判。 */
    private static final long TTL_MS = 150L;

    /** 弹反者（= 受击方）→ 弹反发生时刻（服务器单调毫秒）。 */
    private final Map<UUID, Long> parriedAt = new HashMap<>();

    /** 记录一次完美弹反（由 {@link ParryManager} 在弹反成功分支调用）。 */
    public void mark(UUID parrier) {
        if (parrier != null) {
            parriedAt.put(parrier, now());
        }
    }

    /** 该玩家是否刚刚（{@link #TTL_MS} 内）完美弹反成功。 */
    public boolean isActive(UUID parrier) {
        Long at = parriedAt.get(parrier);
        return at != null && now() - at <= TTL_MS;
    }

    /** 消费标记（读取并清除）：模块用完即清，避免跨招式残留。 */
    public boolean consume(UUID parrier) {
        if (parrier == null) {
            return false;
        }
        Long at = parriedAt.remove(parrier);
        return at != null && now() - at <= TTL_MS;
    }

    /** 清理某玩家标记（死亡 / 离局 / 禁用）。 */
    public void clear(UUID parrier) {
        parriedAt.remove(parrier);
    }

    /** 清空全部标记（禁用）。 */
    public void clearAll() {
        parriedAt.clear();
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
