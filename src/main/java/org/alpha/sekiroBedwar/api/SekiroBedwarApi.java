package org.alpha.sekiroBedwar.api;

import org.alpha.sekiroBedwar.api.internal.SekiroApiImpl;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SekiroBedwar 公共 API 门面（对外唯一稳定入口）。
 *
 * <p><b>定位</b>：SekiroBedwar 是战斗规则核心插件；本 API 只做两件事——
 * ①<b>只读查询</b>（决斗 / 架势状态）②<b>事件通知</b>
 * （{@code org.alpha.sekiroBedwar.api.events} 包，见各事件 javadoc）。
 * 战斗统计（连段使用率 / 成功率）、排位 / MMR、排行榜、录像、连招推荐等业务
 * <b>一律不放在核心</b>——外部插件监听事件即可自行实现，例如统计插件
 * SekiroBedwar-Stats 监听 {@link org.alpha.sekiroBedwar.api.events.SecretTechniqueStartEvent} /
 * {@link org.alpha.sekiroBedwar.api.events.SecretTechniqueCompleteEvent} 计算成功率。</p>
 *
 * <p><b>依赖方式</b>：外部插件 {@code plugin.yml} 写 {@code depend: [SekiroBedwar]}，
 * 编译期引用本 jar。所有方法与事件均为 {@code @ApiStatus.Stable} 语义承诺：
 * 只增不改；新增秘传（{@link TechniqueId} 新枚举值）/ 新增忍具（{@link ToolId} 新枚举值）
 * <b>不需要</b>外部插件改代码（按 id 消费 + default 兜底即可兼容）。</p>
 *
 * <p><b>线程</b>：查询与事件都应在 Bukkit 主线程使用（事件天然主线程广播；
 * 异步查询不保证一致性，但不会抛异常）。核心未加载 / API 未安装时查询返回安全默认值。</p>
 */
public final class SekiroBedwarApi {

    private SekiroBedwarApi() {
    }

    // ==================== 决斗生命周期（只读查询） ====================

    /** 玩家是否处于决斗中（PENDING / ACTIVE）。 */
    public static boolean isInDuel(Player player) {
        return player != null && isInDuel(player.getUniqueId());
    }

    /** 玩家是否处于决斗中（PENDING / ACTIVE）。 */
    public static boolean isInDuel(UUID player) {
        return SekiroApiImpl.isInDuel(player);
    }

    /** 玩家当前决斗的只读快照；不在决斗中返回空。 */
    public static Optional<DuelInfo> duelOf(Player player) {
        return player == null ? Optional.empty() : duelOf(player.getUniqueId());
    }

    /** 玩家当前决斗的只读快照；不在决斗中返回空。 */
    public static Optional<DuelInfo> duelOf(UUID player) {
        return SekiroApiImpl.duelInfo(player);
    }

    /** 玩家对手；不在决斗中返回空。 */
    public static Optional<UUID> duelOpponentOf(UUID player) {
        return duelOf(player).flatMap(d -> d.opponentOf(player));
    }

    /** 当前全部进行中的决斗（快照列表）。 */
    public static List<DuelInfo> activeDuels() {
        return SekiroApiImpl.activeDuels();
    }

    // ==================== 架势（只读查询） ====================

    /**
     * 玩家架势只读快照；不在决斗中返回 {@link StancePhase#NONE}（数值 0）。
     * 外部<b>没有</b>修改架势的公共入口——一切架势变化只由核心战斗规则产生。
     */
    public static StanceSnapshot stanceOf(Player player) {
        return player == null ? SekiroApiImpl.stance(null) : stanceOf(player.getUniqueId());
    }

    /** 玩家架势只读快照。 */
    public static StanceSnapshot stanceOf(UUID player) {
        return SekiroApiImpl.stance(player);
    }

    // ==================== 枚举注册表（供外部展示） ====================

    /** 当前核心已实现的秘传 id 列表（新增秘传自动扩展，外部按值消费）。 */
    public static TechniqueId[] techniques() {
        return TechniqueId.values();
    }

    /** 当前核心会广播使用事件的忍具 id 列表。 */
    public static ToolId[] tools() {
        return ToolId.values();
    }
}
