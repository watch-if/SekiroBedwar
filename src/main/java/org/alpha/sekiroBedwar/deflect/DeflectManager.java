package org.alpha.sekiroBedwar.deflect;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.combat.CombatUtils;
import org.alpha.sekiroBedwar.duel.Duel;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelState;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 盾牌弹反返还（独立新机制）：主手持盾右键扣纸人 → 2s 免疫累计 → 1.5s 内命中返还。
 */
public final class DeflectManager {
    private static final String MARKER_START = "# === SekiroBedwar shield START ===";
    private static final String MARKER_END = "# === SekiroBedwar shield END ===";
    private static final String SPEED_END = "# === SekiroBedwar sword-speed END ===";

    private final SekiroBedwar plugin;
    private final DeflectConfig config;
    private final PaperDollManager paperDollManager;
    private final DuelManager duelManager;
    private final DeflectListener listener;

    private final Map<UUID, DeflectState> states = new HashMap<>();
    private BukkitTask expireTask;

    public DeflectManager(SekiroBedwar plugin, DeflectConfig config,
                          PaperDollManager paperDollManager, DuelManager duelManager) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.duelManager = duelManager;
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
                + " 弹反窗=" + config.deflectWindowMs() + "ms 反击窗=" + config.counterWindowMs() + "ms");
    }

    public void disable() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
        states.clear();
    }

    public void clear(UUID uuid) {
        states.remove(uuid);
    }

    /** 主手持盾右键触发：扣纸人并进入弹反窗口。 */
    public boolean tryStartDeflect(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        Duel duel = duelManager.getDuel(player.getUniqueId()).orElse(null);
        if (duel == null || duel.getState() != DuelState.ACTIVE) {
            return false;
        }
        if (!paperDollManager.consumePaperDolls(player, config.paperDollCost())) {
            return false;
        }
        long now = now();
        DeflectState state = states.get(player.getUniqueId());
        if (state == null) {
            state = new DeflectState();
            states.put(player.getUniqueId(), state);
        }
        state.deflectUntil = now + config.deflectWindowMs();
        state.counterUntil = now + config.deflectWindowMs() + config.counterWindowMs();
        state.accumulated = 0.0;
        return true;
    }

    /** 弹反窗口免疫 + 累计（HIGHEST，先于完美弹反/普通格挡）。 */
    public void handleNegate(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        DeflectState state = states.get(victim.getUniqueId());
        if (state == null || now() >= state.deflectUntil) {
            return;
        }
        double dmg = event.getFinalDamage();
        if (dmg <= 0.0) {
            dmg = event.getDamage();
        }
        state.accumulated += dmg;
        event.setCancelled(true);
    }

    /** 反击窗口内有效近战命中 → 返还累计伤害（MONITOR，仅未取消的攻击）。 */
    public void handleReflect(EntityDamageByEntityEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = CombatUtils.resolveMeleeAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        DeflectState state = states.get(attacker.getUniqueId());
        if (state == null || now() < state.deflectUntil || now() >= state.counterUntil) {
            return;
        }
        if (state.accumulated <= 0.0) {
            return;
        }
        double acc = state.accumulated;
        states.remove(attacker.getUniqueId());
        victim.damage(acc);
    }

    /** 周期清理：弹反窗口结束强制解除持盾；反击窗口到期未反击 → 丢弃累计。 */
    private void expire() {
        if (states.isEmpty()) {
            return;
        }
        long now = now();
        for (Map.Entry<UUID, DeflectState> entry : new ArrayList<>(states.entrySet())) {
            DeflectState state = entry.getValue();
            if (now >= state.deflectUntil && !state.released) {
                state.released = true;
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.setCooldown(Material.SHIELD, 1);
                }
            }
            if (now >= state.counterUntil) {
                states.remove(entry.getKey());
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

    private static final class DeflectState {
        long deflectUntil;
        long counterUntil;
        double accumulated;
        boolean released;
    }
}
