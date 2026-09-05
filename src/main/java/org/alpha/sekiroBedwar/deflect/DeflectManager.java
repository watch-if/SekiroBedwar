package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 盾牌弹反（独立模块）：主手举盾消耗纸人 → 举盾后一段时间全部按完美弹反窗口处理 →
 * 窗口结束强制解除举盾。
 *
 * <p><b>触发</b>：主手持盾右键（{@code PlayerInteractEvent}，HAND 槽 + SHIELD）。右键事件
 * 后 <b>1 tick 延迟确认</b>——确认玩家确实处于 {@code isBlocking()}（真举盾）才扣
 * {@code deflect.paper-doll-cost} 纸人并开窗，防止“点了右键但没举成盾”（点容器方块等）误扣；
 * 已处于弹反窗口内再次右键不重复扣费。</p>
 *
 * <p><b>窗口语义</b>：{@code deflect.deflect-window-ms}（默认 2000ms）内来袭的
 * <b>近战命中一律按完美弹反</b>处理（由 {@code ParryManager} 查询 {@link #isDeflecting}
 * 强制走完美弹反分支——免伤免击退 + 攻击方架势 −= Dbase×parry-attacker-multiplier +
 * 反馈音效 + 封印计数 + 崩条评估 + 巴之雷钩子，与普通完美弹反完全一致）。与普通完美弹反的
 * 差别：不受「举盾后 170ms」窗口限制、不受「一次按住只弹反一击」限制——<b>整个窗口每一击
 * 都弹反</b>（这就是纸人的开销）。危攻击依然不可弹反（ParryManager 危判定在窗口判定之前）；
 * 弓箭不参与弹反（与完美弹反口径一致）；受击状态（stagger，无法格挡）期间不授予强制弹反。</p>
 *
 * <p><b>强制收盾</b>：窗口结束时 {@code setCooldown(SHIELD, 1)} 强制 {@code isBlocking()}
 * 变 false（1.21.11 无“停止持盾”API，冷却是既有的强制手段）——玩家可立即重新右键举盾
 * 再开一窗（再付纸人）。</p>
 *
 * <p><b>恐怖区免疫</b>：处于弹反窗口的玩家免疫僵尸头颅恐怖区的负面（{@code TerrorManager}
 * 查询 {@link #isDeflecting}），语义不变。</p>
 */
public final class DeflectManager {
    private static final String MARKER_START = "# === SekiroBedwar shield START ===";
    private static final String MARKER_END = "# === SekiroBedwar shield END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final DeflectConfig config;
    private final PaperDollManager paperDollManager;
    private final DeflectListener listener;

    /** 玩家 → 弹反窗口截止时刻（单调毫秒）。窗口结束即移除。 */
    private final Map<UUID, Long> deflectUntil = new HashMap<>();
    private BukkitTask expireTask;

    public DeflectManager(SekiroBedwar plugin, DeflectConfig config, PaperDollManager paperDollManager) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.listener = new DeflectListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        expireTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 1L, 1L);
        injectShop();
        plugin.getLogger().info("盾牌弹反已启用：纸人×" + config.paperDollCost()
                + " 完美弹反窗口=" + config.deflectWindowMs() + "ms（窗口结束强制收盾）");
    }

    public void disable() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
        deflectUntil.clear();
    }

    public void clear(UUID uuid) {
        deflectUntil.remove(uuid);
    }

    /** 玩家是否处于纸人弹反窗口（= 完美弹反窗口，也用于免疫恐怖区负面）。 */
    public boolean isDeflecting(UUID uuid) {
        Long until = deflectUntil.get(uuid);
        return until != null && now() < until;
    }

    /**
     * 主手持盾右键触发（由 {@link DeflectListener} 调用）：1 tick 后确认实际举盾才扣纸人开窗。
     * 已在窗口内 / 纸人不足 / 未举成盾 → 不扣费、不开窗。
     */
    public void requestDeflect(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (isDeflecting(uuid)) {
            return; // 已在弹反窗口内：不重复扣费（防连点/事件重复触发）
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || !p.isBlocking()) {
                return; // 未真正举盾（误触/被拦截）——不扣费
            }
            if (isDeflecting(uuid)) {
                return; // 延迟期间已有另一窗
            }
            if (!paperDollManager.consumePaperDolls(p, config.paperDollCost())) {
                return; // 纸人不足：静默失败（沉浸原则，无文字提示）
            }
            deflectUntil.put(uuid, now() + config.deflectWindowMs());
        }, 1L);
    }

    /** 周期检测：窗口结束 → 移除记录 + 强制解除举盾（盾牌 1 tick 冷却打断持盾）。 */
    private void expire() {
        if (deflectUntil.isEmpty()) {
            return;
        }
        long now = now();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(deflectUntil).entrySet()) {
            if (now < entry.getValue()) {
                continue;
            }
            deflectUntil.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.setCooldown(Material.SHIELD, 1);
            }
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 幂等注入：把盾牌购买项并入剑攻速类别（sword-speed 块内，END 之前）。 */
    private void injectShop() {
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null) {
            plugin.getLogger().info("未找到 ScreamingBedWars，跳过盾牌商店注入");
            return;
        }
        File shopFile = new File(bw.getDataFolder(), "shop" + File.separator + "shop.yml");
        if (!shopFile.isFile()) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(shopFile.toPath()), StandardCharsets.UTF_8);
            content = removeBlock(content);
            int speedEnd = content.indexOf(SPEED_END);
            if (speedEnd < 0) {
                plugin.getLogger().warning("未找到剑攻速商店块，跳过盾牌注入");
                return;
            }
            String block = buildBlock();
            content = content.substring(0, speedEnd) + block + content.substring(speedEnd);
            Files.write(shopFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("已注入盾牌商店物品: " + shopFile.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().warning("盾牌商店注入失败: " + ex.getMessage());
        }
    }

    private String removeBlock(String content) {
        int start = content.indexOf(MARKER_START);
        if (start < 0) {
            return content;
        }
        int end = content.indexOf(MARKER_END, start);
        if (end < 0) {
            return content;
        }
        int endLine = content.indexOf('\n', end);
        endLine = endLine < 0 ? content.length() : endLine + 1;
        return content.substring(0, start) + content.substring(endLine);
    }

    private String buildBlock() {
        return MARKER_START + "\n"
                + "  - price: 5 of iron\n"
                + "    stack:\n"
                + "      type: shield\n"
                + "      display-name: \"盾牌\"\n"
                + MARKER_END + "\n";
    }
}
