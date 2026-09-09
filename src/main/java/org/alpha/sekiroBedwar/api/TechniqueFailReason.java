package org.alpha.sekiroBedwar.api;

/**
 * 秘传失败 / 中断原因（公共 API，稳定枚举；出现在
 * {@link org.alpha.sekiroBedwar.api.events.SecretTechniqueFailEvent}）。
 */
public enum TechniqueFailReason {
    /** 相邻两击间隔超出容差窗口（节奏脱拍，本击作为新的一式）。 */
    OUT_OF_RHYTHM,
    /** 起手动作未在换刀后的衔接窗口（1 tick）内发出（苇名第一击 / 龙闪左键）。 */
    CONNECT_TIMEOUT,
    /** 终结段不满足强制条件（一心七连：第 7 击非危攻击）。 */
    FINISHER_NOT_MET,
    /** 释放所需资源不足（如龙闪纸人不足）。 */
    INSUFFICIENT_RESOURCE,
    /** 判定被更高优先规则接管 / 被拒绝（保留）。 */
    REJECTED,
    /** 其他 / 未来新增原因。 */
    OTHER
}
