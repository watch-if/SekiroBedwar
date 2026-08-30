package org.alpha.sekiroBedwar.freeze;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.screamingsandals.bedwars.api.events.GameEndEvent;
import org.screamingsandals.bedwars.api.events.ResourceSpawnEvent;
import org.screamingsandals.bedwars.api.game.ItemSpawner;
import org.screamingsandals.bedwars.api.game.LocalGame;
import org.screamingsandals.bedwars.api.types.server.ItemStackHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物资刷新冻结（独立模块）。
 *
 * <p>当刷新点位置落在任一决斗的白色内圈（{@link DuelConfig#innerRadius()}，与
 * {@code DuelVisualizer} 的白圈同半径）范围内时，拦截 {@link ResourceSpawnEvent}：
 * <b>计时照常、实际生成暂停</b>——事件取消后 {@code ItemSpawnerImpl} 直接 return，
 * 但 spawner 自身的 elapsedTime 周期不受影响。</p>
 *
 * <p>被拦截的生成量累计为「待生成资源」，决斗结束后按下一次正常刷新节点统一补出
 * （补出时与正常量合并为一堆，经 BedWars 地面上限自然约束）。缓存受
 * {@link FreezeConfig#resourceMaxCacheSeconds()}（默认 120s）窗口限制：窗口内的量保留，
 * 窗口外的溢出丢弃，避免决斗无上限时长导致缓存无限膨胀。</p>
 *
 * <p>线程安全：{@code ResourceSpawnEvent} 可能经 BedWars 任务线程触发，故 pending 用
 * {@link ConcurrentHashMap}，读改互不冲突；{@code GameEndEvent} / {@link #disable()} 清理在主线程。</p>
 */
public final class ResourceFreezeManager {
    private final SekiroBedwar plugin;
    private final FreezeConfig config;
    private final DuelConfig duelConfig;
    private final DuelManager duelManager;

    /** spawner → 待生成缓存（amount = 累计生成量，since = 首次冻结时刻）。 */
    private final Map<ItemSpawner, Pending> pending = new ConcurrentHashMap<>();

    public ResourceFreezeManager(SekiroBedwar plugin, FreezeConfig config, DuelConfig duelConfig,
                                 DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.duelConfig = duelConfig;
        this.duelManager = duelManager;
    }

    /** 注册事件处理与对局结束清理。 */
    public void enable() {
        ensureMainThread("enable");
        if (!config.resourceEnabled()) {
            return;
        }
        ResourceSpawnEvent.handle(plugin, this::onResourceSpawn);
        GameEndEvent.handle(plugin, ev -> pending.clear());
        plugin.getLogger().info("物资刷新冻结已启用（白圈半径=" + duelConfig.innerRadius()
                + "，缓存窗口=" + config.resourceMaxCacheSeconds() + "s）");
    }

    /** 插件禁用：清空待生成缓存。 */
    public void disable() {
        ensureMainThread("disable");
        pending.clear();
    }

    private void onResourceSpawn(ResourceSpawnEvent ev) {
        ItemSpawner spawner = ev.getItemSpawner();
        LocalGame game = ev.getGame();
        if (spawner == null || game == null) {
            return;
        }
        Location loc = spawner.getLocation().as(Location.class);
        if (loc == null) {
            return;
        }

        if (isSpawnerFrozen(game, loc)) {
            freezeSpawn(ev, spawner, loc);
        } else {
            flushPending(ev, spawner);
        }
    }

    private void freezeSpawn(ResourceSpawnEvent ev, ItemSpawner spawner, Location loc) {
        long maxCacheMillis = config.resourceMaxCacheSeconds() * 1000L;
        if (maxCacheMillis <= 0) {
            // 配置为不缓存：直接丢弃本次生成
            ev.setCancelled(true);
            return;
        }
        Pending p = pending.get(spawner);
        long now = System.currentTimeMillis();
        if (p != null && now - p.since >= maxCacheMillis) {
            // 缓存窗口已满：窗口外溢出丢弃，保留窗口内的量待决斗结束补出
            ev.setCancelled(true);
            return;
        }
        // API 的 ItemStackHolder 只有 Wrapper.as()，取数量先转 Bukkit ItemStack
        // （运行时实际是 slib ItemStack，含 getAmount()/withAmount()，但编译期不可见）
        int amount = ev.getResource() == null ? 0 : ev.getResource().as(ItemStack.class).getAmount();
        if (amount > 0) {
            if (p == null) {
                pending.put(spawner, new Pending(amount, now));
                plugin.getLogger().info("物资刷新冻结：刷新点(" + loc.getBlockX() + ", " + loc.getBlockY()
                        + ", " + loc.getBlockZ() + ") 位于决斗白圈内，实际生成暂停");
            } else {
                p.amount += amount;
            }
        }
        ev.setCancelled(true);
    }

    private void flushPending(ResourceSpawnEvent ev, ItemSpawner spawner) {
        Pending p = pending.remove(spawner);
        if (p == null || p.amount <= 0 || ev.getResource() == null) {
            return;
        }
        // 决斗结束后的下一个自然刷新节点：正常量 + 缓存量合并为一堆补出。
        // API 的 ItemStackHolder 只有 Wrapper.as()，需转 Bukkit ItemStack 改数量后
        // 再经 ItemStackHolder.of() 包回（Provider 按运行时平台适配包装）。
        ItemStack current = ev.getResource().as(ItemStack.class);
        current.setAmount(current.getAmount() + p.amount);
        ev.setResource(ItemStackHolder.of(current));
    }

    /** 刷新点是否位于任一进行中决斗的白色内圈内（PENDING 与 ACTIVE 都算「决斗期间」）。 */
    private boolean isSpawnerFrozen(LocalGame game, Location loc) {
        double inner = duelConfig.innerRadius();
        for (Duel duel : duelManager.getDuels()) {
            if (duel.getGame() != game || duel.getState() == DuelState.ENDING) {
                continue;
            }
            DuelIsland island = duel.getIsland();
            if (island.getWorld().equals(loc.getWorld()) && island.horizontalDistanceTo(loc) <= inner) {
                return true;
            }
        }
        return false;
    }

    /** 写操作必须位于 Bukkit 主线程（快速失败）。 */
    private static void ensureMainThread(String method) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ResourceFreezeManager." + method + " 必须在 Bukkit 主线程调用");
        }
    }

    private static final class Pending {
        int amount;
        final long since;

        Pending(int amount, long since) {
            this.amount = amount;
            this.since = since;
        }
    }
}
