package org.alpha.sekiroBedwar.api;

/**
 * 架势阶段（公共 API，稳定枚举；优先级 BROKEN &gt; CRITICAL &gt; NORMAL）。
 */
public enum StancePhase {
    /** 未处于决斗 / 无架势状态（数值读取均为 0）。 */
    NONE,
    /** 正常。 */
    NORMAL,
    /** 临界：已消耗比例达临界线（默认 = 架势条空），尚未崩条。 */
    CRITICAL,
    /** 崩条：处决 / 逃离窗口开启中。 */
    BROKEN
}
