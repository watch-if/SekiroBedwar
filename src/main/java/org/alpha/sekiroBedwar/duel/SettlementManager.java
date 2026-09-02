package org.alpha.sekiroBedwar.duel;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.alpha.sekiroBedwar.stance.StanceConfig;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 决斗资源结算管理器（独立模块，与触发 / 架势系统解耦）。
 *
 * <p><b>资源快照</b>（{@code startSnapshots}）：决斗开始（ACTIVE）时记录双方配置资源数量
 * （{@link DuelResourceSnapshot}），贯穿整个决斗，决斗结束（任意原因）时清理。</p>
 *
 * <p>四情形结算（比例均来自 {@code settlement:} 段，{@link SettlementConfig}）：</p>
 * <ul>
 *   <li><b>崩条死亡 / 处决</b>（{@code break-kill-ratio}，默认 0.5）：处决窗口内被杀
 *       （{@link PlayerDeathEvent} + {@code isBroken}）或处决窗口到期（{@link #checkTimeout}）→
 *       败者资源按比例转移给胜者，决斗以 {@link EndReason#EXECUTED} / {@link EndReason#EXECUTION_TIMEOUT} 结束；</li>
 *   <li><b>普通死亡</b>（{@code normal-kill-ratio}，默认 1.0）：未崩条被对方击杀（含 killer 为空时
 *       用 {@code getLastDamageCause()} 兜底）→ 按比例转移，决斗以 {@link EndReason#EXECUTED} 结束；</li>
 *   <li><b>虚空死亡</b>（{@code void-kill-ratio}，默认 1.0）：{@code lastDamageCause = VOID} →
 *       按比例转移并记录胜者，决斗以 {@link EndReason#VOID_DEATH} 结束（被击落虚空 = 决斗死亡）；</li>
 *   <li><b>第三方介入</b>（{@link EndReason#THIRD_PARTY_ENTERED}）：不转移，双方资源<b>回滚</b>到
 *       决斗开始快照（仅在线 / 未死亡者）。</li>
 * </ul>
 * 其余结束原因（退出 / 离局 / 对局结束 / 手动 / 插件禁用）不结算，只清理快照。</p>
 *
 * <p>结算资源类型与架势最大架势共用 {@code stance.max-stance.resources} 映射
 * （铁 / 金 / 钻石 / 绿宝石，可配置增改），只转移这些类型的物品，不触碰其余物品。</p>
 *
 * <p><b>线程安全</b>：监听器与周期任务都在 Bukkit 主线程执行；写操作经 {@link #ensureMainThread} 守卫，
 * 快照容器用 {@link ConcurrentHashMap} 保证读安全。</p>
 *
 * <p><b>防泄漏</b>：结算后经 {@link DuelManager#endDuel} 结束决斗，由 DuelEndedEvent 广播完成
 * 架势 / 架势条状态清理；快照在 {@link #onDuelEnded} 移除双方 UUID，{@link #disable()} 清空。</p>
 */
public final class SettlementManager {
    private final SekiroBedwar plugin;
    private final StanceConfig config;
    private final SettlementConfig settlementConfig;
    private final DuelManager duelManager;
    private final StanceManager stanceManager;
    private final SettlementListener listener;

    /** 决斗开始时双方资源快照：玩家 UUID → 起始资源（贯穿决斗，决斗结束时清理）。 */
    private final ConcurrentMap<UUID, DuelResourceSnapshot> startSnapshots = new ConcurrentHashMap<>();

    private BukkitTask checkTask;

    public SettlementManager(SekiroBedwar plugin, StanceConfig config,
                             SettlementConfig settlementConfig,
                             DuelManager duelManager, StanceManager stanceManager) {
        this.plugin = plugin;
        this.config = config;
        this.settlementConfig = settlementConfig;
        this.duelManager = duelManager;
        this.stanceManager = stanceManager;
        this.listener = new SettlementListener(this);
    }

    /** 注册监听 + 启动周期检测（ACTIVE 补记快照 / 处决窗口到期结算）。 */
    public void enable() {
        ensureMainThread("enable");
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        checkTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::checkTimeout, 1L, Math.max(1, config.settleCheckTicks()));
    }

    /** 插件禁用：取消周期检测任务并清空快照（防泄漏）。 */
    public void disable() {
        ensureMainThread("disable");
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        startSnapshots.clear();
    }

    /**
     * 玩家死亡（由 {@link SettlementListener} 在 HIGHEST 优先级调用，先于掉落 / 观战流程）。
     * 死者处于决斗且死亡为虚空坠落 / 被对方击杀 → 按对应比例结算并结束决斗。
     * 虚空与击杀两条分支互斥，同一死亡事件只结算一次，不会与 Bukkit 原版流程重复结算。
     */
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim == null) {
            return;
        }
        Duel duel = duelManager.getDuel(victim.getUniqueId()).orElse(null);
        if (duel == null) {
            // 非决斗死亡：被玩家击杀 → 全部资源（铁金钻绿）转移给击杀者；其余由 BedWars 清除不掉落。
            Player killer = victim.getKiller();
            if (killer != null && !killer.equals(victim)) {
                settleFromDrops(event, victim, killer, 1.0);
            }
            return;
        }
        DuelState state = duel.getState();
        if (state != DuelState.PENDING && state != DuelState.ACTIVE) {
            return;
        }
        Player opponent = opponentOf(duel, victim.getUniqueId());
        if (opponent == null || !opponent.isOnline()) {
            return;
        }
        // 虚空死亡（被击落虚空 = 决斗死亡）：按虚空比例结算并记录胜者。
        // 死亡瞬间死者背包已被清空（物品全部移入 event.getDrops()），故从掉落列表结算。
        if (isVoidDeath(victim)) {
            settleFromDrops(event, victim, opponent, settlementConfig.voidKillRatio());
            duelManager.endDuel(duel, EndReason.VOID_DEATH);
            return;
        }
        if (isKilledBy(victim, opponent)) {
            // 崩条内被杀（处决）→ 崩条比例；未崩条被击杀 → 普通比例
            double ratio = stanceManager.isBroken(victim.getUniqueId())
                    ? settlementConfig.breakKillRatio()
                    : settlementConfig.normalKillRatio();
            settleFromDrops(event, victim, opponent, ratio);
            duelManager.endDuel(duel, EndReason.EXECUTED);
        }
    }

    /**
     * 周期检测（主线程）：
     * <ol>
     *   <li>对 ACTIVE 决斗补记双方资源快照（幂等，决斗开始基准）；</li>
     *   <li>决斗中一方处决窗口到期且双方仍存活 → 按崩条比例结算并立即结束决斗。
     *       被处决者保留剩余部分（决斗结束由 DuelEndedEvent 清理其架势状态）。</li>
     * </ol>
     */
    private void checkTimeout() {
        for (Duel duel : duelManager.getDuels()) {
            DuelState state = duel.getState();
            if (state == DuelState.ACTIVE) {
                snapshotIfNeeded(duel);
            }
            if (state != DuelState.PENDING && state != DuelState.ACTIVE) {
                continue;
            }
            Player a = duel.getPlayerA();
            Player b = duel.getPlayerB();
            if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
                continue;
            }
            if (a.isDead() || b.isDead()) {
                continue;
            }
            if (stanceManager.isExecutionWindowExpired(duel.getPlayerAUuid())) {
                settle(a, b, settlementConfig.breakKillRatio());
                duelManager.endDuel(duel, EndReason.EXECUTION_TIMEOUT);
            } else if (stanceManager.isExecutionWindowExpired(duel.getPlayerBUuid())) {
                settle(b, a, settlementConfig.breakKillRatio());
                duelManager.endDuel(duel, EndReason.EXECUTION_TIMEOUT);
            }
        }
    }

    /**
     * 决斗结束（由 {@link SettlementListener} 以 NORMAL 优先级调用，
     * 先于 {@code StanceListener} 的 MONITOR 清理架势状态）：
     * <ol>
     *   <li>移除双方资源快照（贯穿决斗，结束时清理，防泄漏）；</li>
     *   <li>第三方介入（{@link EndReason#THIRD_PARTY_ENTERED}）→ 双方资源回滚到决斗开始快照
     *       （仅在线 / 未死亡 / 快照有效者）；其余结束原因不结算。</li>
     * </ol>
     * 临时状态（架势 / 架势条 / 荧光）由决斗结束事件的其余监听器完成清理。
     */
    public void onDuelEnded(DuelEndedEvent event) {
        Duel duel = event.getDuel();
        DuelResourceSnapshot snapA = startSnapshots.remove(duel.getPlayerAUuid());
        DuelResourceSnapshot snapB = startSnapshots.remove(duel.getPlayerBUuid());
        if (event.getReason() != EndReason.THIRD_PARTY_ENTERED) {
            return;
        }
        restoreIfOnline(event.getPlayerA(), snapA);
        restoreIfOnline(event.getPlayerB(), snapB);
    }

    /** 决斗开始时补记双方资源快照（幂等；PENDING 期不记，ACTIVE 后首轮补上）。 */
    private void snapshotIfNeeded(Duel duel) {
        UUID ua = duel.getPlayerAUuid();
        UUID ub = duel.getPlayerBUuid();
        if (startSnapshots.containsKey(ua) && startSnapshots.containsKey(ub)) {
            return;
        }
        snapshotPlayer(ua);
        snapshotPlayer(ub);
    }

    private void snapshotPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        startSnapshots.put(uuid, new DuelResourceSnapshot(currentResourceCounts(player)));
    }

    /** 玩家可恢复（在线 / 未死亡 / 快照有效）时把背包资源回滚为快照数量。 */
    private void restoreIfOnline(Player player, DuelResourceSnapshot snapshot) {
        if (player == null || !player.isOnline() || player.isDead() || snapshot == null) {
            return;
        }
        restoreResources(player, snapshot);
    }

    /** 把玩家背包资源恢复为快照数量：多的扣除、少的补足（覆盖物品掉落 / 背包变化 / 购买消耗）。 */
    private void restoreResources(Player player, DuelResourceSnapshot snapshot) {
        Map<Material, Integer> current = currentResourceCounts(player);
        for (Material type : config.resourceCoefficients().keySet()) {
            int desired = snapshot.getCount(type);
            int have = current.getOrDefault(type, 0);
            if (have > desired) {
                removeResource(player, type, have - desired);
            } else if (have < desired) {
                give(player, type, desired - have);
            }
        }
    }

    /** 从玩家背包逐槽扣除指定数量的资源（整栈移除 / 末栈 setAmount）。 */
    private void removeResource(Player player, Material type, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != type) {
                continue;
            }
            if (item.getAmount() > remaining) {
                item.setAmount(item.getAmount() - remaining);
                player.getInventory().setItem(i, item);
                remaining = 0;
            } else {
                remaining -= item.getAmount();
                player.getInventory().setItem(i, null);
            }
        }
    }

    /** 玩家当前背包中各配置资源的总数量（只读）。 */
    private Map<Material, Integer> currentResourceCounts(Player player) {
        Map<Material, Integer> counts = new HashMap<>();
        Set<Material> resources = config.resourceCoefficients().keySet();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !resources.contains(item.getType())) {
                continue;
            }
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    /** 把资源物品放入玩家背包；背包满装不下的部分丢到玩家脚下。 */
    private void give(Player player, Material type, int amount) {
        int maxStack = type.getMaxStackSize();
        while (amount > 0) {
            int chunk = Math.min(amount, maxStack);
            ItemStack give = new ItemStack(type, chunk);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            amount -= chunk;
        }
    }

    /** 死者是否被决斗对手击杀（killer 为空时用最后伤害来源兜底，覆盖被击落虚空前的近战/投射物伤害）。 */
    private boolean isKilledBy(Player victim, Player opponent) {
        Player killer = victim.getKiller();
        if (killer != null && killer.getUniqueId().equals(opponent.getUniqueId())) {
            return true;
        }
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage
                && damage.getDamager() instanceof Player damager
                && damager.getUniqueId().equals(opponent.getUniqueId())) {
            return true;
        }
        return false;
    }

    /** 死亡是否为虚空坠落（lastDamageCause = VOID，被击落虚空 = 决斗死亡）。 */
    private boolean isVoidDeath(Player victim) {
        return victim.getLastDamageCause() instanceof EntityDamageEvent damage
                && damage.getCause() == EntityDamageEvent.DamageCause.VOID;
    }

    /** 决斗中另一名玩家。 */
    private Player opponentOf(Duel duel, UUID uuid) {
        return duel.getPlayerAUuid().equals(uuid) ? duel.getPlayerB() : duel.getPlayerA();
    }

    /**
     * 结算：把 {@code from} 背包中配置资源类型物品的 {@code ratio} 比例转移给 {@code to}。
     * {@code ratio}=1.0 全额、0.5 半额（每种资源数量各自向下取整）。
     * 只转移配置资源类型，不触碰其余物品；{@code to} 背包满装不下的部分还给 {@code from}。
     */
    private void settle(Player from, Player to, double ratio) {
        if (from == null || to == null || !from.isOnline() || !to.isOnline()) {
            return;
        }
        Set<Material> resources = config.resourceCoefficients().keySet();
        ItemStack[] contents = from.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || !resources.contains(item.getType())) {
                continue;
            }
            int take = (int) Math.floor(item.getAmount() * ratio);
            if (take <= 0) {
                continue;
            }
            // 保留原物品（自定义显示名 / NBT，如 BedWars 资源的 "Iron"），只改数量——
            // 转移"原模原样"：折算对方背包应扣除的数量、以原标签加回自己背包，
            // 避免重建裸物品丢掉标签（显示成"铁锭"等默认名）。
            ItemStack give = item.clone();
            give.setAmount(take);
            if (item.getAmount() > take) {
                item.setAmount(item.getAmount() - take);
                from.getInventory().setItem(i, item);
            } else {
                from.getInventory().setItem(i, null);
            }
            Map<Integer, ItemStack> leftover = to.getInventory().addItem(give);
            for (ItemStack rest : leftover.values()) {
                from.getInventory().addItem(rest);
            }
        }
    }

    /**
     * 从死亡掉落列表按比例结算：死亡瞬间死者背包已被清空、物品全部移入
     * {@link PlayerDeathEvent#getDrops()}（此监听在 HIGHEST 优先级，先于掉落执行，
     * 背包此刻为空），因此直接在掉落列表上转移配置资源给胜者，而非读空背包。
     * 转移后剩余部分保留在掉落列表随原版掉落；{@code to} 背包满装不下的部分也放回
     * 掉落列表（资源仍归地面掉落，不吞没）。
     */
    private void settleFromDrops(PlayerDeathEvent event, Player from, Player to, double ratio) {
        if (from == null || to == null || !from.isOnline() || !to.isOnline()) {
            return;
        }
        Set<Material> resources = config.resourceCoefficients().keySet();
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> overflow = new ArrayList<>();
        for (ListIterator<ItemStack> it = drops.listIterator(); it.hasNext(); ) {
            ItemStack item = it.next();
            if (item == null || !resources.contains(item.getType())) {
                continue;
            }
            int take = (int) Math.floor(item.getAmount() * ratio);
            if (take <= 0) {
                continue;
            }
            // 保留原物品（自定义显示名 / NBT，如 "Iron"），只改数量——转移"原模原样"，
            // 不给胜者重建裸物品（否则丢标签显示成"铁锭"等默认名）。
            ItemStack give = item.clone();
            give.setAmount(take);
            int remain = item.getAmount() - take;
            if (remain > 0) {
                item.setAmount(remain);
                it.set(item);
            } else {
                it.remove();
            }
            overflow.addAll(to.getInventory().addItem(give).values());
        }
        drops.addAll(overflow);
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败，防止异步线程篡改状态）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("SettlementManager." + method + " 必须在 Bukkit 主线程调用");
        }
    }
}
