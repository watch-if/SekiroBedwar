package org.alpha.sekiroBedwar.windcharge;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 风弹管理器（独立模块）：忍具商店购买绑定风弹（{@code wind-charge.material} 默认
 * WIND_CHARGE，PDC 记录 owner；绑定物不可丢弃 / 入容器）。
 *
 * <p><b>投掷经济（复用既有机制，本模块不重复扣）</b>：风弹释放归为投掷物一类——
 * 每次投掷消耗 {@code paper-doll.throw.cost}(1) 纸人并退还物品；命中目标后
 * {@code paper-doll.teleport.window-ms} 内左键可再耗 1 纸人传送到目标身边。
 * 两条都由 paperdoll 模块驱动（其白名单不含 WIND_CHARGE，天然按 1 纸人计）。</p>
 *
 * <p><b>爆风墙（本模块核心）</b>：玩家释放（投掷）瞬间，在释放者面前生成一面半椭圆的面——
 * 面朝方向为短轴 {@code depth}(1.5 格)、两侧各长轴 {@code width}(2.5 格)、高
 * {@code height}(3 格)；圆心与朝向在释放瞬间锁定（不随玩家移动，与恐怖区同口径）。
 * TNT 爆炸粒子从释放者左手侧向右手侧扫过 {@code sweep-seconds}(1s)，扫完后整面停留
 * {@code linger-seconds}(1s)。粒子存在期间（扫过 + 停留全程），触碰到墙面
 * （{@code touch-radius} 判定，释放者除外）的玩家 {@code disable-seconds}(0.5s) 内
 * <b>不能防御与攻击</b>：</p>
 * <ul>
 *   <li>不能防御 = 强制收盾 + 窗口内禁止再举盾（{@code setCooldown}，主/副手盾与剑都冷却，
 *       剑格挡一并禁用）+ 决斗内进入「无法格挡」状态（{@code StanceManager.disableBlocking}，
 *       阻断完美弹反 / 盾牌弹反授予；决斗外该调用自然无操作）；</li>
 *   <li>不能攻击 = 其作为伤害来源的一切对实体伤害（近战 + 投射物）在 LOWEST 优先级被取消
 *       （早于弹反 HIGH / 普通格挡 NORMAL，被取消的攻击不进入架势换算）。</li>
 * </ul>
 * <p>同一玩家 {@code no-stack-seconds}(6s) 内效果不可叠加（不重复施加）。
 * 不限 ACTIVE 决斗：任何场景释放风弹都有爆风墙。</p>
 */
public final class WindChargeManager {

    private final SekiroBedwar plugin;
    private final WindChargeConfig config;
    private final StanceManager stanceManager;
    private final SekiroShopManager shop;
    private final NamespacedKey ownerKey;
    private final WindChargeListener listener;

    /** 存活中的爆风墙（释放瞬间锁定几何，到期移除）。 */
    private final List<GustWall> walls = new ArrayList<>();
    /** 禁攻窗口：玩家 → 不能攻击的截止时刻（毫秒）。 */
    private final Map<UUID, Long> attackDisabledUntil = new HashMap<>();
    /** 不可叠加窗口：玩家 → 最近一次被施加效果的时刻（毫秒）。 */
    private final Map<UUID, Long> lastTouchedAt = new HashMap<>();
    private BukkitTask tickTask;

    public WindChargeManager(SekiroBedwar plugin, WindChargeConfig config,
                             StanceManager stanceManager, SekiroShopManager shop) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.shop = shop;
        this.ownerKey = new NamespacedKey(plugin, "wind_charge_owner");
        this.listener = new WindChargeListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> clearPlayer(ev.getPlayer().getUuid())); // 离局清状态（下局重新购买）
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        shop.register(new ShopItem("wind-charge", 43, this::renderItem, this::buy));
        plugin.getLogger().info("风弹已启用：半椭圆 " + num(config.depth()) + "×" + num(config.width())
                + "×" + num(config.height()) + " 扫过=" + num(config.sweepMs() / 1000.0) + "s 停留="
                + num(config.lingerMs() / 1000.0) + "s 触碰封印=" + num(config.disableSeconds())
                + "s（" + num(config.noStackMs() / 1000.0) + "s 不可叠加）");
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        walls.clear();
        attackDisabledUntil.clear();
        lastTouchedAt.clear();
    }

    /** 离线 / 离局清理（墙按自身寿命自然消散，不按人移除）。 */
    public void clearPlayer(UUID uuid) {
        attackDisabledUntil.remove(uuid);
        lastTouchedAt.remove(uuid);
    }

    // ============ 释放：生成爆风墙 ============

    /**
     * 风弹释放瞬间（投掷实际发生；纸人消耗与退还在 PaperDollListener 的 NORMAL 阶段已完成，
     * 本触发在其后的高优先级）：以释放者脚位为圆心、面朝方向为短轴，锁定生成一面半椭圆爆风墙。
     */
    public void handleLaunch(Player caster) {
        if (!org.alpha.sekiroBedwar.combat.BwScope.inGame(caster.getUniqueId())) {
            return; // 玩法只在 BedWars 对局内触发（对局外风弹按原版）
        }
        Location base = caster.getLocation();
        Vector dir = base.getDirection().setY(0.0);
        if (dir.lengthSquared() < 1.0e-4) {
            dir = new Vector(0, 0, -1); // 俯视极端时水平分量趋零，兜底朝北（与雾璃鸦同口径）
        }
        dir.normalize();
        double fx = dir.getX();
        double fz = dir.getZ();
        double lx = fz;
        double lz = -fx; // 释放者左手方向（面朝南 (0,0,1) 时左手为 +X 东）
        walls.add(new GustWall(caster.getUniqueId(), base.getWorld(),
                base.getX(), base.getY(), base.getZ(), fx, fz, lx, lz,
                config.depth(), config.width(), config.height(), config.points(),
                now(), config.sweepMs(), config.lingerMs()));
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(caster.getUniqueId(),
                org.alpha.sekiroBedwar.api.ToolId.WIND_CHARGE, null,
                org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
    }

    // ============ 每 tick：渲染 + 触碰判定 + 惰性清理 ============

    private void tick() {
        long now = now();
        if (!walls.isEmpty()) {
            walls.removeIf(wall -> now >= wall.untilMs);
            for (GustWall wall : walls) {
                renderWall(wall, now);
                detectTouches(wall, now);
            }
        }
        attackDisabledUntil.values().removeIf(until -> until < now);
        lastTouchedAt.values().removeIf(t -> now - t > config.noStackMs());
    }

    /** 渲染：扫过阶段随进度揭示新弧列（已揭示列逐 tick 重画保持可见），停留阶段整面重画。 */
    private void renderWall(GustWall wall, long now) {
        World world = wall.world;
        if (world == null) {
            return;
        }
        double progress = now >= wall.sweepEndMs ? 1.0
                : (wall.sweepMs <= 0 ? 1.0 : (double) (now - wall.castMs) / wall.sweepMs);
        int revealed = (int) Math.ceil(Math.max(0.0, Math.min(1.0, progress)) * wall.points);
        for (int i = 0; i < revealed; i++) {
            double px = wall.px(i);
            double pz = wall.pz(i);
            for (int k = 0; k < wall.heightSamples; k++) {
                world.spawnParticle(config.particle(), px, wall.baseY + wall.heightOffset(k), pz,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** 触碰判定：玩家身体与墙高带 [baseY, baseY+height] 重叠，且到弧线采样点最短水平距离
     * ≤ {@code touch-radius} 即受效（释放者本人、死亡、旁观、跨世界排除）。 */
    private void detectTouches(GustWall wall, long now) {
        double r = config.touchRadius();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(wall.caster) || player.isDead()
                    || player.getGameMode() == GameMode.SPECTATOR
                    || !player.getWorld().equals(wall.world)) {
                continue;
            }
            Location loc = player.getLocation();
            if (loc.getY() >= wall.baseY + wall.height || loc.getY() + 1.8 <= wall.baseY) {
                continue;
            }
            double dx = loc.getX() - wall.baseX;
            double dz = loc.getZ() - wall.baseZ;
            double u = dx * wall.lx + dz * wall.lz; // 侧向坐标（+ = 释放者左手）
            double v = dx * wall.fx + dz * wall.fz; // 面朝方向坐标
            if (Math.abs(u) > wall.width + r || v < -r || v > wall.depth + r) {
                continue; // 粗筛：超出弧线包络矩形
            }
            double minSq = Double.MAX_VALUE;
            for (int i = 0; i < wall.points; i++) {
                double du = u - wall.us[i];
                double dv = v - wall.vs[i];
                double sq = du * du + dv * dv;
                if (sq < minSq) {
                    minSq = sq;
                }
            }
            if (minSq <= r * r) {
                applyTouch(player, now);
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(wall.caster,
                        org.alpha.sekiroBedwar.api.ToolId.WIND_CHARGE, player.getUniqueId(),
                        org.alpha.sekiroBedwar.api.ToolUseResult.SUCCESS);
            }
        }
    }

    /** 施加「不能防御与攻击」：不可叠加窗口（默认 6s）内再次触碰直接忽略；对局外玩家不受封。 */
    private void applyTouch(Player player, long now) {
        if (!org.alpha.sekiroBedwar.combat.BwScope.inGame(player.getUniqueId())) {
            return; // 玩法只在 BedWars 对局内生效
        }
        UUID uuid = player.getUniqueId();
        Long last = lastTouchedAt.get(uuid);
        if (last != null && now - last < config.noStackMs()) {
            return;
        }
        lastTouchedAt.put(uuid, now);
        attackDisabledUntil.put(uuid, now + config.disableMs());
        if (config.disableSeconds() <= 0) {
            return;
        }
        // 不能防御：决斗内进入「无法格挡」（阻断弹反/盾牌弹反授予；决斗外无操作）+
        // 强制收盾并禁止再举盾：主 / 副手的盾与剑逐件冷却（blocks_attacks 剑格挡同样失效）
        stanceManager.disableBlocking(uuid, config.disableSeconds());
        int ticks = Math.max(1, (int) Math.ceil(config.disableSeconds() * 20.0));
        blockItem(player, player.getInventory().getItemInMainHand(), ticks);
        blockItem(player, player.getInventory().getItemInOffHand(), ticks);
    }

    /** 主/副手的盾与剑逐件冷却（Bukkit 无「停止持盾」API，冷却即强制 isBlocking() 为假；
     * blocks_attacks 剑格挡同样失效）。 */
    private static void blockItem(Player player, ItemStack item, int ticks) {
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == Material.SHIELD || type.name().endsWith("_SWORD")) {
            player.setCooldown(type, ticks);
        }
    }

    // ============ 不能攻击 ============

    /** 禁攻窗口内玩家作为伤害来源（近战或投射物射手）的一切对实体伤害被取消。 */
    public void handleAttackAttempt(EntityDamageByEntityEvent event) {
        if (attackDisabledUntil.isEmpty()) {
            return;
        }
        Player attacker = CombatUtils.resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        Long until = attackDisabledUntil.get(attacker.getUniqueId());
        if (until == null || now() >= until) {
            return;
        }
        event.setCancelled(true);
    }

    // ============ 绑定物品识别 ============

    /** 绑定风弹识别（材质 + PDC owner 标记）。 */
    public boolean isWindCharge(ItemStack item) {
        if (item == null || item.getType() != config.material() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private ItemStack makeWindCharge(Player player) {
        ItemStack item = new ItemStack(config.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + config.name());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    // ============ 忍具商店 GUI ============

    /** 购买入口（GUI 点击路由）：扣费 → 发绑定风弹（无购买上限；死亡全清后可再购）。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.priceCurrency()), config.priceAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        player.getInventory().addItem(makeWindCharge(player));
        player.sendMessage("§a已购得风弹！");
    }

    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7掷出（耗 1 纸人）瞬间面前掀起爆风墙");
        lore.add("§7半椭圆：面朝 " + num(config.depth()) + " × 两侧各 " + num(config.width())
                + " × 高 " + num(config.height()));
        lore.add("§7左→右扫过 " + num(config.sweepMs() / 1000.0) + "s，停留 "
                + num(config.lingerMs() / 1000.0) + "s");
        lore.add("§7触碰者 " + num(config.disableSeconds()) + "s 不能防御与攻击");
        lore.add("§7（" + num(config.noStackMs() / 1000.0) + "s 内不叠加）");
        lore.add(ShopCurrency.priceLore(config.priceCurrency(), config.priceAmount()));
        return SekiroShopManager.icon(config.material(), "§b" + config.name(), lore);
    }

    // ============ 工具 ============

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 数值展示（去尾零）：3.0 → 3，1.5 → 1.5。 */
    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * 一面爆风墙：释放瞬间锁定圆心、朝向与几何（不随玩家移动）。
     *
     * <p>局部坐标：u = 侧向（+ 为释放者左手）、v = 面朝方向。弧线采样 φ ∈ [0, π]，
     * i = 0 起于左手端 (u=+width, v=0)、i = N-1 止于右手端 (u=-width, v=0)，
     * 弧顶 (u=0, v=depth) 在正中——扫过按 i 递增即「从左向右」。世界坐标 =
     * base + u·(lx,lz) + v·(fx,fz)。垂直采样 heightSamples 层均匀铺满 [0, height]。</p>
     */
    private static final class GustWall {
        final UUID caster;
        final World world;
        final double baseX;
        final double baseY;
        final double baseZ;
        final double fx;
        final double fz;
        final double lx;
        final double lz;
        final double depth;
        final double width;
        final double height;
        final int points;
        final double[] us;
        final double[] vs;
        final int heightSamples;
        final long castMs;
        final long sweepMs;
        final long sweepEndMs;
        final long untilMs;

        GustWall(UUID caster, World world, double baseX, double baseY, double baseZ,
                 double fx, double fz, double lx, double lz,
                 double depth, double width, double height, int points,
                 long castMs, long sweepMs, long lingerMs) {
            this.caster = caster;
            this.world = world;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.fx = fx;
            this.fz = fz;
            this.lx = lx;
            this.lz = lz;
            this.depth = depth;
            this.width = width;
            this.height = height;
            this.points = points;
            this.us = new double[points];
            this.vs = new double[points];
            for (int i = 0; i < points; i++) {
                double phi = Math.PI * i / (points - 1);
                this.us[i] = width * Math.cos(phi);   // +width（左端）→ -width（右端）
                this.vs[i] = depth * Math.sin(phi);   // 0 → depth（弧顶）→ 0
            }
            this.heightSamples = Math.max(1, (int) Math.round(height));
            this.castMs = castMs;
            this.sweepMs = sweepMs;
            this.sweepEndMs = castMs + sweepMs;
            this.untilMs = castMs + sweepMs + lingerMs;
        }

        double px(int i) {
            return baseX + lx * us[i] + fx * vs[i];
        }

        double pz(int i) {
            return baseZ + lz * us[i] + fz * vs[i];
        }

        double heightOffset(int k) {
            return height * (k + 0.5) / heightSamples;
        }
    }
}
