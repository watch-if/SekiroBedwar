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
 * {@code iframe.duel-enabled}（默认 false = 决斗期间无）。</p>
 *
 * <p><b>秘传武技</b>：第一式·飞渡浮舟（{@code mystery.fei-du-fu-zhou}）连击间隔序列
 * （tick，可为小数，判定按 毫秒 = tick×50 ± 容差×50）、容差、第 6 击架势加成与完成奖励；
 * 第二式·苇名十字斩（{@code mystery.yamedo-cross-slash}）二连间隔 / 容差 / 击退级别 /
 * 架势扣减与恢复。列表缺省时代码内置默认兜底（服务器旧 duel.yml 缺段 =
 * 模块静默失效的教训）。</p>
 */
public final class MysteryConfig {

    /** 内置默认连击间隔（tick）：1→2 至 6→7，共 6 个间隔、7 次攻击。 */
    static final double[] DEFAULT_FDFZ_INTERVALS = {7.3, 10.0, 5.7, 5.3, 5.7, 16.0};

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
        this.fdfzIntervals = parseIntervals(yaml.getDoubleList("mystery.fei-du-fu-zhou.intervals-ticks"));
        this.fdfzToleranceTicks = Math.max(0.0, yaml.getDouble("mystery.fei-du-fu-zhou.tolerance-ticks", 0.2));
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
    }

    /** 连击间隔序列：列表缺失 / 为空 / 含非正值时回退内置默认（列表型配置必须有代码默认）。 */
    private double[] parseIntervals(List<Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_FDFZ_INTERVALS.clone();
        }
        double[] out = new double[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Double v = raw.get(i);
            if (v == null || v <= 0) {
                plugin.getLogger().warning("飞渡浮舟间隔序列含非正值，回退默认序列");
                return DEFAULT_FDFZ_INTERVALS.clone();
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
}
