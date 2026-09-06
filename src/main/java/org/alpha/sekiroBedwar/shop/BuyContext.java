package org.alpha.sekiroBedwar.shop;

import org.bukkit.entity.Player;
import org.screamingsandals.bedwars.api.player.BWPlayer;

/**
 * GUI 购买上下文：Bukkit 玩家 + 点击瞬间经 {@code BedwarsAPI.getPlayerManager()} 实查的
 * BedWars {@link BWPlayer}（供 {@code isInGame()} 复核与 {@code getGame()} 取存活队伍数）。
 * 无会话暂存——查不到 BWPlayer 时监听器直接拒绝开买。
 */
public record BuyContext(Player player, BWPlayer bwPlayer) {
}
