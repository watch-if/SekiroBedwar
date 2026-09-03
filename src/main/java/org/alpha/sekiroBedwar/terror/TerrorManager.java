package org.alpha.sekiroBedwar.terror;

import org.alpha.sekiroBedwar.SekiroBedwar;
import org.alpha.sekiroBedwar.deflect.DeflectManager;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 僵尸头颅 + 恐怖条管理器：击杀 50% 掉头颅；左键使用（耗 3 纸人 + 1 头颅）→ 半径 3 内 2s 施加反胃 + 1HP/s + 恐怖累积；
 * 恐怖条 F∈[0,100] 后台记录、不展示，满 100 瞬秒；无维持态时以 -10/s 衰减。
 */
public final class TerrorManager {
    private final SekiroBedwar plugin;
    private final TerrorConfig config;
    private final PaperDollManager paperDollManager;
    private final DeflectManager deflectManager;
    private final NamespacedKey ownerKey;
    private final TerrorListener listener;

    private final Map<UUID, Double> terror = new HashMap<>();
    private final List<TerrorZone> zones = new ArrayList<>();
    private BukkitTask tickTask;

    public TerrorManager(SekiroBedwar plugin, TerrorConfig config, PaperDollManager paperDollManager,
                         DeflectManager deflectManager) {
        this.plugin = plugin;
        this.config = config;
        this.paperDollManager = paperDollManager;
        this.deflectManager = deflectManager;
        this.ownerKey = new NamespacedKey(plugin, "zombie_head");
        this.listener = new TerrorListener(this);
    }

    public void enable() {
        if (!config.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        plugin.getLogger().info("僵尸头颅已启用：掉落=" + config.dropChance()
                + " 纸人×" + config.paperDollCost() + " 恐怖上限=" + config.maxTerror());
    }

    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        terror.clear();
        zones.clear();
    }

    public void clear(UUID uuid) {
        terror.remove(uuid);
        zones.removeIf(zone -> zone.owner.equals(uuid));
    }

    // ============ 物品 ============

    public boolean isZombieHead(ItemStack item) {
        if (item == null || item.getType() != Material.ZOMBIE_HEAD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private boolean isOwnedZombieHead(ItemStack item, Player player) {
        if (!isZombieHead(item)) {
            return false;
        }
        String owner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    private ItemStack buildHead(Player player) {
        ItemStack head = new ItemStack(Material.ZOMBIE_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName("§f僵尸头颅");
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        head.setItemMeta(meta);
        return head;
    }

    private boolean consumeZombieHead(Player player, int n) {
        int remaining = n;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!isOwnedZombieHead(item, player)) {
                continue;
            }
            if (item.getAmount() > remaining) {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            } else {
                remaining -= item.getAmount();
                player.getInventory().setItem(i, null);
            }
        }
        return remaining <= 0;
    }

    // ============ 使用 ============

    /** 左键使用僵尸头颅：耗 3 纸人 + 1 头颅 → 在释放位置创建恐怖区。 */
    public void handleUse(Player player, ItemStack held) {
        if (!config.enabled() || player == null || held == null) {
            return;
        }
        if (!isOwnedZombieHead(held, player)) {
            return;
        }
        if (!paperDollManager.consumePaperDolls(player, config.paperDollCost())) {
            return;
        }
        if (!consumeZombieHead(player, 1)) {
            return;
        }
        zones.add(new TerrorZone(player.getUniqueId(), player.getLocation().clone(),
                System.currentTimeMillis() + config.durationMs()));
    }

    // ============ 死亡 ============

    /** 死亡：清除死亡者头颅（不掉落）+ 清恐怖值 + 50% 掉给击杀者。 */
    public void handleDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        event.getDrops().removeIf(this::isZombieHead);
        terror.remove(victim.getUniqueId());
        if (!config.enabled()) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        if (Math.random() < config.dropChance()) {
            killer.getInventory().addItem(buildHead(killer));
        }
    }

    // ============ 周期 ============

    private void tick() {
        if (!config.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        zones.removeIf(zone -> zone.until < now);
        if (!zones.isEmpty()) {
            double r2 = config.radius() * config.radius();
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                boolean inZone = false;
                for (TerrorZone zone : zones) {
                    if (zone.owner.equals(id) || zone.center.getWorld() != player.getWorld()) {
                        continue;
                    }
                    if (zone.center.distanceSquared(player.getLocation()) <= r2) {
                        inZone = true;
                        break;
                    }
                }
                double f = terror.getOrDefault(id, 0.0);
                if (inZone && !deflectManager.isDeflecting(id)) {
                    f += config.terrorPerSecond();
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 40, 0, false, false, false));
                    player.damage(config.damagePerSecond());
                } else {
                    f -= config.decayPerSecond();
                }
                f = Math.max(0.0, Math.min(config.maxTerror(), f));
                if (f >= config.maxTerror()) {
                    terror.remove(id);
                    player.setHealth(0.0);
                } else if (f <= 0.0) {
                    terror.remove(id);
                } else {
                    terror.put(id, f);
                }
            }
            for (TerrorZone zone : zones) {
                Location c = zone.center;
                c.getWorld().spawnParticle(Particle.BUBBLE, c.getX(), c.getY() + 1, c.getZ(),
                        40, config.radius(), 1.5, config.radius(), 0.05);
            }
        } else if (!terror.isEmpty()) {
            // 无恐怖区时仍衰减
            for (UUID id : new ArrayList<>(terror.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    terror.remove(id);
                    continue;
                }
                double f = Math.max(0.0, terror.get(id) - config.decayPerSecond());
                if (f <= 0.0) {
                    terror.remove(id);
                } else {
                    terror.put(id, f);
                }
            }
        }
    }

    private static final class TerrorZone {
        final UUID owner;
        final Location center;
        final long until;

        TerrorZone(UUID owner, Location center, long until) {
            this.owner = owner;
            this.center = center;
            this.until = until;
        }
    }
}
