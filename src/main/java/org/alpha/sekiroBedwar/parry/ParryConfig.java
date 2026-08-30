package org.alpha.sekiroBedwar.parry;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;

/**
 * 完美弹反系统配置：封装 <code>duel.yml</code> 的 <code>parry:</code> 段。
 *
 * <p>完美弹反窗口（举盾后窗口，无攻击前/后之分、无 RTT/2 回溯，网络补偿只放大窗口）：
 * <pre>
 *   smoothRTT       = EWMA(getPing, α)                       // 玩家 RTT 平滑值
 *   comp            = min(comp-max-ms, max(0, (smoothRTT − comp-floor-ms) / comp-divisor))
 *   effectiveWindow = base-window-ms + comp                  // 举盾后窗口（默认 170 + 高延迟补偿）
 *   完美弹反 ⟺ 举盾时刻 ≤ 命中时刻 ≤ 举盾时刻 + effectiveWindow
 * </pre>
 * 举盾时刻 = 格挡轮询（{@code poll-ticks}）记录到的格挡开始；命中落在「举盾后 effectiveWindow 内」
 * （默认 170ms，高延迟最多再 +40ms 补偿）即完美弹反。同 tick 刚举盾即命中由 {@code eagerStart}
 * 兜底（0 延时）。一次按下只弹反一击，同一按住中的后续命中按普通格挡处理。</p>
 *
 * <p>完美弹反架势换算（消耗制：current 从 max 扣到 0 崩条，见 {@code stance/} 包，
 * Dbase = 攻击方武器面板伤害，见 {@code combat/} 包）：
 * <ul>
 *   <li>完美弹反：攻击方架势 −= <b>Dbase</b> × {@code parry-attacker-multiplier}（默认 3.0）；</li>
 *   <li>受击方（弹反者）自身 −= <b>固定值</b> {@code parry-victim-cost}（用户设为 0 = 不扣自身架势），
 *       且无论是否临界都进入<b>维持态</b>（{@code markActive}）：刷新 idle 计时、
 *       防止架势自然恢复——成功弹反把当前架势"锁"在当前值，不能靠弹反拖时间回架势。</li>
 * </ul>
 * <b>普通格挡 / 无格挡的乘数已迁移到 <code>block:</code> 段</b>（由 {@code block/} 包读取），
 * 本类不再持有，避免两套模块重复配置。</p>
 *
 * <p><b>连续弹反计数器窗口（攻势无效）</b>（{@code parry.seal} 段）：同一决斗中，玩家攻击连续被
 * 对方完美弹反 {@code required-parries} 次后，接下来 {@code duration-seconds} 秒内该玩家的所有
 * 攻击（近战与弓箭）对对手无伤害、无作用（攻击一律被取消，不进入普通格挡 / 弹反换算）。
 * “连续”判定：相邻两次被完美弹反间隔 ≤ {@code max-interval-seconds}（默认 0.7s），且中间不能有
 * 一次普通格挡 / 成功命中（近战或弓箭，均打断计数）；被封印期间命中一律无效。不发送文字提示（保持战斗沉浸）。</p>
 *
 * <p>配置变更后调用 {@link #reload()} 生效。</p>
 */
public final class ParryConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private long baseWindowMs;
    private int pollTicks;

    private double alpha;
    private double compFloorMs;
    private double compMaxMs;
    private double compDivisor;

    private double parryAttackerMultiplier;
    private double parryVictimCost;

    private boolean parrySoundEnabled;
    private Sound parrySound;
    private float parrySoundVolume;
    private float parrySoundPitch;

    private boolean sealEnabled;
    private int sealRequiredParries;
    private double sealDurationSeconds;
    private double sealMaxIntervalSeconds;

    public ParryConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 parry 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("parry.enabled", true);
        this.baseWindowMs = Math.max(1L, yaml.getLong("parry.base-window-ms", 170L));
        this.pollTicks = Math.max(1, yaml.getInt("parry.poll-ticks", 1));

        this.alpha = Math.min(1.0, Math.max(0.01, yaml.getDouble("parry.latency.alpha", 0.2)));
        this.compFloorMs = Math.max(0.0, yaml.getDouble("parry.latency.comp-floor-ms", 50.0));
        this.compMaxMs = Math.max(0.0, yaml.getDouble("parry.latency.comp-max-ms", 40.0));
        this.compDivisor = Math.max(1.0, yaml.getDouble("parry.latency.comp-divisor", 2.0));

        this.parryAttackerMultiplier = Math.max(0.0, yaml.getDouble("parry.stance.parry-attacker-multiplier", 3.0));
        this.parryVictimCost = Math.max(0.0, yaml.getDouble("parry.stance.parry-victim-cost", 5.0));

        this.parrySoundEnabled = yaml.getBoolean("parry.feedback.sound.enabled", true);
        this.parrySound = resolveSound(yaml.getString("parry.feedback.sound.name"));
        this.parrySoundVolume = (float) Math.max(0.0, Math.min(2.0, yaml.getDouble("parry.feedback.sound.volume", 1.0)));
        this.parrySoundPitch = (float) Math.max(0.0, Math.min(2.0, yaml.getDouble("parry.feedback.sound.pitch", 1.0)));

        this.sealEnabled = yaml.getBoolean("parry.seal.enabled", true);
        this.sealRequiredParries = Math.max(1, yaml.getInt("parry.seal.required-parries", 3));
        this.sealDurationSeconds = Math.max(0.0, yaml.getDouble("parry.seal.duration-seconds", 5.0));
        this.sealMaxIntervalSeconds = Math.max(0.0, yaml.getDouble("parry.seal.max-interval-seconds", 0.7));
    }

    /** 是否启用弹反 / 伤害→架势 联动（false 时完全原版战斗）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 完美弹反基础窗口（毫秒）：命中落在「举盾后 base-window-ms + 补偿」内即完美弹反（默认 170）。 */
    public long baseWindowMs() {
        return baseWindowMs;
    }

    /** isBlocking() 轮询间隔（tick；1 = 50ms，决定格挡开始时刻的记录精度）。 */
    public int pollTicks() {
        return pollTicks;
    }

    /** EWMA 平滑系数 α（0.01 ~ 1.0）。 */
    public double alpha() {
        return alpha;
    }

    /** 补偿下限：smoothRTT ≤ 该值 → 补偿 0。 */
    public double compFloorMs() {
        return compFloorMs;
    }

    /** 补偿上限（毫秒）：高延迟补偿封顶，窗口不无限扩大。 */
    public double compMaxMs() {
        return compMaxMs;
    }

    /** 补偿除数（(smoothRTT − floor) / divisor）。 */
    public double compDivisor() {
        return compDivisor;
    }

    /** 完美弹反：攻击方架势 −= Dbase（武器面板伤害）× 该值。 */
    public double parryAttackerMultiplier() {
        return parryAttackerMultiplier;
    }

    /** 完美弹反：受击方（弹反者）架势 −= 该<b>固定值</b>（不是乘数；默认 5.0）。 */
    public double parryVictimCost() {
        return parryVictimCost;
    }

    /** 完美弹反成功时是否向双方播放反馈音效。 */
    public boolean parrySoundEnabled() {
        return parrySoundEnabled;
    }

    /** 反馈音效（默认 BLOCK_ANVIL_PLACE 铁砧放置）。 */
    public Sound parrySound() {
        return parrySound;
    }

    /** 反馈音效音量（0-2）。 */
    public float parrySoundVolume() {
        return parrySoundVolume;
    }

    /** 反馈音效音调（0-2；1.0 = 原调）。 */
    public float parrySoundPitch() {
        return parrySoundPitch;
    }

    /** 是否启用连续弹反计数器窗口（攻势无效）。 */
    public boolean sealEnabled() {
        return sealEnabled;
    }

    /** 连续被对方完美弹反多少次后触发封印（>=1）。 */
    public int sealRequiredParries() {
        return sealRequiredParries;
    }

    /** 封印持续时长（秒，可为小数；攻势无效窗口）。 */
    public double sealDurationSeconds() {
        return sealDurationSeconds;
    }

    /** 连续判定的时间窗（秒，可为小数；默认 0.7）：相邻两次被完美弹反间隔超过该值即断开连续。 */
    public double sealMaxIntervalSeconds() {
        return sealMaxIntervalSeconds;
    }

    /**
     * 按名解析音效：先按 OldEnum 枚举名（如 {@code BLOCK_ANVIL_PLACE}）遍历
     * {@link Registry#SOUNDS} 匹配 {@code name()}，再按资源键（如 {@code block.anvil.place}）
     * 兜底；都失败回退 {@link Sound#BLOCK_ANVIL_PLACE}。
     * <p>1.21.4 起 {@link Sound} 由枚举改为接口（{@code OldEnum}），不能用 {@code Enum.valueOf}，
     * 统一走 {@link Registry#SOUNDS} 解析。</p>
     */
    private static Sound resolveSound(String raw) {
        if (raw != null && !raw.trim().isEmpty()) {
            String want = raw.trim().toUpperCase(Locale.ROOT);
            for (Sound sound : Registry.SOUNDS) {
                if (sound.name().equals(want)) {
                    return sound;
                }
            }
            try {
                NamespacedKey key = raw.contains(":")
                        ? NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT))
                        : NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT));
                Sound byKey = key == null ? null : Registry.SOUNDS.get(key);
                if (byKey != null) {
                    return byKey;
                }
            } catch (IllegalArgumentException ignored) {
                // 非法键名忽略
            }
        }
        return Sound.BLOCK_ANVIL_PLACE;
    }
}
