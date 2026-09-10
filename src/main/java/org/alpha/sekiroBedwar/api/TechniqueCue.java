package org.alpha.sekiroBedwar.api;

/**
 * 秘传发声提示（供 {@link SekiroBedwarApi#playTechniqueCue} 使用）：外部秘传不自己
 * playSound，而是声明"接上了一段 / 脱拍了"，由核心统一映射音效并按
 * 「领先者发声」规则决定是否出声——保证同一玩家并行多式时音效语言一致、不互相混音。
 *
 * <p>音效映射（与核心四式完全相同）：{@link #SUCCESS} = 铁砧落地声，
 * {@link #FAIL} = 铁砧打磨声（连续脱拍链中是否只播一次由外部式自行决定，
 * 核心只做领先者门控）。</p>
 */
public enum TechniqueCue {

    /** 本段节奏成功接上（调用前应已用 {@code setComboProgress} 更新自己的进度）。 */
    SUCCESS,

    /** 本段脱拍 / 连段失败（发出后核心自动回收该式对该玩家的进度槽）。 */
    FAIL
}
