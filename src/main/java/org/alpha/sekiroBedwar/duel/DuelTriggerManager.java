package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelTriggeredEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.Team;
import org.screamingsandals.bedwars.api.events.GameEndEvent;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.player.BWPlayer;
import org.screamingsandals.bedwars.api.player.PlayerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 决斗触发核心管理器（独立模块）。
 *
 * <p>触发条件（全部满足）：
 * <ol>
 *   <li>两名敌对玩家（不同队伍、都存活、非观战）处于<b>同一对局</b>且对局进行中（RUNNING）；</li>
 *   <li>双方在追溯窗口内都“有效主动命中”过对方（互相命中，见 {@link ValidAttackPredicate}）；</li>
 *   <li>两人位于同一“决斗岛屿”内（以两人中点为圆心、半径 radius 的圆，且实心非虚空，
 *       可叠加 duel.yml 岛屿白名单）；</li>
 *   <li>岛屿外围规定半径内不存在第三方玩家；</li>
 *   <li>同一对玩家不在触发冷却中。</li>
 * </ol>
 * 靠近、空挥、搭方块、普通移动均不会触发。</p>
 *
 * <p>触发成功后：广播 {@link DuelTriggeredEvent}，并由 {@link DuelVisualizer}
 * 展示双方 GLOWING 光箭矢高亮 + 白色内圆(y) + 红色外圆(z) 粒子（持续到决斗结束）。</p>
 */
public final class DuelTriggerManager {
    private final SekiroBedwar plugin;
    private final DuelConfig config;
    private final DuelVisualizer visualizer;
    private final DuelTriggerListener listener;

    /** 有效主动攻击判定接口（可替换默认实现）。 */
    private ValidAttackPredicate validAttackPredicate;

    /** 玩家是否已处于决斗中的判定钩子（由 {@link DuelManager} 提供，避免重复触发）。 */
    private Predicate<UUID> alreadyInDuelPredicate;

    /** attacker -> (victim -> 最近命中时间戳)。 */
    private final Map<UUID, Map<UUID, Long>> attackRecords = new HashMap<>();
    /** 同一对玩家上次触发决斗的时间戳（防重复触发）。 */
    private final Map<DuelKey, Long> cooldowns = new HashMap<>();

    public DuelTriggerManager(SekiroBedwar plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.validAttackPredicate = new DefaultValidAttackPredicate(config);
        this.visualizer = new DuelVisualizer(plugin, config);
        this.listener = new DuelTriggerListener(this, validAttackPredicate);
    }

    /** 注册 Bukkit 监听与 BedWars API 事件。 */
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        if (Bukkit.getPluginManager().getPlugin("ScreamingBedWars") == null) {
            plugin.getLogger().warning("未检测到 ScreamingBedWars，决斗生命周期清理事件未注册（对局判定将不生效）");
            return;
        }
        // BedWars API 事件：玩家离局 / 整局结束 时清理决斗状态
        PlayerLeaveEvent.handle(plugin, ev -> purgePlayer(ev.getPlayer().getUuid()));
        GameEndEvent.handle(plugin, ev -> clearAll());
    }

    /** 插件禁用：清理所有状态与特效。 */
    public void disable() {
        clearAll();
        visualizer.disable();
    }

    /** 替换有效主动攻击判定实现。 */
    public void setValidAttackPredicate(ValidAttackPredicate predicate) {
        this.validAttackPredicate = predicate;
    }

    /** 当前使用的有效主动攻击判定实现。 */
    public ValidAttackPredicate getValidAttackPredicate() {
        return validAttackPredicate;
    }

    /** 设置"玩家是否已处于决斗中"的判定钩子（由 {@link DuelManager} 提供）。 */
    public void setAlreadyInDuelPredicate(Predicate<UUID> predicate) {
        this.alreadyInDuelPredicate = predicate;
    }

    /**
     * 记录一次“有效主动攻击”（由 {@link DuelTriggerListener} 在判定通过后调用）。
     *
     * @param attacker 攻击方
     * @param victim   被攻击方
     */
    public void recordValidAttack(Player attacker, Player victim) {
        attackRecords.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>())
                .put(victim.getUniqueId(), System.currentTimeMillis());
    }

    /** 决斗结束（任意原因）：立即清除该场决斗的荧光与粒子特效，恢复双方决斗前的可见状态。 */
    public void hideVisuals(Player a, Player b) {
        visualizer.clearDuel(a, b);
    }

    /** 玩家离开/下线时清理其全部相关状态。 */
    public void purgePlayer(UUID uuid) {
        attackRecords.remove(uuid);
        attackRecords.values().forEach(inner -> inner.remove(uuid));
        cooldowns.keySet().removeIf(key -> key.contains(uuid));
    }

    /** 清空所有攻击记录与冷却。 */
    public void clearAll() {
        attackRecords.clear();
        cooldowns.clear();
        visualizer.clearAll();
    }

    /**
     * 评估并尝试触发一次决斗（对称调用：A 命中 B 与 B 命中 A 都会评估同一对）。
     *
     * @return 是否成功触发
     */
    public boolean tryTriggerDuel(Player attacker, Player victim) {
        if (attacker == null || victim == null || attacker.equals(victim)
                || !attacker.isOnline() || !victim.isOnline()) {
            return false;
        }
        // 任一方已处于决斗中则不再触发新决斗（单决斗互斥的触发侧兜底）
        if (alreadyInDuelPredicate != null
                && (alreadyInDuelPredicate.test(attacker.getUniqueId()) || alreadyInDuelPredicate.test(victim.getUniqueId()))) {
            return false;
        }
        DuelKey key = DuelKey.of(attacker.getUniqueId(), victim.getUniqueId());
        if (inCooldown(key)) {
            return false;
        }
        if (Bukkit.getPluginManager().getPlugin("ScreamingBedWars") == null) {
            return false;
        }

        PlayerManager playerManager = BedwarsAPI.getInstance().getPlayerManager();
        Optional<? extends BWPlayer> optA = playerManager.getPlayer(attacker.getUniqueId());
        Optional<? extends BWPlayer> optB = playerManager.getPlayer(victim.getUniqueId());
        if (optA.isEmpty() || optB.isEmpty()) {
            return false;
        }
        BWPlayer bwA = optA.get();
        BWPlayer bwB = optB.get();
        if (bwA.isSpectator() || bwB.isSpectator() || !bwA.isInGame() || !bwB.isInGame()) {
            return false;
        }

        // 1) 同一对局 + 进行中
        LocalGame gameA = bwA.getGame();
        LocalGame gameB = bwB.getGame();
        if (gameA == null || gameB == null || !gameA.getUuid().equals(gameB.getUuid())) {
            return false;
        }
        if (gameA.getStatus() != GameStatus.RUNNING) {
            return false;
        }

        // 2) 敌对（不同队伍）、两队存活
        Team teamA = gameA.getTeamOfPlayer(bwA);
        Team teamB = gameA.getTeamOfPlayer(bwB);
        if (teamA == null || teamB == null || teamA.equals(teamB) || !teamA.isAlive() || !teamB.isAlive()) {
            return false;
        }

        // 3) 双方在追溯窗口内互相有效命中
        if (!hasRecentAttack(attacker, victim) || !hasRecentAttack(victim, attacker)) {
            return false;
        }

        // 4) 同一实心决斗岛屿（动态中点圆 + 可选白名单）
        Optional<DuelIsland> islandOpt = config.resolveIsland(attacker.getLocation(), victim.getLocation());
        if (islandOpt.isEmpty() || !islandOpt.get().isLegal(config)) {
            return false;
        }
        DuelIsland island = islandOpt.get();

        // 5) 岛屿外围规定半径内不存在第三方
        if (hasThirdParty(gameA, island, bwA, bwB)) {
            return false;
        }

        // 触发成功：进冷却 → 广播事件 → 展示特效
        cooldowns.put(key, System.currentTimeMillis());
        Bukkit.getPluginManager().callEvent(new DuelTriggeredEvent(attacker, victim, gameA, island));
        visualizer.show(attacker, victim, island, gameA);
        return true;
    }

    private boolean hasRecentAttack(Player attacker, Player victim) {
        Map<UUID, Long> hits = attackRecords.get(attacker.getUniqueId());
        if (hits == null) {
            return false;
        }
        Long time = hits.get(victim.getUniqueId());
        return time != null && (System.currentTimeMillis() - time) <= config.attackRecallSeconds() * 1000L;
    }

    private boolean inCooldown(DuelKey key) {
        Long time = cooldowns.get(key);
        if (time == null) {
            return false;
        }
        if (System.currentTimeMillis() - time > config.duelCooldownSeconds() * 1000.0) {
            cooldowns.remove(key);
            return false;
        }
        return true;
    }

    private boolean hasThirdParty(LocalGame game, DuelIsland island, BWPlayer duelistA, BWPlayer duelistB) {
        double limit = island.getRadius() + config.outerRadius();
        for (BWPlayer other : game.getConnectedPlayers()) {
            UUID id = other.getUuid();
            if (id.equals(duelistA.getUuid()) || id.equals(duelistB.getUuid()) || other.isSpectator()) {
                continue;
            }
            Player bukkit = Bukkit.getPlayer(id);
            if (bukkit == null || !bukkit.isOnline()) {
                continue;
            }
            // 世界切换：仅在岛屿同一世界内判断水平距离，异世界玩家不算第三方
            if (!bukkit.getWorld().equals(island.getWorld())) {
                continue;
            }
            if (island.horizontalDistanceTo(bukkit.getLocation()) <= limit) {
                return true;
            }
        }
        return false;
    }

    /** 无序对（规范化排序），用于冷却键。 */
    private record DuelKey(UUID a, UUID b) {
        static DuelKey of(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new DuelKey(a, b) : new DuelKey(b, a);
        }

        boolean contains(UUID uuid) {
            return a.equals(uuid) || b.equals(uuid);
        }
    }
}
