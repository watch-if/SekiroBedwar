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
 * <p><b>飞渡浮舟</b>（{@code mystery.fei-du-fu-zhou}）：连击间隔序列（tick，
 * 可为小数，判定按 毫秒 = tick×50 ± 容差×50）、容差、第 6 击架势加成与完成奖励。
 * 列表缺省时代码内置默认序列兜底（服务器旧 duel.yml 缺段 = 模块静默失效的教训）。</p>
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
}
