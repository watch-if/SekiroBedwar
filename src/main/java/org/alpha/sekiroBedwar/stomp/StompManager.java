package org.alpha.sekiroBedwar.stomp;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.api.ToolId;
import org.alpha.sekiroBedwar.api.ToolUseResult;
import org.alpha.sekiroBedwar.combat.BwScope;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelAreaGuard;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.mystery.MysteryManager;
import org.alpha.sekiroBedwar.parry.ParryMarker;
import org.alpha.sekiroBedwar.shop.BuyContext;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
import org.alpha.sekiroBedwar.shop.ShopCurrency;
import org.alpha.sekiroBedwar.shop.ShopItem;
import org.alpha.sekiroBedwar.stance.StanceBreakManager;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.screamingsandals.bedwars.api.events.PlayerLeaveEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 踩头 / 重锤风暴（{@code stomp/} 包，独立模块，2026-09-20 新增）。
 *
 * <p><b>原型</b>：只狼的踩头借力 + MC 1.21「重锤 + 风爆」在下落 &gt; 1.5 格时的近战风爆。
 * 忍具商店购买（默认 10 铁，一次性，本局有效）并<b>穿着护甲</b>后获得。</p>
 *
 * <p><b>触发（三项同时满足）</b>：
 * <ol>
 *   <li>玩家处于<b>滞空</b>（{@code !isOnGround}）；</li>
 *   <li>与另一名玩家的<b>碰撞箱间距 &lt; {@code trigger-distance}}(0.8) 格（水平）且垂直投影重叠；</li>
 *   <li>按下<b>空格</b>（{@link PlayerInputEvent} 取 {@code isJump()} 的<b>上升沿</b>，
 *       按住不放不算新触发）。</li>
 * </ol>
 *
 * <p><b>归属</b>：两人中<b>相对高度较低者被踩</b>（高位者为踩者），因此本模块不预设攻守，
 * 同一个按键在“我更高”时是踩人、在“我更低”时是被踩的一方起跳借力。</p>
 *
 * <p><b>伤害方式按相对下落高度分层</b>：
 * <ul>
 *   <li>下落高度 &le; {@code fall-threshold}(1.5) 格 → <b>轻踩</b>：只扣受击方
 *       {@code light-stance}(1) 架势，不产生伤害事件；</li>
 *   <li>下落高度 &gt; 1.5 格 → <b>重踩</b>：在踩者位置产生风爆（踩者与周围生物被向上弹起）
 *       + 走<b>正常命中管线</b>造成 {@code heavy-damage}(1) 基础伤害（重锤只有 1 基础伤害的口径），
 *       即<b>可被完美弹反、可被普通格挡、可被斧破盾</b>，架势换算由 block/parry 模块照常处理。</li>
 * </ul>
 *
 * <p><b>位移</b>：
 * <ul>
 *   <li>未被弹反 → 踩者按方向键输入（前后左右 + 朝向）<b>借力弹走</b>
 *       （{@code bounce-horizontal} 水平 + {@code bounce-up} 向上）；无方向输入则只有向上分量；</li>
 *   <li>被<b>完美弹反</b> → 位移方向反转：踩者朝<b>远离被踩者</b>的方向吃
 *       {@code parried-knockback}(击退 II 量级) 位移，落点可能更靠后而被推出白圈 / 掉进虚空
 *       ——这是该招式的风险所在。</li>
 * </ul>
 * 两种位移都经 {@link DuelAreaGuard#recordKnockback(Player, Vector)} 显式登记，
 * 走「击退位移豁免」口径（决斗中被弹反真的会被打飞出去，而不是被拉回圈内）。</p>
 *
 * <p><b>作用域</b>：只在 BedWars 对局内且踩者与目标处于同一场 <b>ACTIVE 决斗</b>时触发
 * （对局外一律原版）。不推进秘传四式、不推进锈丸/炎上连段（只做自己的伤害/架势/位移）。</p>
 */
public final class StompManager implements Listener {

    private final SekiroBedwar plugin;
    private final StompConfig config;
    private final StanceManager stanceManager;
    private final DuelManager duelManager;
    private final DuelAreaGuard duelAreaGuard;
    private final MysteryManager mysteryManager;
    private final SekiroShopManager shop;
    private final ParryMarker parryMarker;
    private final StanceBreakManager stanceBreakManager;

    /** 本局已购买踩头的玩家（离局清除；配合「穿着护甲」判定生效）。 */
    private final Set<UUID> purchased = new HashSet<>();

    /** 玩家退出：清理输入与滞空簿记（离局由 BW PlayerLeaveEvent 清购买记录）。 */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        peakY.remove(id);
        usedThisAirtime.remove(id);
        jumpHeld.remove(id);
    }

    /** 逐 tick 滞空簿记：最高点 Y（用于按 tick 计数算下落高度，不依赖 getFallDistance）。 */
    private final Map<UUID, Double> peakY = new HashMap<>();
    /** 本次滞空是否已触发过（一次滞空一次；落地重置）。 */
    private final Set<UUID> usedThisAirtime = new HashSet<>();

    private BukkitTask tickTask;

    public StompManager(SekiroBedwar plugin, StompConfig config, StanceManager stanceManager,
                        StanceBreakManager stanceBreakManager, DuelManager duelManager,
                        DuelAreaGuard duelAreaGuard, MysteryManager mysteryManager,
                        SekiroShopManager shop, ParryMarker parryMarker) {
        this.plugin = plugin;
        this.config = config;
        this.stanceManager = stanceManager;
        this.stanceBreakManager = stanceBreakManager;
        this.duelManager = duelManager;
        this.duelAreaGuard = duelAreaGuard;
        this.mysteryManager = mysteryManager;
        this.shop = shop;
        this.parryMarker = parryMarker;
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PlayerLeaveEvent.handle(plugin, ev -> clearAll(ev.getPlayer().getUuid())); // 离局全清（下局重新购买）
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        shop.register(new ShopItem("stomp", 45, this::renderItem, this::buy));
        plugin.getLogger().info("踩头已启用：价格=" + config.priceAmount() + " "
                + config.priceCurrency() + " 触发距离=" + config.triggerDistance()
                + " 分层=" + config.fallThreshold() + "格");
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        clearAll();
    }

    /** 全清（离局 / 禁用）。 */
    public void clearAll() {
        purchased.clear();
        peakY.clear();
        usedThisAirtime.clear();
        jumpHeld.clear();
    }

    public void clearAll(UUID uuid) {
        purchased.remove(uuid);
        peakY.remove(uuid);
        usedThisAirtime.remove(uuid);
        jumpHeld.remove(uuid);
    }

    // ==================== 滞空簿记 ====================

    /**
     * 逐 tick 维护「本次滞空的最高点」：下落高度 = 峰值 Y − 当前 Y。
     * 落地即把峰值重置回脚位（下一次起跳重新累计），因此不依赖会被落地 / 入水清零的
     * {@code getFallDistance()}。
     */
    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!BwScope.inGame(id) || player.isDead() || player.isGliding()) {
                peakY.remove(id);
                usedThisAirtime.remove(id);
                continue;
            }
            double y = player.getLocation().getY();
            if (player.isOnGround()) {
                peakY.put(id, y);
                usedThisAirtime.remove(id); // 落地 = 重置「一次滞空一次」额度
            } else {
                Double peak = peakY.get(id);
                if (peak == null || y > peak) {
                    peakY.put(id, y);
                }
            }
        }
    }

    // ==================== 触发 ====================

    /**
     * 空格上升沿：滞空中按下跳跃键 → 尝试踩头。
     *
     * <p>输入是<b>状态</b>不是按键：{@code PlayerInputEvent} 只在客户端输入变化时派发，
     * 按住不放不会反复派发，因此这里额外用 {@link #jumpHeld} 记录上一状态，只认
     * false → true 的<b>上升沿</b>。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("UnstableApiUsage")
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean jump = event.getInput().isJump();
        boolean wasHeld = jumpHeld.contains(id);
        if (!jump) {
            jumpHeld.remove(id);
            return;
        }
        if (wasHeld) {
            return; // 按住不放：不是新的按下
        }
        jumpHeld.add(id);
        tryStomp(player, event.getInput());
    }

    /** 空格按住状态（上升沿检测；离局/禁用清空）。 */
    private final Set<UUID> jumpHeld = new HashSet<>();

    @SuppressWarnings("UnstableApiUsage")
    private void tryStomp(Player presser, org.bukkit.Input input) {
        UUID id = presser.getUniqueId();
        // 只有「已购买 + 穿着护甲」的玩家按键才有效；被踩的一方由本次判定决定
        if (!hasStomp(presser) || presser.isOnGround() || presser.isDead() || presser.isGliding()) {
            return;
        }
        // 举盾 / 下蹲状态下不发动：① 避免空格与盾牌格挡状态互相污染
        //（格挡中受击会被 ParryManager 判为完美弹反而消费弹反标记，导致踩头被误判成「被弹反」）；
        // ② 与识破（下蹲）的按键语义不冲突。
        if (input.isSneak() || presser.isBlocking()) {
            return;
        }
        if (config.oncePerAirtime() && usedThisAirtime.contains(id)) {
            return;
        }
        Location loc = presser.getLocation();
        if (loc.getWorld() == null) {
            return;
        }
        Optional<Duel> opt = duelManager.getDuel(id);
        if (opt.isEmpty() || opt.get().getState() != DuelState.ACTIVE) {
            return;
        }
        Duel duel = opt.get();
        Player other = opponentOf(duel, id);
        if (other == null || other.isDead() || !other.isOnline()
                || other.getWorld() != loc.getWorld()) {
            return;
        }
        // 更高者才是踩者：按键者更高 → 我踩对方；按键者更低 → 对方踩我（我来当垫脚）。
        boolean presserHigher = loc.getY() > other.getLocation().getY();
        Player stomper = presserHigher ? presser : other;
        Player victim = presserHigher ? other : presser;
        // 踩者必须同样具备资格（更高者是对方时，对方必须也已购买且穿着护甲）
        if (!hasStomp(stomper)) {
            return;
        }
        if (!isStompContact(stomper, victim)) {
            return;
        }
        // 一次滞空一次：踩者与被踩者双方都消耗额度（防同一滞空反复触发 / 即时二次踩）
        usedThisAirtime.add(stomper.getUniqueId());
        usedThisAirtime.add(victim.getUniqueId());

        double fall = fallHeightOf(stomper);
        if (fall > config.fallThreshold()) {
            heavyStomp(stomper, victim, input);
        } else {
            lightStomp(stomper, victim, input);
        }
    }

    /**
     * 触发判定（碰撞箱）：水平间距 &lt; 阈值 且 垂直投影重叠。
     *
     * <p>用 {@link BoundingBox}（服务端真实碰撞箱）而非中心距，紧贴「碰撞箱是用来判定
     * 如何触发的」这一口径；垂直方向只要求投影重叠，不参与阈值——垂直关系由
     * 「谁更低谁被踩」与「下落高度分层」各自处理。</p>
     */
    private boolean isStompContact(Player stomper, Player victim) {
        BoundingBox mine = stomper.getBoundingBox();
        BoundingBox other = victim.getBoundingBox();
        if (mine.getMaxY() <= other.getMinY() + 1.0e-6 || other.getMaxY() <= mine.getMinY() + 1.0e-6) {
            return false; // 垂直投影不重叠（上下完全错开）
        }
        return horizontalGap(mine, other) < config.triggerDistance();
    }

    private static double horizontalGap(BoundingBox a, BoundingBox b) {
        double dx = Math.max(0.0, Math.max(b.getMinX() - a.getMaxX(), a.getMinX() - b.getMaxX()));
        double dz = Math.max(0.0, Math.max(b.getMinZ() - a.getMaxZ(), a.getMinZ() - b.getMaxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 该玩家的下落高度（本次滞空峰值 − 当前脚位；上升期 = 0）。 */
    private double fallHeightOf(Player player) {
        Double peak = peakY.get(player.getUniqueId());
        if (peak == null) {
            return 0.0;
        }
        return Math.max(0.0, peak - player.getLocation().getY());
    }

    /** 决斗对手（按 UUID 取；离线返回 null）。 */
    private static Player opponentOf(Duel duel, UUID id) {
        if (!duel.contains(id)) {
            return null;
        }
        return duel.getPlayerAUuid().equals(id) ? duel.getPlayerB() : duel.getPlayerA();
    }

    private boolean hasStomp(Player player) {
        return purchased.contains(player.getUniqueId()) && wearingArmor(player);
    }

    /** 要求穿甲：四件装备栏任一为护甲材料。 */
    private static boolean wearingArmor(Player player) {
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && isArmor(stack.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArmor(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || material == Material.TURTLE_HELMET || material == Material.ELYTRA;
    }

    // ==================== 两种落点 ====================

    /**
     * 轻踩（下落 &le; 1.5 格）：只扣架势、不派发伤害事件。
     *
     * <p><b>风爆与竖直位移不分层</b>（用户口径）：与重锤附魔「风爆」打中生物一样，
     * 只要踩中就在踩者位置引发风爆（竖直弹起向量 + 粒子/音效），无论下落多高——
     * 下落高度只决定伤害方式。因此向上弹起的力度按下落高度递增（下落越高弹得越高），
     * 轻踩时等于基础小跳。</p>
     *
     * <p>「伤害与架势也能被完美弹反弹开」对轻踩同样成立：轻踩不派发伤害事件，
     * 因此改用 {@link ParryMarker} 询问「受击方是否刚刚完美弹反成功」——是则整击作废
     * （不扣架势、不弹走，改吃反向击退，架势惩罚由 ParryManager 结算）。</p>
     */
    private void lightStomp(Player attacker, Player victim, @SuppressWarnings("UnstableApiUsage") org.bukkit.Input input) {
        UUID victimId = victim.getUniqueId();
        if (victim.isBlocking() && parryMarker.consume(victimId)) {
            // 仅当受击方确实在格挡时才查弹反标记：避免把「踩者自己上一次弹反的残留标记」
            // 误读成「这次踩头被弹反」。
            applyParriedKnockback(attacker, victim);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                    ToolId.STOMP, victimId, ToolUseResult.REJECTED);
            return;
        }
        windBurst(attacker, input);
        // 崩条判定必须在扣架势【之前】：轻踩只扣固定架势、不派发伤害事件，若不显式走这一步
        // 就会出现「临界中被踩头却不崩条」。判定口径与普通近战命中完全一致（近战未弹反 + 临界）。
        stanceBreakManager.onMeleeHitWithoutEvent(attacker, victim, victim.getHealth());
        // 架势扣在【受击方】身上：走 reduceStanceBy(dealer, victim, amount) 统一入口，
        // 夜叉戮糖等「造成方架势伤害」倍率自动生效（reduceStance 是扣自己，用错会变成踩者自扣）。
        stanceManager.reduceStanceBy(attacker.getUniqueId(), victimId, config.lightStance());
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 1.0f, 1.2f);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                ToolId.STOMP, victimId, ToolUseResult.SUCCESS);
    }

    /**
     * 重踩（下落 &gt; 1.5 格）：风爆 + 走正常命中管线造成 1 基础伤害。
     *
     * <p>伤害走 {@code victim.damage(damage, attacker)} 的原版事件路径，因此
     * 完美弹反 / 普通格挡 / 斧破盾全部由既有模块照常处理；伤害与架势绝不在此重复结算。
     * 是否被弹反用 {@link ParryMarker} 判定，而不是「事件被取消」——后者另有连续弹反
     * 封印一个来源，会把「被封印」误判成「被弹反」。</p>
     */
    private void heavyStomp(Player attacker, Player victim, @SuppressWarnings("UnstableApiUsage") org.bukkit.Input input) {
        // 风爆视觉 + 竖直弹起（下落越深弹得越高）
        windBurst(attacker, input);

        UUID victimId = victim.getUniqueId();
        parryMarker.consume(victimId); // 清掉历史标记，避免上一次弹反残留被误读
        victim.damage(config.heavyDamage(), attacker);
        boolean parried = parryMarker.consume(victimId);

        if (parried) {
            applyParriedKnockback(attacker, victim);
            org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                    ToolId.STOMP, victimId, ToolUseResult.REJECTED);
            return;
        }

        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.toolUse(attacker.getUniqueId(),
                ToolId.STOMP, victimId, ToolUseResult.SUCCESS);
    }

    /**
     * 风爆：在踩者位置产生竖直弹起向量 + 粒子 / 音效，半径内的非玩家生物一并向上弹起。
     *
     * <p>与重锤附魔风爆同口径——<b>每次踩中都触发、与下落高度无关</b>（下落高度只决定
     * 伤害方式）；弹起力度按本次下落高度递增（{@code wind-burst-up-base} + 下落格数 ×
     * {@code wind-burst-up-per-block}，封顶 {@code wind-burst-up-max}）——高处砸下弹得更高，
     * 贴地踩就是一个小跳。水平分量为方向键借力（无输入则只有竖直分量）。</p>
     *
     * <p>被完美弹反时不走这里：位移改为反向击退（见 {@link #applyParriedKnockback}）。</p>
     */
    @SuppressWarnings("UnstableApiUsage")
    private void windBurst(Player attacker, org.bukkit.Input input) {
        Location loc = attacker.getLocation();
        double up = windBurstUp(attacker);
        mysteryManager.cancelKnockbackGuard(attacker.getUniqueId()); // 断掉防击退护身，保证位移真实生效
        Vector velocity = attacker.getVelocity().clone();
        Vector direction = inputDirection(attacker, input);
        if (direction != null) {
            velocity.setX(direction.getX() * config.bounceHorizontal());
            velocity.setZ(direction.getZ() * config.bounceHorizontal());
            duelAreaGuard.recordKnockback(attacker, direction); // 登记为击退位移（豁免拉回，可被打出白圈）
        }
        velocity.setY(up);
        attacker.setVelocity(velocity);

        double radius = config.windBurstRadius();
        if (radius > 0.0 && loc.getWorld() != null) {
            for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                if (!(entity instanceof LivingEntity living) || entity.equals(attacker) || entity instanceof Player) {
                    continue;
                }
                Vector v = living.getVelocity().clone();
                v.setY(up);
                living.setVelocity(v);
            }
        }
        loc.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, loc.clone().add(0.0, 0.1, 0.0),
                1, 0.0, 0.0, 0.0, 0.0);
        loc.getWorld().playSound(loc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.0f);
    }

    /** 风爆竖直弹起速度：基础值 + 下落格数 × 每格增量，封顶可配。 */
    private double windBurstUp(Player attacker) {
        double up = config.windBurstUpBase() + fallHeightOf(attacker) * config.windBurstUpPerBlock();
        return Math.min(up, config.windBurstUpMax());
    }

    /**
     * 被完美弹反：位移方向反转——踩者朝远离被踩者的方向吃击退 II 量级位移。
     * 落点可能更靠后（被推出白圈 / 掉进虚空），是该招式的风险来源；同时按完美弹反口径
     * 结算架势（由 ParryManager 负责），这里只补位移。
     */
    private void applyParriedKnockback(Player attacker, Player victim) {
        Vector away = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0.0);
        if (away.length() < 1.0e-6) {
            away = victim.getLocation().getDirection().clone().setY(0.0);
        }
        if (away.length() < 1.0e-6) {
            away = new Vector(0.0, 0.0, 1.0);
        }
        away.normalize();
        duelAreaGuard.recordKnockback(attacker, away);
        Vector velocity = attacker.getVelocity().clone();
        velocity.setX(away.getX() * config.parriedKnockback());
        velocity.setZ(away.getZ() * config.parriedKnockback());
        velocity.setY(config.parriedUp());
        mysteryManager.cancelKnockbackGuard(attacker.getUniqueId());
        attacker.setVelocity(velocity);
    }

    /** 方向键输入 → 水平单位向量（前后左右 + 朝向；无输入返回 null）。 */
    @SuppressWarnings("UnstableApiUsage")
    private static Vector inputDirection(Player player, org.bukkit.Input input) {
        double forward = (input.isForward() ? 1.0 : 0.0) - (input.isBackward() ? 1.0 : 0.0);
        double strafe = (input.isLeft() ? 1.0 : 0.0) - (input.isRight() ? 1.0 : 0.0);
        if (Math.abs(forward) < 1.0e-6 && Math.abs(strafe) < 1.0e-6) {
            return null;
        }
        double yaw = Math.toRadians(player.getLocation().getYaw());
        Vector look = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vector left = new Vector(-look.getZ(), 0.0, look.getX());
        Vector direction = look.multiply(forward).add(left.multiply(strafe));
        if (direction.length() < 1.0e-6) {
            return null;
        }
        return direction.normalize();
    }

    // ==================== 忍具商店 GUI ====================

    /** 购买入口（GUI 点击路由）：扣费 → 本局解锁踩头（配合穿甲生效，换甲不失效）。 */
    public void buy(BuyContext ctx) {
        Player player = ctx.player();
        if (player == null || !ctx.bwPlayer().isInGame()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (purchased.contains(id)) {
            player.sendMessage("§e本局已解锁踩头，无需重复购买。");
            return;
        }
        if (!ShopCurrency.deduct(player, ShopCurrency.of(config.priceCurrency()), config.priceAmount())) {
            player.sendMessage("§c购买失败：货币不足！");
            return;
        }
        purchased.add(id);
        player.sendMessage("§a已解锁踩头·重锤风暴！穿上护甲后滞空贴身按空格即可发动。");
    }

    private ItemStack renderItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7滞空贴身（<" + trim(config.triggerDistance()) + "格）按空格发动");
        lore.add("§7我更高：踩头借力弹走（方向键决定方向）");
        lore.add("§7下落 >" + trim(config.fallThreshold()) + "格：1 伤害 + 风爆");
        lore.add("§7下落 ≤" + trim(config.fallThreshold()) + "格：仅 " + trim(config.lightStance()) + " 架势");
        lore.add("§c可被完美弹反：被弹反则位移反转成击退");
        lore.add("§7需穿着护甲；本局有效，换甲不失效");
        lore.add(ShopCurrency.priceLore(config.priceCurrency(), config.priceAmount()));
        if (viewer != null && purchased.contains(viewer.getUniqueId())) {
            lore.add("§e▶ 已解锁");
        }
        ItemStack item = makeIcon(config.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b踩头·重锤风暴");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 创造图标：重锤 + 风爆附魔（发光）。 */
    static ItemStack makeIcon(Material material) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
        return item;
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
