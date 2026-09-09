package org.alpha.sekiroBedwar.api;

/**
 * 秘传武技的稳定公共标识（公共 API）。
 *
 * <p><b>扩展约定</b>：核心新增秘传时只需在此追加枚举常量并在实现中发事件；
 * 外部插件（统计 / 排位 / 录像）应<b>按 id 值消费</b>并对未知枚举保持兼容
 * （例如 switch 带 default 分支、或按 {@link #configKey()} 字符串匹配），
 * <b>不需要因新秘传而修改 API 架构</b>。</p>
 */
public enum TechniqueId {

    /** 第一式·飞渡浮舟：七击节奏连（间隔序列 ±容差），第 6 击有效命中额外架势伤。 */
    FEIDU_FUZU("fei-du-fu-zhou", "飞渡浮舟"),
    /** 第二式·苇名十字斩：空手换刀起手二连，第二击有效命中击退 + 架势交换。 */
    YAMEDO_CROSS_SLASH("yamedo-cross-slash", "苇名十字斩"),
    /** 第三式·龙闪：空手蓄力后左键释放双段音波柱。 */
    LONG_SHAN("long-shan", "龙闪"),
    /** 第四式·一心七连：七段近战连击 + 危攻击终结，逐段叠加架势增伤。 */
    ISSHIN_SEVEN_STRIKE("isshin-seven-strike", "一心七连");

    private final String configKey;
    private final String displayName;

    TechniqueId(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    /** duel.yml {@code mystery.<configKey>} 段的键（跨版本稳定）。 */
    public String configKey() {
        return configKey;
    }

    /** 中文显示名。 */
    public String displayName() {
        return displayName;
    }
}
