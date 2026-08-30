package org.alpha.sekiroBedwar.stance;

/**
 * 架势状态枚举（供本插件内其他模块查询目标架势所处阶段）。
 *
 * <p>优先级：{@link #BROKEN} &gt; {@link #CRITICAL} &gt; {@link #NORMAL}。
 * 不在决斗中（无架势状态）恒为 {@link #NORMAL}。</p>
 *
 * <p><b>设计语义（用户确认）</b>：崩条 = <b>处决窗口</b>（{@link #BROKEN}），
 * 不是「架势 = 第二血条」——崩条本身不致死，只表示目标处于可被处决的战术窗口
 * （配合结算模块击杀全额 / 窗口到期半额）。</p>
 */
public enum StanceStatus {
    /** 正常：不在决斗，或决斗中有架势但未临界、未崩条。 */
    NORMAL,
    /** 临界：已消耗架势比例达到临界比例（默认架势条空），未崩条。 */
    CRITICAL,
    /** 崩条：处决窗口开启（{@code isBroken()} 为真）。 */
    BROKEN
}
