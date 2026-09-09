package org.alpha.sekiroBedwar.stance;

import java.util.UUID;

/**
 * 架势变化观察者（内部接线用：由 {@code api.internal} 安装，转广播公共事件
 * {@link org.alpha.sekiroBedwar.api.events.StanceChangeEvent}）。
 */
@FunctionalInterface
public interface StanceObserver {

    /** 一次数值型架势变化应用后回调（before ≠ after；自然恢复不回调）。 */
    void onChange(UUID player, double before, double after, double max);
}
