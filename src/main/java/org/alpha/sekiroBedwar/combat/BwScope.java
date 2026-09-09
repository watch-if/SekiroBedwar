package org.alpha.sekiroBedwar.combat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.player.BWPlayer;

import java.util.UUID;

/**
 * 对局作用域工具：SekiroBedwar 的玩法功能<b>只在 BedWars 对局内触发，对局外（大厅 /
 * 等待房）一律不生效</b>（用户约束 2026-09-08）。
 *
 * <p>判定 = 玩家持有 BWPlayer 且 {@link BWPlayer#isInGame()}。ScreamingBedWars 缺失、
 * API 异常或玩家不在任何对局 → 一律 false（保守关闭，绝不把功能漏到对局外）。
 * 决斗类模块（Block/Parry/秘传命中钩子等）本就以 ACTIVE 决斗为前提天然满足，
 * 本工具供<b>无决斗前提</b>的自由触发模块（无敌帧巡检 / 风弹 / 龙闪 / 恐怖 / 耐久 /
 * 剑格挡）在入口处闸断。</p>
 */
public final class BwScope {

    private BwScope() {
    }

    /** 玩家是否处于 BedWars 对局中。 */
    public static boolean inGame(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Plugin bw = Bukkit.getPluginManager().getPlugin("ScreamingBedWars");
        if (bw == null || !bw.isEnabled()) {
            return false;
        }
        try {
            return BedwarsAPI.getInstance().getPlayerManager().getPlayer(uuid)
                    .map(BWPlayer::isInGame).orElse(false);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
