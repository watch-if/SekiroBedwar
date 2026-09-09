package org.alpha.sekiroBedwar.api;

/**
 * 忍具 / 道具使用的稳定公共标识（{@link org.alpha.sekiroBedwar.api.events.ShinobiToolUseEvent}）。
 *
 * <p><b>扩展约定</b>：新增忍具只追加枚举常量；外部插件按 id 消费并对未知值保持兼容，
 * 不需要因新忍具修改 API 架构。新增忍具若复用既有资源（如纸人计价的消耗品），
 * 优先为其单独增加 id 而非复用。</p>
 */
public enum ToolId {

    /** 纸人本体作为资源被消耗（投掷抵扣 / 命中传送等，target 视场景可空）。 */
    PAPER_DOLL("paper-doll", "纸人"),
    /** 漂流纸人：右键使用（血量上限减半换 5 纸人）。 */
    DRIFTING_PAPER_DOLL("drifting-paper-doll", "漂流纸人"),
    /** 雾璃鸦：右键激活悬停护身。 */
    CROW("crow", "雾璃鸦"),
    /** 风弹：投掷引爆爆风墙（target = 触碰受封玩家，可空）。 */
    WIND_CHARGE("wind-charge", "风弹"),
    /** 巴之雷：落雷触发（attacker 释放、target 受雷者）。 */
    LIGHTNING_STRIKE("lightning-strike", "巴之雷·落雷"),
    /** 巴之雷·雷反：被雷击者在窗口内成功反制。 */
    LIGHTNING_REVERSAL("lightning-reversal", "巴之雷·雷反"),
    /** 盾牌弹反（纸人完美弹反窗口激活）。 */
    SHIELD_DEFLECT("shield-deflect", "盾牌弹反"),
    /** 锈丸：属性窗口开启（毒 DoT 施压生效）。 */
    RUST("rust", "锈丸"),
    /** 炎上：属性窗口开启（火焰附加生效）。 */
    BURN("burn", "炎上"),
    /** 僵尸头颅：左键释放恐怖区。 */
    TERROR_HEAD("terror-head", "僵尸头颅");

    private final String configKey;
    private final String displayName;

    ToolId(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    /** 与 duel.yml 配置段键一致（跨版本稳定）。 */
    public String configKey() {
        return configKey;
    }

    /** 中文显示名。 */
    public String displayName() {
        return displayName;
    }
}
