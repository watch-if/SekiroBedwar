package org.alpha.sekiroBedwar.block;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 普通格挡 / 受击架势配置：封装 <code>duel.yml</code> 的 <code>block:</code> 段。
 *
 * <p>消耗制架势（current 从 max 扣到 0 崩条，见 {@code stance/} 包）。
 * 用户确认的最终公式（Dbase = 攻击方武器面板伤害，Dactual = 实机血量伤害，见 {@code combat/} 包）：
 * <ul>
 *   <li><b>无格挡命中</b>（ΔS肉）：受击方架势 −= <b>Dactual</b>（实机血量伤害，{@code getFinalDamage()}）
 *       × {@code hit-multiplier}（默认 2.5）；</li>
 *   <li><b>普通格挡</b>（ΔS格挡，盾牌格挡、未命中完美弹反窗口）：不完全免架势——
 *       防守方架势 −= <b>Dbase</b>（武器面板伤害）× {@code defender-multiplier}（默认 1.5）；
 *       <b>攻击方不再扣架势</b>（用户确认移除 attacker-multiplier）。</li>
 * </ul></p>
 *
 * <p>与完美弹反分离：弹反专用乘数 / 弹反者固定值仍在 <code>parry:</code> 段，由 {@code parry/}
 * 包读取；本段只负责普通格挡与无格挡命中。</p>
 *
 * <p><b>破盾</b>（{@code block.shield-break.*}）：攻击方主手为配置的破盾武器（默认六种斧）且
 * 命中普通格挡（非完美弹反）时——防守方架势改为按 {@code stance-multiplier} 更高倍率扣减，
 * 并短暂禁用其格挡（无法正常格挡窗口）。完美弹反的命中在 HIGH 优先级已被取消，本模块不可见。</p>
 */
public final class BlockConfig {
    private final SekiroBedwar plugin;

    private boolean enabled;
    private double hitMultiplier;
    private double defenderMultiplier;

    private boolean shieldBreakEnabled;
    private Set<Material> shieldBreakMaterials;
    private double shieldBreakStanceMultiplier;
    private double shieldBreakDisableBlockingSeconds;

    public BlockConfig(SekiroBedwar plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 duel.yml 的 block 段。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "duel.yml");
        if (!file.exists()) {
            plugin.saveResource("duel.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        this.enabled = yaml.getBoolean("block.enabled", true);
        this.hitMultiplier = Math.max(0.0, yaml.getDouble("block.stance.hit-multiplier", 2.5));
        this.defenderMultiplier = Math.max(0.0, yaml.getDouble("block.stance.defender-multiplier", 1.5));

        this.shieldBreakEnabled = yaml.getBoolean("block.shield-break.enabled", true);
        this.shieldBreakMaterials = parseShieldBreakMaterials(yaml);
        this.shieldBreakStanceMultiplier = Math.max(0.0, yaml.getDouble("block.shield-break.stance-multiplier", 3.0));
        this.shieldBreakDisableBlockingSeconds = Math.max(0.0, yaml.getDouble("block.shield-break.disable-blocking-seconds", 3.0));
    }

    private Set<Material> parseShieldBreakMaterials(YamlConfiguration yaml) {
        List<String> names = yaml.getStringList("block.shield-break.materials");
        Set<Material> result = new HashSet<>();
        for (String name : names) {
            try {
                result.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("跳过无效的破盾武器类型: " + name);
            }
        }
        if (result.isEmpty()) {
            // 配置为空 / 全无效时兜底默认六种斧
            for (Material axe : new Material[]{
                    Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE,
                    Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE}) {
                result.add(axe);
            }
        }
        return result;
    }

    /** 是否启用普通格挡 / 受击架势变化（false 时该模块零介入，完全原版战斗）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 无格挡命中（ΔS肉）：受击方架势 −= Dactual（实机血量伤害）× 该值。 */
    public double hitMultiplier() {
        return hitMultiplier;
    }

    /** 普通格挡（ΔS格挡）：防守方架势 −= Dbase（武器面板伤害）× 该值（不完全免架势）。 */
    public double defenderMultiplier() {
        return defenderMultiplier;
    }

    /** 是否启用破盾（斧击普通格挡 → 更高架势扣减 + 短暂无法格挡）。 */
    public boolean shieldBreakEnabled() {
        return shieldBreakEnabled;
    }

    /** 触发破盾的武器类型集合（拷贝，默认六种斧）。 */
    public Set<Material> shieldBreakMaterials() {
        return new HashSet<>(shieldBreakMaterials);
    }

    /** 破盾时防守方架势扣除倍率（替换普通格挡 {@link #defenderMultiplier()}）。 */
    public double shieldBreakStanceMultiplier() {
        return shieldBreakStanceMultiplier;
    }

    /** 破盾后短暂无法格挡时长（秒）。 */
    public double shieldBreakDisableBlockingSeconds() {
        return shieldBreakDisableBlockingSeconds;
    }
}
