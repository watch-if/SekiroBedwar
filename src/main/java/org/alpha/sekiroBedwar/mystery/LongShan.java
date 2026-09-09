package org.alpha.sekiroBedwar.mystery;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelIsland;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 秘传第三式·龙闪（{@link Mystery} 实现）：空手蓄力的双手音波斩。
 *
 * <p><b>起手</b>：主手<b>持续空手 ≥ {@code min-empty-hand-seconds}(1s)</b> 后快捷栏换持
 * 近战武器 → <b>即时武装</b>（{@code PlayerItemHeldEvent} 驱动，与苇名 0.5~1s 窗口互补，
 * 两式判定天然互斥不重叠）。武装后玩家<b>左键挥臂</b>即释放（不取消普通攻击），
 * 但释放动作必须在<b>换刀后 1 tick 内</b>完成（超时 = 脱拍：武装作废 + 铁砧打磨音，
 * 须重新空手换刀）。</p>
 *
 * <p><b>释放</b>：消耗 {@code paper-doll-cost}(2) 纸人（不足则低音提示、不消耗武装）
 * + 铁砧落地音，朝释放者<b>面朝方向</b>（水平投影）发射一道监守者音波式飞行波柱——
 * 每 tick 前进 {@code wave-speed-blocks-per-tick} 格、竖直方向覆盖脚位起向上
 * {@code height-up}(4) 格（{@code hit-radius} 水平判定），<b>飞出决斗白圈
 * （{@code visuals.inner-radius}）即消散</b>（非决斗场景按 {@code fallback-range-blocks}
 * 距离终止）。路径碰到的玩家（释放者除外）受 {@code damage-hp}(2) 点泛型直伤
 * （不走格挡 / 弹反换算）+ {@code damage-stance}(10) 架势伤害，每波每人只判定一次。</p>
 *
 * <p><b>二段波</b>：第一波放出后隔 {@code second-wave-delay-seconds}(1s)，
 * 在<b>同一触发位置、同一方向</b>自动补射第二波（伤害与判定相同，不二次扣纸人）。</p>
 */
public final class LongShan implements Mystery {

    private final SekiroBedwar plugin;
    private final MysteryConfig config;
    private final StanceManager stanceManager;
    private final PaperDollManager paperDollManager;
    private final DuelManager duelManager;
    private final DuelConfig duelConfig;

    private final BukkitTask waveTask;
    /** 已武装玩家 → 武装时刻（左键释放须在其后 1 tick 内衔接）。 */
    private final Map<UUID, Long> armedAt = new HashMap<>();
    /** 玩家 → 本轮空手起始时刻（「持物→空」切换时写入；无记录 = 开局即空手）。 */
    private final Map<UUID, Long> emptySince = new HashMap<>();
    /** 释放中双波的公共统计：玩家 → 波命中数（第二波放完随 Complete 一起消费清除）。 */
    private final Map<UUID, Integer> hitsByCaster = new HashMap<>();
    /** 存活中的飞行波。 */
    private final List<Wave> waves = new ArrayList<>();

    public LongShan(SekiroBedwar plugin, MysteryConfig config, StanceManager stanceManager,
                    PaperDollManager paperDollManager, DuelManager duelManager, DuelConfig duelConfig) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.paperDollManager = paperDollManager;
        this.duelManager = duelManager;
        this.duelConfig = duelConfig;
        this.waveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickWaves, 1L, 1L);
    }

    @Override
    public String id() {
        return "long-shan";
    }

    // ============ 武装（空手 ≥1s → 换刀，事件即时） ============

    /**
     * 快捷栏切换（宿主即时转发）：持物→空 = 空手起表；空→近战武器且空手 ≥1s = 即时武装
     * （与苇名 0.5~1s 窗口互补互斥）。
     */
    @Override
    public void onSlotSwitch(Player player, ItemStack previous, ItemStack current) {
        if (!org.alpha.sekiroBedwar.combat.BwScope.inGame(player.getUniqueId())) {
            return; // 玩法只在 BedWars 对局内触发：对局外不簿记、不武装
        }
        boolean prevEmpty = previous == null || previous.getType().isAir();
        boolean curEmpty = current == null || current.getType().isAir();
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime() / 1_000_000L;
        if (!prevEmpty && curEmpty) {
            emptySince.put(uuid, now); // 进入空手：起表
            return;
        }
        if (!prevEmpty || curEmpty || !isMeleeWeapon(current)) {
            return;
        }
        Long since = emptySince.remove(uuid);
        long heldEmptyMs = since == null ? Long.MAX_VALUE : now - since;
        if (heldEmptyMs >= config.lsMinEmptyMs()) {
            armedAt.put(uuid, now); // 空手 ≥1s 后换刀：龙闪武装（左键须 1 tick 内衔接）
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techStart(uuid,
                    org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN); // 公共 API：武装即启动
        }
    }

    // ============ 左键释放（须在换刀后 1 tick 内衔接） ============

    /** 武装后左键挥臂 → 扣 2 纸人放第一波 + 1s 后同位置同方向补第二波。 */
    @Override
    public void onLeftClick(Player player) {
        final UUID caster = player.getUniqueId();
        Long at = armedAt.remove(caster);
        if (at == null) {
            return;
        }
        if (System.nanoTime() / 1_000_000L - at > config.armConnectMs()) {
            // 换刀后未在 1 tick 内释放：算脱拍（武装作废，须重新空手换刀）
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(caster,
                    org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.CONNECT_TIMEOUT, 0);
            return;
        }
        if (paperDollManager.countPaperDolls(player) < config.lsPaperDollCost()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.4f);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techFail(caster,
                    org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN,
                    org.alpha.sekiroBedwar.api.TechniqueFailReason.INSUFFICIENT_RESOURCE, 0);
            return; // 纸人不足：不扣、解除本次武装（须重新空手换刀）
        }
        paperDollManager.consumePaperDolls(player, config.lsPaperDollCost());
        // 触发快照：位置与面朝方向锁定——第二波隔 1s 复用同一快照（玩家移动/转头不影响）
        Location base = player.getLocation().clone();
        Vector dir = base.getDirection().setY(0.0);
        if (dir.lengthSquared() < 1.0e-4) {
            dir = new Vector(0, 0, -1); // 俯视极端兜底（与风弹同口径）
        }
        dir.normalize();
        final Vector facing = dir;
        hitsByCaster.put(caster, 0);
        launchWaveSnapshot(base.clone(), facing, caster, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 期间死亡 / 离场均已被 clear 撤表（视作 Cancel），不再补第二波、不误发 Complete
            if (!plugin.isEnabled() || !hitsByCaster.containsKey(caster)) {
                return;
            }
            Player p = Bukkit.getPlayer(caster);
            if (p != null && p.isOnline() && !p.isDead()) {
                launchWaveSnapshot(base.clone(), facing, caster, 2);
            }
            // 双波放完 = 武技完成（命中数如实上报，未命中也算释放完成）
            Integer fired = hitsByCaster.remove(caster);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techComplete(caster,
                    org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN, 2, fired == null ? 0 : fired);
        }, Math.max(1L, config.lsSecondWaveDelayMs() / 50L));
    }

    /** 以快照参数发射一波（音波柱 + 铁砧落地音在释放者处播放）。 */
    private void launchWaveSnapshot(Location origin, Vector dir, UUID caster, int waveIndex) {
        spawnWave(origin, dir, caster, waveIndex);
        Player p = Bukkit.getPlayer(caster);
        if (p != null && p.isOnline()) {
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
        }
    }

    private void spawnWave(Location origin, Vector dir, UUID caster, int waveIndex) {
        DuelIsland island = resolveIsland(caster);
        waves.add(new Wave(origin, dir, caster, island,
                island == null ? config.lsFallbackRange() : duelConfig.innerRadius(), waveIndex));
    }

    // ============ 波推进 / 渲染 / 命中 ============

    private void tickWaves() {
        if (waves.isEmpty()) {
            return;
        }
        double speed = config.lsWaveSpeed();
        double hitRadius = config.lsHitRadius();
        double heightUp = config.lsHeightUp();
        Particle particle = config.lsParticle();
        for (Wave wave : new ArrayList<>(waves)) {
            wave.traveled += speed;
            Location front = wave.origin.clone().add(wave.dir.clone().multiply(wave.traveled));
            World world = wave.origin.getWorld();
            if (world == null) {
                waves.remove(wave);
                continue;
            }
            boolean outside = wave.island != null
                    ? wave.island.horizontalDistanceTo(front) > wave.boundary
                    : wave.traveled > wave.boundary;
            if (outside) {
                waves.remove(wave); // 触碰白圈 / 超距：特效与伤害判定同时结束
                continue;
            }
            // 渲染：波前竖直柱（5 层 × 横向 3 点）
            Vector side = new Vector(-wave.dir.getZ(), 0.0, wave.dir.getX());
            for (int dy = 0; dy <= 4; dy++) {
                double y = wave.origin.getY() + heightUp * dy / 4.0;
                for (double off = -0.8; off <= 0.81; off += 0.8) {
                    world.spawnParticle(particle,
                            front.getX() + side.getX() * off, y, front.getZ() + side.getZ() * off,
                            2, 0.05, 0.05, 0.05, 0.02);
                }
            }
            // 命中判定（玩家，除释放者；每波每人一次）
            for (Player target : Bukkit.getOnlinePlayers()) {
                UUID id = target.getUniqueId();
                if (id.equals(wave.caster) || target.isDead() || target.getGameMode() == GameMode.SPECTATOR
                        || !target.getWorld().equals(world) || wave.hit.contains(id)) {
                    continue;
                }
                Location loc = target.getLocation();
                double dx = loc.getX() - front.getX();
                double dz = loc.getZ() - front.getZ();
                if (dx * dx + dz * dz > hitRadius * hitRadius) {
                    continue;
                }
                if (loc.getY() > wave.origin.getY() + heightUp || loc.getY() + 1.8 < wave.origin.getY()) {
                    continue; // 身体与波柱 [origin.y, origin.y+heightUp] 无重叠
                }
                wave.hit.add(id);
                if (config.lsDamageHp() > 0) {
                    target.damage(config.lsDamageHp()); // 泛型直伤：不走格挡/弹反二次处理
                }
                if (config.lsDamageStance() > 0) {
                    stanceManager.reduceStance(id, config.lsDamageStance());
                }
                hitsByCaster.merge(wave.caster, 1, Integer::sum);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techHit(wave.caster,
                        org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN, wave.index, false, id);
            }
        }
    }

    /** 释放者当前所在（或刚离开的）进行中决斗的岛屿（白圈边界基准）；无则 null。 */
    private DuelIsland resolveIsland(UUID caster) {
        for (Duel duel : duelManager.getDuels()) {
            if (duel.getState() == DuelState.ENDING) {
                continue;
            }
            if (duel.contains(caster)) {
                return duel.getIsland();
            }
        }
        return null;
    }

    private static boolean isMeleeWeapon(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_SPEAR");
    }

    @Override
    public void onAttack(Player attacker, Player victim, boolean parried) {
        // 龙闪无近战连击逻辑（伤害走波判定），仅左键释放
    }

    @Override
    public void clear(UUID player, org.alpha.sekiroBedwar.api.TechniqueCancelReason reason) {
        boolean inFlight = hitsByCaster.remove(player) != null;
        if (armedAt.remove(player) != null || inFlight) {
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.techCancel(player,
                    org.alpha.sekiroBedwar.api.TechniqueId.LONG_SHAN, reason, inFlight ? 1 : 0);
        }
        emptySince.remove(player);
        waves.removeIf(w -> w.caster.equals(player));
    }

    @Override
    public void clearAll() {
        armedAt.clear();
        emptySince.clear();
        hitsByCaster.clear();
        waves.clear();
    }

    @Override
    public void shutdown() {
        waveTask.cancel();
        waves.clear();
    }

    /** 一波：触发快照（位置 / 方向 / 边界 / 波序）+ 前进进度 + 本波已命中集合。 */
    private static final class Wave {
        final Location origin;
        final Vector dir;
        final UUID caster;
        final DuelIsland island;   // null = 非决斗（按固定距离终止）
        final double boundary;      // island 水平距离上限 或 前进距离上限
        final int index;            // 波序（1 = 首波，2 = 补射波；公共 Hit 事件的 hitIndex）
        final Set<UUID> hit = new HashSet<>();
        double traveled;

        Wave(Location origin, Vector dir, UUID caster, DuelIsland island, double boundary, int index) {
            this.origin = origin;
            this.dir = dir;
            this.caster = caster;
            this.island = island;
            this.boundary = boundary;
            this.index = index;
        }
    }
}
