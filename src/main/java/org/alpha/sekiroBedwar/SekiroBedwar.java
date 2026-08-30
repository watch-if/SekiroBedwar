package org.alpha.sekiroBedwar;

import org.alpha.sekiroBedwar.block.BlockConfig;
import org.alpha.sekiroBedwar.block.BlockManager;
import org.alpha.sekiroBedwar.duel.DuelAreaGuard;
import org.alpha.sekiroBedwar.freeze.DuelBlockProtectionListener;
import org.alpha.sekiroBedwar.freeze.FreezeConfig;
import org.alpha.sekiroBedwar.freeze.ResourceFreezeManager;
import org.alpha.sekiroBedwar.freeze.RespawnFreezeManager;
import org.alpha.sekiroBedwar.lightning.LightningConfig;
import org.alpha.sekiroBedwar.lightning.LightningManager;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelTriggerManager;
import org.alpha.sekiroBedwar.duel.SettlementConfig;
import org.alpha.sekiroBedwar.duel.SettlementManager;
import org.alpha.sekiroBedwar.parry.ParryConfig;
import org.alpha.sekiroBedwar.parry.ParryManager;
import org.alpha.sekiroBedwar.parry.ParrySealManager;
import org.alpha.sekiroBedwar.speed.SpeedConfig;
import org.alpha.sekiroBedwar.speed.SpeedManager;
import org.alpha.sekiroBedwar.stance.StanceBossBarDisplay;
import org.alpha.sekiroBedwar.stance.StanceBreakManager;
import org.alpha.sekiroBedwar.stance.StanceConfig;
import org.alpha.sekiroBedwar.stance.StanceRecoveryTask;
import org.alpha.sekiroBedwar.stance.StanceListener;
import org.alpha.sekiroBedwar.stance.StanceManager;
import org.alpha.sekiroBedwar.stance.StanceXpDisplay;
import org.alpha.sekiroBedwar.swordblock.SwordBlockingManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SekiroWar 插件主类。
 *
 * <p>目前集成：
 * <ul>
 *   <li>{@link DuelTriggerManager}：决斗触发判定 + 触发后特效（DuelTriggeredEvent）；</li>
 *   <li>{@link DuelManager}：触发后的决斗生命周期管理（PENDING/ACTIVE/ENDING 状态机、
 *       第三方进入结束、单决斗互斥）；</li>
 *   <li>{@link DuelAreaGuard}：决斗期间区域限制（不能主动离开白色内圆边界，
 *       主动越界拉回 / 主动传送拦截 / 击退位移豁免）；</li>
 *   <li>{@link StanceManager}：独立架势系统（当前/最大架势、增/减/设/百分比/崩条/无法格挡），
 *       最大架势按背包资源配置公式计算，BossBar 决斗期间互显对方；</li>
 *   <li>{@link StanceRecoveryTask}：架势自然恢复（空闲时逐 tick 向满架势恢复，r 可配置）；</li>
 *   <li>{@link SettlementManager}：决斗结算（崩条/普通/虚空三情形按比例转移物品，
 *       第三方介入回滚到决斗开始资源快照）；处决窗口期间双方可逃离决斗场地。</li>
 *   <li>{@link StanceBreakManager}：架势崩溃（临界时未弹反近战命中 / 被弹反触发崩条 +
 *       低血量拉临界 + 崩条后短暂无法格挡 + 架势非满时阻断自然回血）。</li>
 *   <li>{@link BlockManager}：普通格挡 / 受击架势（无格挡命中扣受击方架势；
 *       盾牌普通格挡不完全免架势，防守方 + 攻击方均按配置比例扣架势，覆盖近战与弓箭）；</li>
 *   <li>{@link ParryManager}：完美弹反（命中窗口完整弹开攻击并重创攻击方架势；
 *       未命中窗口按普通格挡处理，绝不误判）。</li>
 *   <li>{@link SpeedManager}：剑攻速强化（商店可购买、等级化，作用于本人所有近战武器；
 *       幂等注入 shop.yml + StorePrePurchaseEvent 拦截自扣费 + ATTACK_SPEED 修正）。</li>
 *   <li>{@link ResourceFreezeManager}：物资刷新冻结（白圈内刷新点暂停实际生成、计时照常，
 *       决斗结束后下一次刷新节点补出；缓存窗口可配置）。</li>
 *   <li>{@link RespawnFreezeManager}：队伍复活冻结（床在白圈期间成员死亡挂起复活倒计时，
 *       决斗结束恢复；床被拆视为最终死亡）。</li>
 *   <li>{@link DuelBlockProtectionListener}：决斗双方方块保护（范围内禁破、期间禁搭）。</li>
 * </ul>
 * 不改动 ScreamingBedWars，全部通过 BedWars API / Event 叠加实现。</p>
 */
public final class SekiroBedwar extends JavaPlugin {

    private DuelTriggerManager duelTriggerManager;
    private DuelManager duelManager;
    private DuelAreaGuard duelAreaGuard;
    private StanceConfig stanceConfig;
    private StanceManager stanceManager;
    private StanceBossBarDisplay stanceDisplay;
    private StanceXpDisplay stanceXpDisplay;
    private StanceRecoveryTask stanceRecoveryTask;
    private SettlementManager settlementManager;
    private StanceBreakManager stanceBreakManager;
    private BlockManager blockManager;
    private ParryManager parryManager;
    private ParrySealManager parrySealManager;
    private SpeedManager speedManager;
    private FreezeConfig freezeConfig;
    private ResourceFreezeManager resourceFreezeManager;
    private RespawnFreezeManager respawnFreezeManager;
    private SwordBlockingManager swordBlockingManager;
    private LightningManager lightningManager;

    @Override
    public void onEnable() {
        DuelConfig duelConfig = new DuelConfig(this);

        this.duelManager = new DuelManager(this, duelConfig);
        this.duelManager.enable();

        this.duelAreaGuard = new DuelAreaGuard(this, duelConfig, this.duelManager);
        this.duelAreaGuard.enable();

        this.duelTriggerManager = new DuelTriggerManager(this, duelConfig);
        this.duelTriggerManager.enable();
        // 玩家已处于某场决斗中时，触发侧不再广播新的 DuelTriggeredEvent
        this.duelTriggerManager.setAlreadyInDuelPredicate(uuid -> this.duelManager.isInDuel(uuid));

        // 架势系统：状态管理 + BossBar 互显对方 + 经验条显示自己的架势
        //（均由 DuelTriggeredEvent / DuelEndedEvent 驱动）
        this.stanceConfig = new StanceConfig(this);
        this.stanceManager = new StanceManager(this, stanceConfig);
        this.stanceDisplay = new StanceBossBarDisplay(this, stanceConfig, stanceManager);
        this.stanceXpDisplay = new StanceXpDisplay(this, stanceConfig, stanceManager);
        getServer().getPluginManager().registerEvents(
                new StanceListener(stanceManager, stanceDisplay, stanceXpDisplay), this);
        // 架势自然恢复（逐 tick，独立模块）：空闲时向满架势恢复
        this.stanceRecoveryTask = new StanceRecoveryTask(this, stanceConfig, stanceManager);
        this.stanceRecoveryTask.enable();

        // 处决窗口期间双方可逃离决斗场地（越界拉回 / 主动传送均豁免）
        this.duelAreaGuard.setEscapeWindowPredicate(uuid -> this.stanceManager.isBroken(uuid));

        // 决斗结算：崩条/普通/虚空三情形按比例实际转移物品；第三方介入回滚到决斗开始资源快照
        this.settlementManager = new SettlementManager(this, stanceConfig, new SettlementConfig(this),
                duelManager, stanceManager);
        this.settlementManager.enable();

        // 架势崩溃管理（独立模块）：临界时未弹反近战命中 / 被弹反触发崩条 + 低血量拉临界 +
        // 崩条后短暂无法格挡（盾牌强制冷却）+ 架势非满时阻断自然回血。
        this.stanceBreakManager = new StanceBreakManager(this, stanceConfig, stanceManager);
        this.stanceBreakManager.enable();

        // 剑攻速强化（独立模块）：商店可购买，等级化，作用于本人所有近战武器。
        // enable 时把「剑攻速强化」类别幂等注入 ScreamingBedWars 的 shop/shop.yml（巴之雷并入该类别），
        // 用 StorePrePurchaseEvent 拦截购买（取消 + 自扣费 + 加 ATTACK_SPEED 修正）。
        this.speedManager = new SpeedManager(this, new SpeedConfig(this));
        this.speedManager.enable();

        // 巴之雷（雷击 / 雷反，独立模块）：商店两级购买（L1 三连击接跳斩落雷 / L2 附忠诚三叉戟），
        // 由 BlockManager（有效架势命中）与 ParryManager（被弹反）钩子驱动触发。
        this.lightningManager = new LightningManager(this, new LightningConfig(this), stanceManager, duelManager);
        this.lightningManager.enable();

        // 普通格挡 / 受击架势（独立模块）：无格挡命中扣受击方架势 Dactual×hit-multiplier；
        // 盾牌普通格挡不完全免架势——防守方扣 Dbase×defender-multiplier（攻击方不扣）。
        // 只处理 ACTIVE 决斗内对方攻击（含弓箭/投射物），不破坏原版战斗。
        this.blockManager = new BlockManager(this, new BlockConfig(this), stanceManager, duelManager,
                stanceBreakManager, this.lightningManager);
        this.blockManager.enable();

        // 完美弹反系统（独立模块，与普通格挡分离）：只判完美弹反——命中窗口则完整弹开攻击并重创
        // 攻击方架势；未命中窗口（含格挡但超出窗口）直接交普通格挡模块处理，绝不误判为完美弹反。
        // 连续弹反计数器窗口（ParrySealManager）：连续被对方完美弹反 N 次后一段时间的攻势无效，
        // 由 ParryManager 在同一 HIGH 回调驱动计数与封印判定（不新增同优先级监听）。
        ParryConfig parryConfig = new ParryConfig(this);
        this.parrySealManager = new ParrySealManager(this, parryConfig);
        this.parrySealManager.enable();
        this.parryManager = new ParryManager(this, parryConfig, stanceManager, duelManager,
                stanceBreakManager, this.parrySealManager, this.lightningManager);
        this.parryManager.enable();

        // 决斗冻结系统（独立模块）：物资刷新冻结（白圈内刷新点暂停实际生成，计时照常，
        // 决斗结束后按下一次刷新节点补出）+ 队伍复活冻结（床在白圈期间成员死亡挂起复活，
        // 决斗结束恢复；床被拆视为最终死亡）+ 方块保护（决斗范围内禁破、期间全图禁搭）。
        this.freezeConfig = new FreezeConfig(this);
        this.resourceFreezeManager = new ResourceFreezeManager(this, freezeConfig, duelConfig, duelManager);
        this.resourceFreezeManager.enable();
        this.respawnFreezeManager = new RespawnFreezeManager(this, freezeConfig, duelConfig, duelManager);
        this.respawnFreezeManager.enable();
        getServer().getPluginManager().registerEvents(
                new DuelBlockProtectionListener(freezeConfig, duelManager), this);

        // 剑格挡（独立模块）：1.21.2+ 有 blocks_attacks 组件时给剑赋盾牌格挡能力（右键举盾、
        // 可被斧头破盾、右键禁用）；1.21.1 无该组件自动跳过。
        this.swordBlockingManager = new SwordBlockingManager(this);
        this.swordBlockingManager.enable();

        getLogger().info("SekiroBedwar 已启用，决斗触发配置 radius=" + duelConfig.radius()
                + " inner(y)=" + duelConfig.innerRadius() + " outer(z)=" + duelConfig.outerRadius()
                + " pending=" + duelConfig.duelPendingSeconds() + "s");
    }

    @Override
    public void onDisable() {
        // 冻结模块先于 DuelManager.disable() 禁用：清空冻结状态，避免关闭时 DuelEndedEvent 误恢复
        if (this.respawnFreezeManager != null) {
            this.respawnFreezeManager.disable();
        }
        if (this.resourceFreezeManager != null) {
            this.resourceFreezeManager.disable();
        }
        if (this.swordBlockingManager != null) {
            this.swordBlockingManager.disable();
        }
        if (this.speedManager != null) {
            this.speedManager.disable();
        }
        if (this.parrySealManager != null) {
            this.parrySealManager.disable();
        }
        if (this.parryManager != null) {
            this.parryManager.disable();
        }
        if (this.lightningManager != null) {
            this.lightningManager.disable();
        }
        if (this.blockManager != null) {
            this.blockManager.disable();
        }
        if (this.stanceBreakManager != null) {
            this.stanceBreakManager.disable();
        }
        if (this.stanceRecoveryTask != null) {
            this.stanceRecoveryTask.disable();
        }
        if (this.settlementManager != null) {
            this.settlementManager.disable();
        }
        if (this.stanceXpDisplay != null) {
            this.stanceXpDisplay.disable();
        }
        if (this.stanceDisplay != null) {
            this.stanceDisplay.disable();
        }
        if (this.stanceManager != null) {
            this.stanceManager.disable();
        }
        if (this.duelAreaGuard != null) {
            this.duelAreaGuard.disable();
        }
        if (this.duelManager != null) {
            this.duelManager.disable();
        }
        if (this.duelTriggerManager != null) {
            this.duelTriggerManager.disable();
        }
    }

    /** 获取决斗触发管理器。 */
    public DuelTriggerManager getDuelTriggerManager() {
        return this.duelTriggerManager;
    }

    /** 获取决斗生命周期管理器。 */
    public DuelManager getDuelManager() {
        return this.duelManager;
    }

    /** 获取决斗区域限制守卫。 */
    public DuelAreaGuard getDuelAreaGuard() {
        return this.duelAreaGuard;
    }

    /** 获取架势系统管理器。 */
    public StanceManager getStanceManager() {
        return this.stanceManager;
    }

    /** 获取架势系统配置。 */
    public StanceConfig getStanceConfig() {
        return this.stanceConfig;
    }

    /** 获取架势崩溃（崩条）管理器。 */
    public StanceBreakManager getStanceBreakManager() {
        return this.stanceBreakManager;
    }

    /** 获取普通格挡 / 受击架势管理器。 */
    public BlockManager getBlockManager() {
        return this.blockManager;
    }

    /** 获取完美弹反系统管理器。 */
    public ParryManager getParryManager() {
        return this.parryManager;
    }

    /** 获取连续弹反计数器窗口（攻势无效）管理器。 */
    public ParrySealManager getParrySealManager() {
        return this.parrySealManager;
    }

    /** 获取剑攻速强化管理器。 */
    public SpeedManager getSpeedManager() {
        return this.speedManager;
    }

    /** 获取决斗冻结系统配置。 */
    public FreezeConfig getFreezeConfig() {
        return this.freezeConfig;
    }

    /** 获取物资刷新冻结管理器。 */
    public ResourceFreezeManager getResourceFreezeManager() {return this.resourceFreezeManager;}

    /** 获取队伍复活冻结管理器。 */
    public RespawnFreezeManager getRespawnFreezeManager() {
        return this.respawnFreezeManager;
    }

    /** 获取剑格挡管理器。 */
    public SwordBlockingManager getSwordBlockingManager() {
        return this.swordBlockingManager;
    }

    /** 获取巴之雷管理器。 */
    public LightningManager getLightningManager() {
        return this.lightningManager;
    }
}
