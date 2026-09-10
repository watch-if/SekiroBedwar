package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.api.TechniqueCancelReason;
import org.alpha.sekiroBedwar.api.TechniqueCue;
import org.alpha.sekiroBedwar.api.TechniqueId;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCancelEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueCompleteEvent;
import org.alpha.sekiroBedwar.api.events.SecretTechniqueFailEvent;
import org.alpha.sekiroBedwar.event.DuelEndedEvent;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秘传宿主（独立模块）：注册 / 转发 / 生命周期清理 / 节奏运行时的统一入口。
 *
 * <p>近战命中钩子由 BlockManager（普通格挡 / 无格挡）与 ParryManager（完美弹反成功）
 * 在同一注入点转发（ACTIVE 决斗内、近战 only）；后续新核心秘传实现 {@link Mystery}
 * 并加入 {@link #arts} 即接入，无需再动两个战斗模块。</p>
 *
 * <p><b>对外运行时</b>（经 {@code SekiroBedwarApi} 门面暴露，供外部插件秘传使用）：
 * tick 时基（{@link #tick()}）、外部连段进度槽（{@link #setExternalProgress}）、
 * 跨式领先者查询（{@link #rivalProgress}）、统一音效 cue（{@link #playCue}）。
 * 核心四式与外部式共享同一份「领先者发声」视野。外部槽回收三重保障：
 * FAIL cue / 该式 Complete·Fail·Cancel 事件 / 玩家死亡·离场·决斗结束清理。</p>
 *
 * <p>死亡 / 退出 / 离局 / 决斗结束清各秘传的连击状态（跨局 / 跨场不残留）。</p>
 */
public final class MysteryManager implements Listener {

    private final SekiroBedwar plugin;
    private final List<Mystery> arts = new ArrayList<>();
    /** 防击退护身（飞渡浮舟 / 一心七连共用）。 */
    private final KnockbackGuard guard;
    /** 自维护 tick 时钟（spigot 无 getCurrentTick，且必须纯 spigot API）：1t 任务 +1。 */
    private int tickClock;
    private org.bukkit.scheduler.BukkitTask tickTask;
    /** 外部秘传进度槽：TechniqueId → (玩家 → 已完成段数)，由 setExternalProgress 维护。 */
    private final Map<TechniqueId, Map<UUID, Integer>> externalProgress = new ConcurrentHashMap<>();

    public MysteryManager(SekiroBedwar plugin, MysteryConfig config,
                          StanceManager stanceManager, PaperDollManager paperDollManager,
                          org.alpha.sekiroBedwar.duel.DuelManager duelManager,
                          org.alpha.sekiroBedwar.duel.DuelConfig duelConfig) {
        this.plugin = plugin;
        this.guard = new KnockbackGuard(config);
        java.util.function.IntSupplier tick = () -> tickClock;
        java.util.function.BiFunction<Mystery, UUID, Integer> rivalTop = this::topProgress;
        // 已实现的秘传（新增秘传：构造 + 在此 add 一行）
        if (config.fdfzEnabled()) {
            arts.add(new FeiduFuzhou(config, stanceManager, paperDollManager, guard, tick, rivalTop));
        }
        if (config.yameEnabled()) {
            arts.add(new YamedoCrossSlash(config, stanceManager, tick, rivalTop));
        }
        if (config.lsEnabled()) {
            arts.add(new LongShan(plugin, config, stanceManager, paperDollManager, duelManager, duelConfig,
                    tick, rivalTop));
        }
        if (config.isshinEnabled()) {
            arts.add(new IsshinSevenStrike(config, stanceManager, paperDollManager, guard, tick, rivalTop));
        }
    }

    /** 除 {@code self} 外其他武技（核心式 + 外部槽）在该玩家身上的最高连段进度。 */
    private int topProgress(Mystery self, UUID player) {
        int top = 0;
        for (Mystery art : arts) {
            if (art == self) {
                continue;
            }
            top = Math.max(top, art.comboProgress(player));
        }
        return Math.max(top, externalMaxExcluding(self.id(), player));
    }

    // ==================== 对外运行时（SekiroBedwarApi 门面委托到此） ====================

    /** 外部秘传上报连段进度（hits ≤ 0 = 清槽；核心式进度由核心自持，外部不可写）。 */
    public void setExternalProgress(TechniqueId id, UUID player, int hits) {
        if (id == null || id.core() || player == null) {
            return;
        }
        if (hits <= 0) {
            Map<UUID, Integer> slots = externalProgress.get(id);
            if (slots != null) {
                slots.remove(player);
            }
            return;
        }
        externalProgress.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(player, hits);
    }

    /** 除 {@code id} 外所有式（核心 + 外部）在该玩家的最高进度（领先者查询）。 */
    public int rivalProgress(TechniqueId id, UUID player) {
        int top = 0;
        for (Mystery art : arts) {
            if (id != null && art.id().equals(id.configKey())) {
                continue;
            }
            top = Math.max(top, art.comboProgress(player));
        }
        return Math.max(top, externalMaxExcluding(id == null ? "" : id.configKey(), player));
    }

    /** 某式当前进度（核心式读实现、外部式读槽）。 */
    public int progressOf(TechniqueId id, UUID player) {
        for (Mystery art : arts) {
            if (art.id().equals(id.configKey())) {
                return art.comboProgress(player);
            }
        }
        Map<UUID, Integer> slots = externalProgress.get(id);
        return slots == null ? 0 : slots.getOrDefault(player, 0);
    }

    /**
     * 统一发声 cue：SUCCESS = 铁砧落地、FAIL = 铁砧打磨；仅当该式在该玩家身上
     * 进度领先（≥ 其他所有式）时出声。FAIL 自动回收外部式进度槽。
     */
    public void playCue(TechniqueId id, Player player, TechniqueCue cue) {
        if (id == null || player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int mine = progressOf(id, uuid);
        boolean leader = rivalProgress(id, uuid) <= mine;
        if (leader) {
            player.playSound(player.getLocation(),
                    cue == TechniqueCue.SUCCESS ? Sound.BLOCK_ANVIL_LAND : Sound.BLOCK_ANVIL_USE,
                    1.0f, 1.0f);
        }
        if (cue == TechniqueCue.FAIL) {
            setExternalProgress(id, uuid, 0);
        }
    }

    private int externalMaxExcluding(String excludeKey, UUID player) {
        int top = 0;
        for (Map.Entry<TechniqueId, Map<UUID, Integer>> entry : externalProgress.entrySet()) {
            if (entry.getKey().configKey().equals(excludeKey)) {
                continue;
            }
            Integer hits = entry.getValue().get(player);
            if (hits != null) {
                top = Math.max(top, hits);
            }
        }
        return top;
    }

    /** 当前 tick 计数（节奏判定的统一时基，宿主 1t 任务驱动）。 */
    public int tick() {
        return tickClock;
    }

    public void enable() {
        // 运行时服务（tick 时基 / 外部进度槽 / 事件回收）与核心式数量无关，始终注册
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tickTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> tickClock++, 1L, 1L);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid(), TechniqueCancelReason.LEFT));
        if (!arts.isEmpty()) {
            plugin.getServer().getPluginManager().registerEvents(guard, plugin);
        }
        for (Mystery art : arts) {
            plugin.getLogger().info("秘传已启用：" + art.id());
        }
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Mystery art : arts) {
            art.clearAll();
            art.shutdown();
        }
        guard.clearAll();
        externalProgress.clear();
    }

    /** BlockManager / ParryManager 的近战命中转发（parried = 该击被完美弹反）。 */
    public void onAttack(Player attacker, Player victim, boolean parried) {
        for (Mystery art : arts) {
            art.onAttack(attacker, victim, parried);
        }
    }

    /** 左键挥臂转发（龙闪释放；不取消原版攻击动作）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClick(org.bukkit.event.player.PlayerAnimationEvent event) {
        for (Mystery art : arts) {
            art.onLeftClick(event.getPlayer());
        }
    }

    /** 快捷栏切换转发（空手换刀起手的武技即时武装；取消的切换不转发）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotSwitch(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack current = player.getInventory().getItem(event.getNewSlot());
        for (Mystery art : arts) {
            art.onSlotSwitch(player, previous, current);
        }
    }

    private void clearPlayer(UUID uuid, TechniqueCancelReason reason) {
        guard.clear(uuid);
        for (Mystery art : arts) {
            art.clear(uuid, reason);
        }
        for (Map<UUID, Integer> slots : externalProgress.values()) {
            slots.remove(uuid);
        }
    }

    /** 外部式连段终结（Complete / Fail / Cancel 事件）自动回收进度槽，防幽灵进度压制他式。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTechEnded(SecretTechniqueCompleteEvent event) {
        setExternalProgress(event.technique(), event.player(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTechEnded(SecretTechniqueFailEvent event) {
        setExternalProgress(event.technique(), event.player(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTechEnded(SecretTechniqueCancelEvent event) {
        setExternalProgress(event.technique(), event.player(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        clearPlayer(event.getEntity().getUniqueId(), TechniqueCancelReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId(), TechniqueCancelReason.LEFT);
    }

    /** 决斗结束：双方连击状态清零（跨场不残留；下一场从第一式重新起连）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelEnded(DuelEndedEvent event) {
        clearPlayer(event.getDuel().getPlayerAUuid(), TechniqueCancelReason.DUEL_ENDED);
        clearPlayer(event.getDuel().getPlayerBUuid(), TechniqueCancelReason.DUEL_ENDED);
    }
}
