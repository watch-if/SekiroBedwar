package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * 秘传系统配置：封装 <code>duel.yml</code> 的 <code>iframe:</code> 与
 * <code>mystery:</code> 两段。
 *
 * <p><b>无敌帧</b>：{@code iframe.global-enabled}（默认 true = 全局有，同原版）与
 * {@code iframe.duel-enabled}（默认 false = 决斗期间无）。对局外一律原版行为，
 * global-enabled 仅作用于对局内（决斗之外）。</p>
 *
 * <p><b>秘传武技</b>：连击判定统一按服务器 tick 数拍距
 * （{@code |Δtick − 目标拍| ≤ ceil(容差拍)}）；各式参数见
 * {@code mystery.fei-du-fu-zhou / yamedo-cross-slash / long-shan / isshin-seven-strike}。
 * 列表型配置缺省时用代码内置默认兜底（缺段=空表会导致模块静默失效）。</p>
 */
public final class MysteryConfig {

    /** 内置默认连击间隔（拍）：1→2 至 6→7，共 6 个间隔、7 次攻击。
     *  判定按 tick 数拍，目标应为整数拍（小数拍在整 tick 命中世界里无可达解）。 */
    static final double[] DEFAULT_FDFZ_INTERVALS = {7.0, 10.0, 6.0, 5.0, 6.0, 16.0};

    /** 一心七连默认间隔（tick）：7 / 5 / 5 / 6 / 8 / 10（第 7 击必须为危攻击）。 */
    static final double[] DEFAULT_ISSHIN_INTERVALS = {7.0, 5.0, 5.0, 6.0, 8.0, 10.0};

    private final SekiroBedwar plugin;

    // ---- 无敌帧 ----
    private boolean iframeGlobalEnabled;
    private boolean iframeDuelEnabled;
    private long iframeScanTicks;

    // ---- 飞渡浮舟 ----
    private boolean fdfzEnabled;
    private String fdfzName;
    private double[] fdfzIntervals;
    private double fdfzToleranceTicks;
    private double fdfzSixthBonusStance;
    private int fdfzRewardPaperDolls;
    private double fdfzRewardStance;
    private double fdfzRewardHealth;

    // ---- 苇名十字斩 ----
    private boolean yameEnabled;
    private String yameName;
    private double yameIntervalTicks;
    private double yameToleranceTicks;
    private double yameKnockbackLevel;
    private double yameVictimStancePenalty;
    private double yameSelfStanceRecovery;
    private long yameMinEmptyMs;
    private long yameMaxEmptyMs;

    // ---- 龙闪 ----
    private boolean lsEnabled;
    private String lsName;
    private long lsMinEmptyMs;
    private int lsPaperDollCost;
    private long lsSecondWaveDelayMs;
    private double lsWaveSpeed;
    private org.bukkit.Particle lsParticle;
    private double lsHitRadius;
    private double lsHeightUp;
    private double lsDamageHp;
    private double lsDamageStance;
    private double lsFallbackRange;

    // ---- 一心七连 ----
    private boolean isshinEnabled;
    private String isshinName;
    private double[] isshinIntervals;
    private double isshinToleranceTicks;
    private double isshinBonusPerStageStance;
    private int isshinRewardPaperDolls;
    private double isshinRewardStance;

    // ---- 两式共用：换刀 → 起手动作衔接窗（拍） ----
    private double armConnectTicks;

    // ---- 第三击起的防击退护身（飞渡浮舟 / 一心七连） ----
    private double knockbackGuardSeconds;

    public MysteryConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.iframeGlobalEnabled = yaml.getBoolean("iframe.global-enabled", true);
        this.iframeDuelEnabled = yaml.getBoolean("iframe.duel-enabled", false);
        this.iframeScanTicks = Math.max(1L, yaml.getLong("iframe.scan-ticks", 5L));

        this.fdfzEnabled = yaml.getBoolean("mystery.fei-du-fu-zhou.enabled", true);
        this.fdfzName = yaml.getString("mystery.fei-du-fu-zhou.name", "飞渡浮舟");
        this.fdfzIntervals = parseIntervals(yaml.getDoubleList("mystery.fei-du-fu-zhou.intervals-ticks"),
                DEFAULT_FDFZ_INTERVALS, "飞渡浮舟");
        this.fdfzToleranceTicks = Math.max(0.0, yaml.getDouble("mystery.fei-du-fu-zhou.tolerance-ticks", 0.5));
        this.fdfzSixthBonusStance = Math.max(0.0, yaml.getDouble("mystery.fei-du-fu-zhou.sixth-bonus-stance", 10.0));
        this.fdfzRewardPaperDolls = Math.max(0, yaml.getInt("mystery.fei-du-fu-zhou.reward-paper-dolls", 2));
        this.fdfzRewardStance = Math.max(0.0, yaml.getDouble("mystery.fei-du-fu-zhou.reward-stance", 5.0));
        this.fdfzRewardHealth = Math.max(0.0, yaml.getDouble("mystery.fei-du-fu-zhou.reward-health", 1.0));

        this.yameEnabled = yaml.getBoolean("mystery.yamedo-cross-slash.enabled", true);
        this.yameName = yaml.getString("mystery.yamedo-cross-slash.name", "苇名十字斩");
        this.yameIntervalTicks = Math.max(0.1, yaml.getDouble("mystery.yamedo-cross-slash.interval-ticks", 4.0));
        this.yameToleranceTicks = Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.tolerance-ticks", 0.5));
        this.yameKnockbackLevel = Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.knockback-level", 2.0));
        this.yameVictimStancePenalty = Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.victim-stance-penalty", 7.0));
        this.yameSelfStanceRecovery = Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.self-stance-recovery", 3.0));
        this.yameMinEmptyMs = Math.max(0L,
                Math.round(Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.min-empty-hand-seconds", 0.5)) * 1000.0));
        this.yameMaxEmptyMs = Math.max(this.yameMinEmptyMs,
                Math.round(Math.max(0.0, yaml.getDouble("mystery.yamedo-cross-slash.max-empty-hand-seconds", 1.0)) * 1000.0));

        this.lsEnabled = yaml.getBoolean("mystery.long-shan.enabled", true);
        this.lsName = yaml.getString("mystery.long-shan.name", "龙闪");
        this.lsMinEmptyMs = Math.max(0L,
                Math.round(Math.max(0.0, yaml.getDouble("mystery.long-shan.min-empty-hand-seconds", 1.0)) * 1000.0));
        this.lsPaperDollCost = Math.max(0, yaml.getInt("mystery.long-shan.paper-doll-cost", 2));
        this.lsSecondWaveDelayMs = Math.max(0L,
                Math.round(Math.max(0.0, yaml.getDouble("mystery.long-shan.second-wave-delay-seconds", 1.0)) * 1000.0));
        this.lsWaveSpeed = Math.max(0.1, yaml.getDouble("mystery.long-shan.wave-speed-blocks-per-tick", 1.5));
        this.lsParticle = parseParticle(yaml.getString("mystery.long-shan.particle", "ELECTRIC_SPARK"));
        this.lsHitRadius = Math.max(0.3, yaml.getDouble("mystery.long-shan.hit-radius", 1.5));
        this.lsHeightUp = Math.max(0.0, yaml.getDouble("mystery.long-shan.height-up", 4.0));
        this.lsDamageHp = Math.max(0.0, yaml.getDouble("mystery.long-shan.damage-hp", 2.0));
        this.lsDamageStance = Math.max(0.0, yaml.getDouble("mystery.long-shan.damage-stance", 10.0));
        this.lsFallbackRange = Math.max(1.0, yaml.getDouble("mystery.long-shan.fallback-range-blocks", 10.0));

        this.isshinEnabled = yaml.getBoolean("mystery.isshin-seven-strike.enabled", true);
        this.isshinName = yaml.getString("mystery.isshin-seven-strike.name", "一心七连");
        this.isshinIntervals = parseIntervals(yaml.getDoubleList("mystery.isshin-seven-strike.intervals-ticks"),
                DEFAULT_ISSHIN_INTERVALS, "一心七连");
        this.isshinToleranceTicks = Math.max(0.0, yaml.getDouble("mystery.isshin-seven-strike.tolerance-ticks", 0.2));
        this.isshinBonusPerStageStance = Math.max(0.0,
                yaml.getDouble("mystery.isshin-seven-strike.bonus-per-stage-stance", 3.0));
        this.isshinRewardPaperDolls = Math.max(0, yaml.getInt("mystery.isshin-seven-strike.reward-paper-dolls", 2));
        this.isshinRewardStance = Math.max(0.0, yaml.getDouble("mystery.isshin-seven-strike.reward-stance", 4.0));

        this.armConnectTicks = Math.max(0.0, yaml.getDouble("mystery.arm-connect-ticks", 4.0));
        this.knockbackGuardSeconds = Math.max(0.0, yaml.getDouble("mystery.knockback-guard-seconds", 1.0));
    }

    private org.bukkit.Particle parseParticle(String name) {
        try {
            return org.bukkit.Particle.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            plugin.getLogger().warning("跳过无效的龙闪粒子名: " + name);
            return org.bukkit.Particle.ELECTRIC_SPARK;
        }
    }

    /** 连击间隔序列：列表缺失 / 为空 / 含非正值时回退内置默认（列表型配置必须有代码默认）。 */
    private double[] parseIntervals(List<Double> raw, double[] defaults, String label) {
        if (raw == null || raw.isEmpty()) {
            return defaults.clone();
        }
        double[] out = new double[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Double v = raw.get(i);
            if (v == null || v <= 0) {
                plugin.getLogger().warning(label + "间隔序列含非正值，回退默认序列");
                return defaults.clone();
            }
            out[i] = v;
        }
        return out;
    }

    // ---- 无敌帧 ----

    /** 全局是否有无敌帧（默认 true = 原版行为）。 */
    public boolean iframeGlobalEnabled() {
        return iframeGlobalEnabled;
    }

    /** 决斗期间是否有无敌帧（默认 false = 决斗拼刀无保护帧）。 */
    public boolean iframeDuelEnabled() {
        return iframeDuelEnabled;
    }

    /** 无敌帧巡检间隔（tick）。 */
    public long iframeScanTicks() {
        return iframeScanTicks;
    }

    // ---- 飞渡浮舟 ----

    public boolean fdfzEnabled() {
        return fdfzEnabled;
    }

    public String fdfzName() {
        return fdfzName;
    }

    /** 相邻两击的目标间隔（tick，第 i 项 = 第 i+1 击到第 i+2 击），长度 +1 = 总击数。 */
    public double[] fdfzIntervals() {
        return fdfzIntervals.clone();
    }

    /** 每段间隔允许的上下浮动（tick）。 */
    public double fdfzToleranceTicks() {
        return fdfzToleranceTicks;
    }

    /** 第 6 击有效命中（未被完美弹反）的额外架势伤害。 */
    public double fdfzSixthBonusStance() {
        return fdfzSixthBonusStance;
    }

    public int fdfzRewardPaperDolls() {
        return fdfzRewardPaperDolls;
    }

    /** 完成奖励：恢复自身架势。 */
    public double fdfzRewardStance() {
        return fdfzRewardStance;
    }

    /** 完成奖励：恢复生命。 */
    public double fdfzRewardHealth() {
        return fdfzRewardHealth;
    }

    // ---- 苇名十字斩 ----

    public boolean yameEnabled() {
        return yameEnabled;
    }

    public String yameName() {
        return yameName;
    }

    /** 第一击 → 第二击的目标间隔（tick）。 */
    public double yameIntervalTicks() {
        return yameIntervalTicks;
    }

    /** 间隔允许的上下浮动（tick）。 */
    public double yameToleranceTicks() {
        return yameToleranceTicks;
    }

    /** 第二段有效命中的击退强度（原版击退附魔级别口径，默认 2）。 */
    public double yameKnockbackLevel() {
        return yameKnockbackLevel;
    }

    /** 第二段有效命中：受击方额外架势扣减。 */
    public double yameVictimStancePenalty() {
        return yameVictimStancePenalty;
    }

    /** 第二段有效命中：自身架势恢复。 */
    public double yameSelfStanceRecovery() {
        return yameSelfStanceRecovery;
    }

    /** 起手要求的空手最短持续时间（毫秒）：主手连续空置达该时长后换持近战武器才武装。 */
    public long yameMinEmptyMs() {
        return yameMinEmptyMs;
    }

    /** 起手允许的空手最长持续时间（毫秒，开区间上界）：≥该值属于龙闪窗口，防两式判定重叠。 */
    public long yameMaxEmptyMs() {
        return yameMaxEmptyMs;
    }

    // ---- 龙闪 ----

    public boolean lsEnabled() {
        return lsEnabled;
    }

    public String lsName() {
        return lsName;
    }

    /** 武装要求的主手连续空手最短时长（毫秒，默认 1s——与苇名的上限互补不重叠）。 */
    public long lsMinEmptyMs() {
        return lsMinEmptyMs;
    }

    /** 触发一次龙闪（两波）消耗的纸人数。 */
    public int lsPaperDollCost() {
        return lsPaperDollCost;
    }

    /** 第一波放出后第二波的补射延迟（毫秒）。 */
    public long lsSecondWaveDelayMs() {
        return lsSecondWaveDelayMs;
    }

    /** 波每 tick 前进距离（格）。 */
    public double lsWaveSpeed() {
        return lsWaveSpeed;
    }

    public org.bukkit.Particle lsParticle() {
        return lsParticle;
    }

    /** 波前命中判定半径（格，水平）。 */
    public double lsHitRadius() {
        return lsHitRadius;
    }

    /** 波柱向上覆盖高度（格，判定路径向上 4 格）。 */
    public double lsHeightUp() {
        return lsHeightUp;
    }

    /** 命中玩家的生命伤害（泛型直伤，不走格挡 / 弹反换算）。 */
    public double lsDamageHp() {
        return lsDamageHp;
    }

    /** 命中玩家的架势伤害。 */
    public double lsDamageStance() {
        return lsDamageStance;
    }

    /** 非决斗场景的波终止距离（格；决斗内以白圈为界）。 */
    public double lsFallbackRange() {
        return lsFallbackRange;
    }

    // ---- 衔接窗 ----

    /**
     * 换刀 → 起手动作（苇名第一击 / 龙闪左键）的衔接窗口（拍）：判定 = tick 计数差
     * ≤ ceil 该值，默认 4 拍（人手拔刀可及）。
     */
    public double armConnectTicks() {
        return armConnectTicks;
    }

    /** 第三击起每次成功命中刷新的防击退时长（秒；0 = 关闭）。 */
    public double knockbackGuardSeconds() {
        return knockbackGuardSeconds;
    }

    // ---- 一心七连 ----

    public boolean isshinEnabled() {
        return isshinEnabled;
    }

    public String isshinName() {
        return isshinName;
    }

    /** 相邻两击的目标间隔（tick，第 i 项 = 第 i+1 击到第 i+2 击），长度 +1 = 总击数。 */
    public double[] isshinIntervals() {
        return isshinIntervals.clone();
    }

    /** 每段间隔允许的上下浮动（tick）。 */
    public double isshinToleranceTicks() {
        return isshinToleranceTicks;
    }

    /** 第 3 段起每段有效命中的单段架势增伤基数（实际扣减 = 基数 × 已叠有效段数）。 */
    public double isshinBonusPerStageStance() {
        return isshinBonusPerStageStance;
    }

    /** 完成奖励：纸人数量。 */
    public int isshinRewardPaperDolls() {
        return isshinRewardPaperDolls;
    }

    /** 完成奖励：恢复自身架势。 */
    public double isshinRewardStance() {
        return isshinRewardStance;
    }
}
