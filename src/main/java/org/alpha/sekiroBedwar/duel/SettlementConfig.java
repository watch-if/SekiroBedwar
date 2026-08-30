package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 决斗资源结算配置：封装 <code>duel.yml</code> 的 <code>settlement:</code> 段。
 *
 * <p>四种结算情形，各配独立比例（值钳制 [0,1]，0 = 不转移）：</p>
 * <ul>
 *   <li><b>崩条死亡 / 处决</b>（{@code break-kill-ratio}，默认 0.5）：处决窗口内被杀 或 处决窗口到期；</li>
 *   <li><b>普通死亡</b>（{@code normal-kill-ratio}，默认 1.0）：未崩条被对方击杀；</li>
 *   <li><b>虚空死亡</b>（{@code void-kill-ratio}，默认 1.0）：被击落虚空（= 决斗死亡）；</li>
 *   <li><b>第三方介入</b>：不转移，双方资源回滚到决斗开始状态（见 {@link DuelResourceSnapshot}）。</li>
 * </ul>
 */
public final class SettlementConfig {
    private final SekiroBedwar plugin;

    private double breakKillRatio;
    private double normalKillRatio;
    private double voidKillRatio;

    public SettlementConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 settlement 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.breakKillRatio = clampRatio(yaml.getDouble("settlement.break-kill-ratio", 0.5));
        this.normalKillRatio = clampRatio(yaml.getDouble("settlement.normal-kill-ratio", 1.0));
        this.voidKillRatio = clampRatio(yaml.getDouble("settlement.void-kill-ratio", 1.0));
    }

    private static double clampRatio(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    /** 崩条死亡 / 处决（窗口内被杀 或 处决超时）：败者资源按该比例转移给胜者（默认 0.5）。 */
    public double breakKillRatio() {
        return breakKillRatio;
    }

    /** 普通死亡（未崩条被击杀）：转移比例（默认 1.0 全额）。 */
    public double normalKillRatio() {
        return normalKillRatio;
    }

    /** 虚空死亡（被击落虚空 = 决斗死亡）：转移比例（默认 1.0，视同普通击杀）。 */
    public double voidKillRatio() {
        return voidKillRatio;
    }
}
