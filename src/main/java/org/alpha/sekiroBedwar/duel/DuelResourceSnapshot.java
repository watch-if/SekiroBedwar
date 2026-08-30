package org.alpha.sekiroBedwar.duel;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决斗开始时的资源快照（不可变值对象）。
 *
 * <p>记录一名玩家在决斗开始（ACTIVE）时背包中各<b>配置资源类型</b>的数量，
 * 供第三方介入结束决斗时回滚到起始状态。快照贯穿整个决斗（不立即删除），
 * 决斗结束时由 {@link SettlementManager} 清理。</p>
 */
public final class DuelResourceSnapshot {
    private final Map<Material, Integer> counts;

    /** @param counts 配置资源类型的数量映射（构造时拷贝，不影响调用方）。 */
    public DuelResourceSnapshot(Map<Material, Integer> counts) {
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /** 该资源类型的起始数量；未记录返回 0。 */
    public int getCount(Material type) {
        return counts.getOrDefault(type, 0);
    }

    /** 数量映射的拷贝（调用方修改不影响本快照）。 */
    public Map<Material, Integer> resourceCounts() {
        return new LinkedHashMap<>(counts);
    }

    /** 是否记录到任何资源数量（第三方回滚前判断快照是否有效）。 */
    public boolean hasData() {
        return !counts.isEmpty();
    }
}
