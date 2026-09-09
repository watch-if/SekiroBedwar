package org.alpha.sekiroBedwar.api;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秘传武技的稳定公共标识（不可变值对象；核心四式为内置常量，外部插件可注册新武技）。
 *
 * <p><b>核心秘传</b>：{@link #FEIDU_FUZU}、{@link #YAMEDO_CROSS_SLASH}、{@link #LONG_SHAN}、
 * {@link #ISSHIN_SEVEN_STRIKE}（{@link #core()} 为 true，键保留不可占用）。</p>
 *
 * <p><b>外部秘传</b>：经 {@link SekiroBedwarApi#registerTechnique(Plugin, String, String)}
 * 注册后，外部实现监听自己的判定逻辑、广播同一组
 * {@link org.alpha.sekiroBedwar.api.events.SecretTechniqueStartEvent} 等事件（携带注册的 id）
 * ——统计 / 排位 / 录像插件按 {@link #key()} 消费并对未知值保持兼容，<b>新增秘传
 * 不需要修改 API 架构</b>。</p>
 */
public final class TechniqueId {

    /** 第一式·飞渡浮舟：七击节奏连（间隔序列 ±容差），第 6 击有效命中额外架势伤。 */
    public static final TechniqueId FEIDU_FUZU =
            registerCore("fei-du-fu-zhou", "飞渡浮舟");
    /** 第二式·苇名十字斩：空手换刀起手二连，第二击有效命中击退 + 架势交换。 */
    public static final TechniqueId YAMEDO_CROSS_SLASH =
            registerCore("yamedo-cross-slash", "苇名十字斩");
    /** 第三式·龙闪：空手蓄力后左键释放双段音波柱。 */
    public static final TechniqueId LONG_SHAN =
            registerCore("long-shan", "龙闪");
    /** 第四式·一心七连：七段近战连击 + 危攻击终结，逐段叠加架势增伤。 */
    public static final TechniqueId ISSHIN_SEVEN_STRIKE =
            registerCore("isshin-seven-strike", "一心七连");

    /** key（小写-连字符）→ 注册实例（核心 + 外部，注册序保留）。 */
    private static final Map<String, TechniqueId> REGISTRY = new ConcurrentHashMap<>();

    private final String key;
    private final String displayName;
    private final boolean core;
    private volatile String ownerPlugin; // 外部注册：归属插件名；核心为 "SekiroBedwar"

    private TechniqueId(String key, String displayName, boolean core) {
        this.key = key;
        this.displayName = displayName;
        this.core = core;
        this.ownerPlugin = core ? "SekiroBedwar" : null;
    }

    private static TechniqueId registerCore(String key, String displayName) {
        TechniqueId id = new TechniqueId(key, displayName, true);
        REGISTRY.put(key, id);
        return id;
    }

    /**
     * 外部插件注册秘传（一般经 {@link SekiroBedwarApi#registerTechnique} 调用）。
     *
     * @throws IllegalArgumentException 键非法 / 与核心或其他插件已注册的键冲突
     * @return 注册（或已属于本插件时返回既有）的 id
     */
    static TechniqueId registerExternal(Plugin owner, String key, String displayName) {
        String k = normalize(key);
        if (k.isEmpty() || !k.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("秘传键需为小写字母/数字/-/_（2-64 字符）: " + key);
        }
        TechniqueId existing = REGISTRY.get(k);
        if (existing != null) {
            if (existing.core || !owner.getName().equalsIgnoreCase(existing.ownerPlugin)) {
                throw new IllegalArgumentException("秘传键已被占用: " + k);
            }
            return existing; // 同插件重复注册幂等返回
        }
        TechniqueId id = new TechniqueId(k, displayName == null ? k : displayName, false);
        id.ownerPlugin = owner.getName();
        REGISTRY.put(k, id);
        return id;
    }

    /** 按键查已注册 id（含核心与外部）；未注册返回 null。 */
    public static TechniqueId of(String key) {
        return REGISTRY.get(normalize(key));
    }

    /** 全部已注册秘传（核心 + 外部，快照）。 */
    public static List<TechniqueId> values() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
    }

    /** 外部插件 disable 时注销自己的键（核心键不可注销；由 {@link SekiroBedwarApi} 门面执行）。 */
    static void unregisterExternal(Plugin owner, TechniqueId id) {
        if (id != null && !id.core && owner != null
                && owner.getName().equalsIgnoreCase(id.ownerPlugin)) {
            REGISTRY.remove(id.key, id);
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    /** duel.yml / 注册用的稳定键（如 {@code fei-du-fu-zhou}）。 */
    public String key() {
        return key;
    }

    /** duel.yml 段键别名（{@link #key()} 的语义名）。 */
    public String configKey() {
        return key;
    }

    /** 显示名。 */
    public String displayName() {
        return displayName;
    }

    /** 是否核心内置武技（false = 外部插件注册）。 */
    public boolean core() {
        return core;
    }

    /** 归属插件名（核心武技为 SekiroBedwar）。 */
    public String ownerPlugin() {
        return ownerPlugin;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TechniqueId && ((TechniqueId) o).key.equals(key);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }

    @Override
    public String toString() {
        return key;
    }
}
