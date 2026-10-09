package org.alpha.sekiroBedwar.stance;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 架势系统配置：封装 <code>duel.yml</code> 的 <code>stance:</code> 段。
 *
 * <p>最大架势公式（全部可配置，默认值为示例系数）：
 * <pre>
 *   W    = Σ coeffᵢ · ln(1 + countᵢ)      // count = 背包中该资源数量（只读）
 *   Smax = base + multiplier · W
 * </pre>
 * 默认即 {@code W = ln(1+铁) + 3ln(1+金) + 10ln(1+钻石) + 20ln(1+绿宝石)}。
 * 资源类型名 → 系数映射写入 <code>stance.max-stance.resources</code>，
 * 后续增加或修改资源类型只需编辑该映射。</p>
 *
 * <p>配置变更后调用 {@link #reload()} 生效。</p>
 */
public final class StanceConfig {
    private final SekiroBedwar plugin;

    private double maxStanceBase;
    private double maxStanceMultiplier;
    private Map<Material, Double> resourceCoefficients;

    private double breakCriticalRatio;
    private double executionSeconds;
    private double staggerDurationSeconds;
    private boolean disableBlocking;
    /** 打进处决窗口的一方是否获得奖励（{@code stance.break.breaker-reward.*}，默认开启）。 */
    private boolean breakerRewardEnabled;
    /** 打进处决窗口者的回血量（HP；默认 5.0）。 */
    private double breakerRewardHealHp;
    /** 打进处决窗口者的架势是否回满（默认 true）。 */
    private boolean breakerRewardRefillStance;
    private int guardCheckTicks;
    private int settleCheckTicks;
    private double lowHealthThreshold;
    private boolean breakOnBlockedHit;
    private boolean breakOnUnblockedHit;
    private boolean breakOnParriedAttack;
    private boolean blockNaturalRegen;
    /** 断自然回血的判定口径（{@code stance.health-regen.block-when}）。 */
    private NaturalRegenScope blockNaturalRegenWhen;
    /** {@code GAMES} 口径下命中即断的对局名 / 世界名（{@code stance.health-regen.games}）。 */
    private List<String> blockNaturalRegenGames;
    /** 决斗刚结束后继续断自然回血的保护秒数（{@code stance.health-regen.post-duel-grace-seconds}）。 */
    private double postDuelRegenGraceSeconds;
    /** 回血取证日志（{@code stance.health-regen.log}，默认 false）。 */
    private boolean blockNaturalRegenLog;

    // 处决窗口破甲（崩条后被处决者护甲无效：命中按纯血伤结算）
    private boolean executionArmorEnabled;
    private boolean executionArmorStripEnchant;
    private boolean executionArmorLog;

    private boolean naturalRecoveryEnabled;
    private double naturalRecoveryRate;
    private double naturalRecoveryIdleSeconds;
    private boolean dotMaintainsActive;
    private final List<PotionEffectType> dotEffects = new ArrayList<>();

    private int bossbarRefreshTicks;
    private BarColor bossbarColor;
    private BarStyle bossbarStyle;
    private String bossbarNameFormat;

    private boolean xpBarEnabled;
    private int xpBarRefreshTicks;

    public StanceConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 stance 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.maxStanceBase = Math.max(0.0, yaml.getDouble("stance.max-stance.base", 100.0));
        this.maxStanceMultiplier = Math.max(0.0, yaml.getDouble("stance.max-stance.multiplier", 1.0));
        this.resourceCoefficients = parseResourceCoefficients(yaml);

        this.breakCriticalRatio = Math.min(1.0, Math.max(0.0, yaml.getDouble("stance.break.critical-ratio", 1.0)));
        this.executionSeconds = Math.max(0.0, yaml.getDouble("stance.break.execution-seconds", 25.0));
        this.staggerDurationSeconds = Math.max(0.0, yaml.getDouble("stance.break.stagger.duration-seconds", 5.0));
        this.disableBlocking = yaml.getBoolean("stance.break.stagger.disable-blocking", true);
        // ★ 打进处决窗口的一方获得奖励（架势回满 + 回血）：把对手打崩是进攻成果，
        //   但处决窗口本身是"对方的逃离窗口"，没有正反馈的话收益不对称。
        this.breakerRewardEnabled = yaml.getBoolean("stance.break.breaker-reward.enabled", true);
        this.breakerRewardHealHp = Math.max(0.0, yaml.getDouble("stance.break.breaker-reward.heal-hp", 5.0));
        this.breakerRewardRefillStance = yaml.getBoolean("stance.break.breaker-reward.refill-stance", true);
        this.guardCheckTicks = Math.max(1, yaml.getInt("stance.break.guard-check-ticks", 5));
        this.settleCheckTicks = Math.max(1, yaml.getInt("stance.break.settle-check-ticks", 5));
        this.lowHealthThreshold = Math.max(0.0, yaml.getDouble("stance.break.low-health-threshold", 2.0));
        this.breakOnBlockedHit = yaml.getBoolean("stance.break.trigger.break-on-blocked-hit", true);
        this.breakOnUnblockedHit = yaml.getBoolean("stance.break.trigger.break-on-unblocked-hit", true);
        this.breakOnParriedAttack = yaml.getBoolean("stance.break.trigger.break-on-parried-attack", true);
        this.blockNaturalRegen = yaml.getBoolean("stance.health-regen.block-natural", true);
        // 口径（block-when）：何时断自然回血。
        //   stance-not-full = 在决斗中且架势未满才断（架势回满后可自然回血）★默认
        //   in-duel         = 只要在决斗中就一律断（不看架势满不满）
        //   games           = 只要身处 games 列出的对局/世界就一律断（不看决斗、不看架势）
        //
        // ★ 为什么还需要「残留读数」机制（见 StanceBreakManager.shouldBlockNaturalRegen）：
        //   决斗结束时 endDuel 会把架势状态整个清掉（hasStance=false、cur=max=0），
        //   于是 `cur < max` 立刻不成立 → 自然回血被放行，表现就是"架势没满却还能回血"。
        //   所以除了实时读数，还要沿用决斗内最后一次读数（post-duel-grace-seconds 秒内）。
        String scopeRaw = yaml.getString("stance.health-regen.block-when");
        this.blockNaturalRegenWhen = parseRegenScope(scopeRaw);
        this.blockNaturalRegenGames = yaml.getStringList("stance.health-regen.games");
        this.postDuelRegenGraceSeconds = Math.max(0.0,
                yaml.getDouble("stance.health-regen.post-duel-grace-seconds", 5.0));
        this.blockNaturalRegenLog = yaml.getBoolean("stance.health-regen.log", false);

        this.executionArmorEnabled = yaml.getBoolean("stance.break.execution-armor.enabled", true);
        this.executionArmorStripEnchant = yaml.getBoolean("stance.break.execution-armor.strip-enchant", true);
        this.executionArmorLog = yaml.getBoolean("stance.break.execution-armor.log", false);

        this.naturalRecoveryEnabled = yaml.getBoolean("stance.natural-recovery.enabled", true);
        this.naturalRecoveryRate = Math.max(0.0, yaml.getDouble("stance.natural-recovery.rate", 0.16));
        this.naturalRecoveryIdleSeconds = Math.max(0.0, yaml.getDouble("stance.natural-recovery.idle-seconds", 5.0));
        this.dotMaintainsActive = yaml.getBoolean("stance.natural-recovery.dot-maintains-active", true);
        this.dotEffects.clear();
        List<String> effectNames = yaml.getStringList("stance.natural-recovery.dot-effects");
        if (effectNames.isEmpty()) {
            effectNames = List.of("POISON", "WITHER");
        }
        for (String name : effectNames) {
            PotionEffectType type = parsePotionEffect(name);
            if (type != null) {
                this.dotEffects.add(type);
            } else {
                plugin.getLogger().warning("忽略未知的负面效果名: " + name);
            }
        }

        this.bossbarRefreshTicks = Math.max(1, yaml.getInt("stance.bossbar.refresh-ticks", 5));
        this.bossbarColor = parseEnum(BarColor.class, yaml.getString("stance.bossbar.color"), BarColor.RED);
        this.bossbarStyle = parseEnum(BarStyle.class, yaml.getString("stance.bossbar.style"), BarStyle.SOLID);
        this.bossbarNameFormat = yaml.getString("stance.bossbar.name-format", "&c%s 的架势");

        this.xpBarEnabled = yaml.getBoolean("stance.xp-bar.enabled", true);
        this.xpBarRefreshTicks = Math.max(1, yaml.getInt("stance.xp-bar.refresh-ticks", 2));
    }

    private Map<Material, Double> parseResourceCoefficients(YamlConfiguration yaml) {
        Map<Material, Double> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("stance.max-stance.resources");
        if (section == null) {
            return result;
        }
        for (String name : section.getKeys(false)) {
            try {
                Material material = Material.valueOf(name.toUpperCase());
                double coeff = section.getDouble(name);
                result.put(material, Math.max(0.0, coeff));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("跳过无效的架势资源类型: " + name);
            }
        }
        return result;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /** 最大架势基础值 S0。 */
    public double maxStanceBase() {
        return maxStanceBase;
    }

    /** 最大架势转换倍率 k。 */
    public double maxStanceMultiplier() {
        return maxStanceMultiplier;
    }

    /**
     * 资源类型 → 公式系数映射（保持 yml 顺序：铁→金→钻石→绿宝石，供第三方介入折算的顺序填充算法按序遍历）。
     * 支持增改资源类型。
     */
    public Map<Material, Double> resourceCoefficients() {
        return new LinkedHashMap<>(resourceCoefficients);
    }

    /**
     * 临界判断比例：<b>已消耗</b>架势比例（1 - current/max）≥ 该值视为“临界”（即
     * current/max ≤ 1 - 该值）。默认 1.0 = 架势条空（current≈0）。到达临界不自动崩条，
     * 需满足 StanceBreakManager 的触发条件（未弹反命中 / 被弹反）才崩。
     */
    public double breakCriticalRatio() {
        return breakCriticalRatio;
    }

    /** 崩条后结算 / 逃离窗口时长（秒）：期间 {@code isBroken()} 为真、可逃离决斗场地、到期半额结算。 */
    public double executionSeconds() {
        return executionSeconds;
    }

    /** 崩条后【受击状态】的时长（秒，与 execution-seconds 分离，默认 5s）。 */
    public double staggerDurationSeconds() {
        return staggerDurationSeconds;
    }

    /** 受击状态期间是否无法正常格挡（默认 true；false 时退化为纯状态标记，不做盾牌冷却强制）。 */
    public boolean disableBlocking() {
        return disableBlocking;
    }
    /** 打进处决窗口的一方是否获得奖励（默认开启）。 */
    public boolean breakerRewardEnabled() {
        return breakerRewardEnabled;
    }

    /** 打进处决窗口者的回血量（HP；默认 5.0，0 = 不回血）。 */
    public double breakerRewardHealHp() {
        return breakerRewardHealHp;
    }

    /** 打进处决窗口者的架势是否回满（默认 true）。 */
    public boolean breakerRewardRefillStance() {
        return breakerRewardRefillStance;
    }

    /** 受击状态强制的检测间隔（tick）。 */
    public int guardCheckTicks() {
        return guardCheckTicks;
    }

    /** 结算模块周期检测处决窗口超时的间隔（tick）。 */
    public int settleCheckTicks() {
        return settleCheckTicks;
    }

    /** 低血量阈值：决斗中受击后血量 ≤ 该值（未死）→ 架势拉到临界（不崩条）。 */
    public double lowHealthThreshold() {
        return lowHealthThreshold;
    }

    /** 临界中被对方普通格挡命中（未完美弹反）是否触发崩条。 */
    public boolean breakOnBlockedHit() {
        return breakOnBlockedHit;
    }

    /** 临界中被对方无格挡命中（未完美弹反）是否触发崩条。 */
    public boolean breakOnUnblockedHit() {
        return breakOnUnblockedHit;
    }

    /** 临界中自己的攻击被对方完美弹反（近战）是否触发崩条。 */
    public boolean breakOnParriedAttack() {
        return breakOnParriedAttack;
    }

    /** 是否在架势非满（current < max）时阻断自然回血（仅 SATIATED）。 */
    public boolean blockNaturalRegen() {
        return blockNaturalRegen;
    }

    /** 断自然回血的判定口径（默认 {@code STANCE_NOT_FULL}）。 */
    public NaturalRegenScope blockNaturalRegenWhen() {
        return blockNaturalRegenWhen;
    }

    /** {@code GAMES} 口径下命中即断的对局名 / 世界名列表（大小写不敏感）。 */
    public List<String> blockNaturalRegenGames() {
        return blockNaturalRegenGames;
    }

    /** 决斗刚结束后继续断自然回血的保护秒数（默认 5；0 = 关闭）。 */
    public double postDuelRegenGraceSeconds() {
        return postDuelRegenGraceSeconds;
    }

    /** 回血取证日志开关（默认 false）。 */
    public boolean blockNaturalRegenLog() {
        return blockNaturalRegenLog;
    }

    /**
     * 自然回血阻断的判定口径。
     *
     * <ul>
     *   <li>{@link #STANCE_NOT_FULL}（默认）：在决斗中且架势未满（current &lt; max）才断 ——
     *       架势回满后自然回血恢复（"逼迫双方去攻击对方"）；</li>
     *   <li>{@link #IN_DUEL}：只要在决斗中就一律断（不看架势满不满）；</li>
     *   <li>{@link #GAMES}：只要身处 {@code stance.health-regen.games} 列出的对局/世界，
     *       一律断（不看是否在决斗、也不看架势）。</li>
     * </ul>
     */
    public enum NaturalRegenScope {
        STANCE_NOT_FULL,
        IN_DUEL,
        GAMES
    }

    private static NaturalRegenScope parseRegenScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return NaturalRegenScope.STANCE_NOT_FULL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "in-duel", "duel", "决斗内" -> NaturalRegenScope.IN_DUEL;
            case "games", "game", "arena", "arenas", "指定对局" -> NaturalRegenScope.GAMES;
            default -> NaturalRegenScope.STANCE_NOT_FULL;
        };
    }

    /** 处决窗口破甲：崩条（处决窗口开启）期间被处决者的护甲无效（默认 true）。 */
    public boolean executionArmorEnabled() {
        return executionArmorEnabled;
    }

    /** 处决破甲是否连同保护类附魔的减伤一起归零（默认 true = 纯血伤）。 */
    public boolean executionArmorStripEnchant() {
        return executionArmorStripEnchant;
    }

    /** 处决破甲是否打印诊断日志（控制台；默认 false）。 */
    public boolean executionArmorLog() {
        return executionArmorLog;
    }

    /** 是否启用架势自然恢复。 */
    public boolean naturalRecoveryEnabled() {
        return naturalRecoveryEnabled;
    }

    /** 自然恢复速率 r（每秒比例系数，见 {@link StanceRecoveryTask}）。 */
    public double naturalRecoveryRate() {
        return naturalRecoveryRate;
    }

    /** 自然恢复触发条件：距上次架势变化超过该秒数才开始恢复。 */
    public double naturalRecoveryIdleSeconds() {
        return naturalRecoveryIdleSeconds;
    }

    /** 身上有负面效果（中毒 / 凋零 / 着火等）时是否持续维持“战斗活跃”态（暂停自然恢复）。 */
    public boolean dotMaintainsActive() {
        return dotMaintainsActive;
    }

    /** 计入维持判定的负面效果集合（着火另按 fireTicks 判）。 */
    public List<PotionEffectType> dotEffects() {
        return List.copyOf(dotEffects);
    }

    /** 负面效果名 → 类型（1.21 现代名优先，兼容旧名；未知返回 null）。 */
    private static PotionEffectType parsePotionEffect(String name) {
        if (name == null) {
            return null;
        }
        switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "POISON":
                return PotionEffectType.POISON;
            case "WITHER":
            case "WITHERING":
                return PotionEffectType.WITHER;
            case "SLOW":
            case "SLOWNESS":
                return PotionEffectType.SLOWNESS;
            case "HUNGER":
                return PotionEffectType.HUNGER;
            case "BLINDNESS":
                return PotionEffectType.BLINDNESS;
            case "NAUSEA":
            case "CONFUSION":
                return PotionEffectType.NAUSEA;
            default:
                return null;
        }
    }

    /** BossBar 刷新间隔（tick）。 */
    public int bossbarRefreshTicks() {
        return bossbarRefreshTicks;
    }

    /** BossBar 颜色。 */
    public BarColor bossbarColor() {
        return bossbarColor;
    }

    /** BossBar 样式。 */
    public BarStyle bossbarStyle() {
        return bossbarStyle;
    }

    /** BossBar 标题格式（%s 替换为对方玩家名）。 */
    public String bossbarNameFormat() {
        return bossbarNameFormat;
    }

    /** 是否用经验条显示自己的架势（false 时完全不占用玩家经验条）。 */
    public boolean xpBarEnabled() {
        return xpBarEnabled;
    }

    /** 经验条刷新间隔（tick；2 = 0.1 秒）。 */
    public int xpBarRefreshTicks() {
        return xpBarRefreshTicks;
    }
}
