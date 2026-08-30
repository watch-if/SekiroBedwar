package org.alpha.sekiroBedwar.duel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * 决斗岛屿值对象。
 *
 * <ul>
 *   <li>动态推导岛屿：{@link #derived(World, double, double, double, double)}，圆心为两名玩家中点；</li>
 *   <li>预先配置岛屿：{@link #configured(World, double, double, double)}，白名单用。</li>
 * </ul>
 *
 * <p>“实心判定”：以圆心 + 圆周若干采样点所在立柱的“最高非空气方块”是否非虚空且非液体，
 * 判断该范围“下方有方块而非底下就是虚空”。</p>
 */
public final class DuelIsland {
    private final World world;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double radius;

    private DuelIsland(World world, double centerX, double centerY, double centerZ, double radius) {
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
    }

    /** 动态推导岛屿：圆心为两名玩家中点（centerY = 两人 Y 均值）。 */
    public static DuelIsland derived(World world, double centerX, double centerY, double centerZ, double radius) {
        return new DuelIsland(world, centerX, centerY, centerZ, radius);
    }

    /** 预先配置岛屿（白名单）：只关心水平位置与半径。 */
    public static DuelIsland configured(World world, double centerX, double centerZ, double radius) {
        return new DuelIsland(world, centerX, 0.0, centerZ, radius);
    }

    public World getWorld() {
        return world;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public double getRadius() {
        return radius;
    }

    /** 到圆心（水平面）的距离。 */
    public double horizontalDistanceTo(Location loc) {
        double dx = loc.getX() - centerX;
        double dz = loc.getZ() - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 位置是否位于本岛屿圆内（水平距离 <= radius）。 */
    public boolean contains(Location loc) {
        return horizontalDistanceTo(loc) <= radius + 1.0e-9;
    }

    /** 另一个岛屿（圆）是否整体落在这个岛屿内（含边界）。 */
    public boolean containsFully(DuelIsland inner) {
        if (!world.equals(inner.world)) {
            return false;
        }
        double centerDist = Math.hypot(inner.centerX - centerX, inner.centerZ - centerZ);
        return centerDist + inner.radius <= radius + 1.0e-6;
    }

    /**
     * 实心判定：圆心 + 圆周上 {@code solidCheckPoints} 个采样点，所在立柱的
     * “最高非空气方块”需为“有方块”（非空气、非液体），且不落在世界虚空之下；
     * 达到 {@code solidMinRatio} 比例才视为实心岛屿。
     */
    public boolean isSolidGround(DuelConfig config) {
        if (!config.requireSolidGround()) {
            return true;
        }
        return sampleRatio(config, this::columnHasSolidGround) >= config.solidMinRatio();
    }

    /**
     * 非虚空判定：同一套采样点中，“立柱非虚空”的比例达到 {@code nonVoidMinRatio}
     * 即可视为合法岛屿（下方不完全是虚空即可，允许水面 / 岩浆面等不实心的区域）。
     */
    public boolean isNonVoidArea(DuelConfig config) {
        return sampleRatio(config, this::columnIsNonVoid) >= config.nonVoidMinRatio();
    }

    /**
     * 合法岛屿判定：实心校验通过，或范围内非虚空比例达标，任一满足即可。
     */
    public boolean isLegal(DuelConfig config) {
        return isSolidGround(config) || isNonVoidArea(config);
    }

    /** 圆心 + 圆周采样，统计满足 {@code check} 的立柱占比。 */
    private double sampleRatio(DuelConfig config, ColumnCheck check) {
        int points = Math.max(0, config.solidCheckPoints());

        int hit = 0;
        int checked = 1;
        // 圆心
        if (check.test((int) Math.floor(centerX), (int) Math.floor(centerZ))) {
            hit++;
        }
        // 圆周采样
        double step = 2.0 * Math.PI / points;
        for (int i = 0; i < points; i++) {
            double theta = step * i;
            int x = (int) Math.floor(centerX + radius * Math.cos(theta));
            int z = (int) Math.floor(centerZ + radius * Math.sin(theta));
            checked++;
            if (check.test(x, z)) {
                hit++;
            }
        }
        return checked == 0 ? 1.0 : (double) hit / checked;
    }

    /** 立柱采样判定接口。 */
    private interface ColumnCheck {
        boolean test(int x, int z);
    }

    /** 立柱是否非虚空：最高非空气方块不低于世界最小高度 + 1（下方不是纯虚空）。 */
    private boolean columnIsNonVoid(int x, int z) {
        return world.getHighestBlockYAt(x, z) >= world.getMinHeight() + 1;
    }

    private boolean columnHasSolidGround(int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        // 最高非空气方块低于世界最小高度 + 1，说明该立柱基本是虚空
        if (y < world.getMinHeight() + 1) {
            return false;
        }
        Material type = world.getBlockAt(x, y, z).getType();
        return !type.isAir() && !isLiquid(type);
    }

    /**
     * Paper 1.21.2+ 的 Material 已类化且移除了 {@code isLiquid()}，
     * 这里用已知液体方块名替代（水/岩浆/气泡柱）。
     */
    private static boolean isLiquid(Material type) {
        switch (type) {
            case WATER:
            case LAVA:
            case BUBBLE_COLUMN:
                return true;
            default:
                return false;
        }
    }
}
