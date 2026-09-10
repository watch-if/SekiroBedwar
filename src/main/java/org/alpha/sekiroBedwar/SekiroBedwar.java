package org.alpha.sekiroBedwar;

import org.alpha.sekiroBedwar.bead.BeadConfig;
import org.alpha.sekiroBedwar.bead.BeadManager;
import org.alpha.sekiroBedwar.attribute.AttributeConfig;
import org.alpha.sekiroBedwar.attribute.AttributeManager;
import org.alpha.sekiroBedwar.armory.ArmorShopConfig;
import org.alpha.sekiroBedwar.armory.ArmorShopManager;
import org.alpha.sekiroBedwar.block.BlockConfig;
import org.alpha.sekiroBedwar.block.BlockManager;
import org.alpha.sekiroBedwar.crow.CrowConfig;
import org.alpha.sekiroBedwar.crow.CrowManager;
import org.alpha.sekiroBedwar.deflect.DeflectConfig;
import org.alpha.sekiroBedwar.equip.AutoEquipConfig;
import org.alpha.sekiroBedwar.equip.AutoEquipManager;
import org.alpha.sekiroBedwar.equip.DurabilityGuardConfig;
import org.alpha.sekiroBedwar.equip.DurabilityGuardManager;
import org.alpha.sekiroBedwar.deflect.DeflectManager;
import org.alpha.sekiroBedwar.danger.DangerConfig;
import org.alpha.sekiroBedwar.danger.DangerManager;
import org.alpha.sekiroBedwar.duel.DuelAreaGuard;
import org.alpha.sekiroBedwar.freeze.DuelBlockProtectionListener;
import org.alpha.sekiroBedwar.freeze.FreezeConfig;
import org.alpha.sekiroBedwar.freeze.ResourceFreezeManager;
import org.alpha.sekiroBedwar.freeze.RespawnFreezeManager;
import org.alpha.sekiroBedwar.lightning.LightningConfig;
import org.alpha.sekiroBedwar.lightning.LightningManager;
import org.alpha.sekiroBedwar.mystery.IFrameManager;
import org.alpha.sekiroBedwar.mystery.MysteryConfig;
import org.alpha.sekiroBedwar.mystery.MysteryManager;
import org.alpha.sekiroBedwar.mobban.MobBanConfig;
import org.alpha.sekiroBedwar.mobban.MobBanManager;
import org.alpha.sekiroBedwar.paperdoll.DriftingPaperDollManager;
import org.alpha.sekiroBedwar.paperdoll.PaperDollConfig;
import org.alpha.sekiroBedwar.paperdoll.PaperDollManager;
import org.alpha.sekiroBedwar.duel.DuelConfig;
import org.alpha.sekiroBedwar.duel.DuelManager;
import org.alpha.sekiroBedwar.duel.DuelTriggerManager;
import org.alpha.sekiroBedwar.duel.SettlementConfig;
import org.alpha.sekiroBedwar.duel.SettlementManager;
import org.alpha.sekiroBedwar.parry.ParryConfig;
import org.alpha.sekiroBedwar.parry.ParryManager;
import org.alpha.sekiroBedwar.parry.ParrySealManager;
import org.alpha.sekiroBedwar.shop.SekiroShopConfig;
import org.alpha.sekiroBedwar.shop.SekiroShopManager;
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
import org.alpha.sekiroBedwar.terror.TerrorConfig;
import org.alpha.sekiroBedwar.terror.TerrorManager;
import org.alpha.sekiroBedwar.windcharge.WindChargeConfig;
import org.alpha.sekiroBedwar.windcharge.WindChargeManager;
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
    private PaperDollManager paperDollManager;
    private DriftingPaperDollManager driftingPaperDollManager;
    private DeflectManager deflectManager;
    private DangerManager dangerManager;
    private BeadManager beadManager;
    private TerrorManager terrorManager;
    private SekiroShopManager sekiroShopManager;
    private CrowManager crowManager;
    private WindChargeManager windChargeManager;
    private AttributeManager attributeManager;
    private MysteryManager mysteryManager;
    private IFrameManager iFrameManager;
    private MobBanManager mobBanManager;
    private org.alpha.sekiroBedwar.welcome.WelcomeManager welcomeManager;
    private AutoEquipManager autoEquipManager;
    private ArmorShopManager armorShopManager;
    private DurabilityGuardManager durabilityGuardManager;

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
        // 公共 API：安装内部发射器（事件与只读查询的宿主）+ 挂架势变化观察者。
        // 战斗统计 / 排位 / 录像等外部插件只依赖 api 包监听事件，核心不为其实现任何业务。
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.install(this, this.duelManager, this.stanceManager);
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl api =
                org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.get();
        if (api != null) {
            this.stanceManager.setObserver(api::onChange);
        }
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

        // 忍具商店（GUI 宿主）：先于全部可购买模块 enable——负责清理 shop.yml 的插件注入块、
        // 接管商店主页右下角入口；各模块随后把自家商品 register 进 GUI（购买判定全在插件内）。
        this.sekiroShopManager = new SekiroShopManager(this, new SekiroShopConfig(this));
        this.sekiroShopManager.enable();

        // 剑攻速强化（忍具商店 GUI 逐级购买）
        this.speedManager = new SpeedManager(this, new SpeedConfig(this), this.sekiroShopManager);
        this.speedManager.enable();

        // 纸人（忍具系统 + 巴之雷消耗品）+ 漂流纸人
        PaperDollConfig paperDollConfig = new PaperDollConfig(this);
        this.paperDollManager = new PaperDollManager(this, paperDollConfig, this.sekiroShopManager);
        this.paperDollManager.enable();
        this.driftingPaperDollManager = new DriftingPaperDollManager(this, paperDollConfig,
                this.paperDollManager, this.sekiroShopManager);
        this.driftingPaperDollManager.enable();

        // 盾牌弹反（独立模块）：主手举盾（右键 1 tick 后确认实际举盾）扣纸人 → 2s 内全部近战命中
        // 按完美弹反窗口处理（由 ParryManager 查询）→ 窗口结束强制解除举盾（盾牌冷却）
        this.deflectManager = new DeflectManager(this, new DeflectConfig(this), this.paperDollManager,
                this.sekiroShopManager);
        this.deflectManager.enable();

        // 自动装备：BedWars 商店购买盔/胸/腿/靴后从背包取出直接穿身上（Pre 暂存 + Post 配对，不取消商店事件）
        this.autoEquipManager = new AutoEquipManager(this, new AutoEquipConfig(this));
        this.autoEquipManager.enable();

        // 取消耐久消耗：按类别（护甲/工具/盾牌）取消 PlayerItemDamageEvent
        this.durabilityGuardManager = new DurabilityGuardManager(this, new DurabilityGuardConfig(this));
        this.durabilityGuardManager.enable();

        // 护甲商店：劫持商店主页「护甲」分类 → 自管套装页（四件即买即穿，复用忍具商店 GUI 框架）
        this.armorShopManager = new ArmorShopManager(this, new ArmorShopConfig(this), this.sekiroShopManager);
        this.armorShopManager.enable();

        // 雾璃鸦（反击型忍具）：忍具商店购买绑定末影之眼；右键激活（拦截原版投掷）头顶悬停 2s，
        // 期间首次受玩家伤害免伤并传送到攻击方身后，悬停结束破碎
        this.crowManager = new CrowManager(this, new CrowConfig(this), this.paperDollManager,
                this.sekiroShopManager);
        this.crowManager.enable();

        // 风弹（独立忍具）：忍具商店购买绑定风弹；投掷消耗 1 纸人 / 命中后传送复用 paper-doll
        // 机制；释放瞬间在面前生成半椭圆爆风墙（TNT 爆炸特效左→右扫过 + 停留），触碰者短时间内
        // 不能防御与攻击（强制收盾 + 对实体伤害一律取消），窗口内不可叠加
        this.windChargeManager = new WindChargeManager(this, new WindChargeConfig(this), stanceManager,
                this.sekiroShopManager);
        this.windChargeManager.enable();

        // 佛珠（忍具商店递增价购买）：单局上限 4 次、每次 +5 最大血量、价格递增
        this.beadManager = new BeadManager(this, new BeadConfig(this), this.sekiroShopManager);
        this.beadManager.enable();

        // 僵尸头颅 + 恐怖条（独立新机制）
        this.terrorManager = new TerrorManager(this, new TerrorConfig(this), this.paperDollManager, this.deflectManager);
        this.terrorManager.enable();

        // 属性伤害（锈丸 / 炎上 / 还原）：与巴之雷三选一专精互斥——互斥状态统一由
        // AttributeManager 判定（LightningManager 构造注入它；这里回填 lightning 供还原清档）
        this.attributeManager = new AttributeManager(this, new AttributeConfig(this), this.sekiroShopManager);

        // 巴之雷（忍具商店两级购买）
        this.lightningManager = new LightningManager(this, new LightningConfig(this), stanceManager, duelManager,
                this.paperDollManager, this.sekiroShopManager, this.attributeManager);
        this.attributeManager.setLightningManager(this.lightningManager);
        this.attributeManager.enable();
        this.lightningManager.enable();

        // 危攻击 / 识破（独立模块）：主手持矛（突进附魔）疾跑攻击 = 危，不可弹反，
        // 格挡破盾 + 扣架势，识破（下蹲 170ms 内接危）反击；长矛经忍具商店购买
        this.dangerManager = new DangerManager(this, new DangerConfig(this), stanceManager, duelManager,
                this.sekiroShopManager);
        this.dangerManager.enable();

        // 秘传系统：无敌帧双开关（全局默认有 / 决斗默认无）+ 秘传武技框架
        // （第一秘传·飞渡浮舟：七连击节奏识别，近战命中钩子由 Block/Parry 统一转发）
        MysteryConfig mysteryConfig = new MysteryConfig(this);
        this.mysteryManager = new MysteryManager(this, mysteryConfig, stanceManager, this.paperDollManager,
                this.duelManager, duelConfig);
        this.mysteryManager.enable();
        // 秘传运行时（tick 时基 / 外部进度槽 / cue 发声）接入公共 API 门面
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.attachMystery(this.mysteryManager);
        this.iFrameManager = new IFrameManager(this, mysteryConfig, this.duelManager);
        this.iFrameManager.enable();

        // 普通格挡 / 受击架势（独立模块）：无格挡命中扣受击方架势 Dactual×hit-multiplier；
        // 盾牌普通格挡不完全免架势——防守方扣 Dbase×defender-multiplier（攻击方不扣）。
        // 只处理 ACTIVE 决斗内对方攻击（含弓箭/投射物），不破坏原版战斗。
        this.blockManager = new BlockManager(this, new BlockConfig(this), stanceManager, duelManager,
                stanceBreakManager, this.lightningManager, this.dangerManager, this.attributeManager,
                this.mysteryManager);
        this.blockManager.enable();

        // 完美弹反系统（独立模块，与普通格挡分离）：只判完美弹反——命中窗口则完整弹开攻击并重创
        // 攻击方架势；未命中窗口（含格挡但超出窗口）直接交普通格挡模块处理，绝不误判为完美弹反。
        // 连续弹反计数器窗口（ParrySealManager）：连续被对方完美弹反 N 次后一段时间的攻势无效，
        // 由 ParryManager 在同一 HIGH 回调驱动计数与封印判定（不新增同优先级监听）。
        ParryConfig parryConfig = new ParryConfig(this);
        this.parrySealManager = new ParrySealManager(this, parryConfig);
        this.parrySealManager.enable();
        this.parryManager = new ParryManager(this, parryConfig, stanceManager, duelManager,
                stanceBreakManager, this.parrySealManager, this.lightningManager, this.dangerManager,
                this.deflectManager, this.attributeManager, this.mysteryManager);
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

        // 红圈生物禁令：进行决斗的红圈（与第三方排除同半径）内禁止铁傀儡 / 羊（TNT羊）生成，
        // 且一切非玩家生物进圈即移除（巡检，无掉落无死亡消息）
        this.mobBanManager = new MobBanManager(this, new MobBanConfig(this), duelConfig, this.duelManager);
        this.mobBanManager.enable();

        // 玩法指南书：进服 / 回大厅发放成书（10 页），进入 BedWars 对局时收回
        this.welcomeManager = new org.alpha.sekiroBedwar.welcome.WelcomeManager(this,
                new org.alpha.sekiroBedwar.welcome.WelcomeConfig(this));
        this.welcomeManager.enable();

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
        if (this.sekiroShopManager != null) {
            this.sekiroShopManager.disable();
        }
        // 冻结模块先于 DuelManager.disable() 禁用：清空冻结状态，避免关闭时 DuelEndedEvent 误恢复
        if (this.respawnFreezeManager != null) {
            this.respawnFreezeManager.disable();
        }
        if (this.mobBanManager != null) {
            this.mobBanManager.disable();
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
        if (this.attributeManager != null) {
            this.attributeManager.disable();
        }
        if (this.dangerManager != null) {
            this.dangerManager.disable();
        }
        if (this.iFrameManager != null) {
            this.iFrameManager.disable(); // 全员恢复原版 20 tick（先于 DuelManager 关闭）
        }
        if (this.mysteryManager != null) {
            this.mysteryManager.disable();
        }
        if (this.armorShopManager != null) {
            this.armorShopManager.disable();
        }
        if (this.autoEquipManager != null) {
            this.autoEquipManager.disable();
        }
        if (this.windChargeManager != null) {
            this.windChargeManager.disable();
        }
        if (this.crowManager != null) {
            this.crowManager.disable();
        }
        if (this.deflectManager != null) {
            this.deflectManager.disable();
        }
        if (this.terrorManager != null) {
            this.terrorManager.disable();
        }
        if (this.beadManager != null) {
            this.beadManager.disable();
        }
        if (this.driftingPaperDollManager != null) {
            this.driftingPaperDollManager.disable();
        }
        if (this.paperDollManager != null) {
            this.paperDollManager.disable();
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
        // 公共 API 最后卸载（观察者摘除 + 停发事件）
        if (this.stanceManager != null) {
            this.stanceManager.setObserver(null);
        }
        org.alpha.sekiroBedwar.api.internal.SekiroApiImpl.uninstall();
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

    /** 获取纸人管理器。 */
    public PaperDollManager getPaperDollManager() {
        return this.paperDollManager;
    }

    /** 获取漂流纸人管理器。 */
    public DriftingPaperDollManager getDriftingPaperDollManager() {
        return this.driftingPaperDollManager;
    }

    /** 获取盾牌弹反返还管理器。 */
    public DeflectManager getDeflectManager() {
        return this.deflectManager;
    }

    /** 获取危攻击 / 识破管理器。 */
    public DangerManager getDangerManager() {
        return this.dangerManager;
    }

    /** 获取佛珠管理器。 */
    public BeadManager getBeadManager() {
        return this.beadManager;
    }

    /** 获取僵尸头颅 / 恐怖条管理器。 */
    public TerrorManager getTerrorManager() {
        return this.terrorManager;
    }

    /** 获取忍具商店（GUI 宿主）管理器。 */
    public SekiroShopManager getSekiroShopManager() {
        return this.sekiroShopManager;
    }

    /** 获取雾璃鸦管理器。 */
    public CrowManager getCrowManager() {
        return this.crowManager;
    }

    /** 获取风弹管理器。 */
    public WindChargeManager getWindChargeManager() {
        return this.windChargeManager;
    }

    /** 获取秘传宿主（飞渡浮舟等武技）。 */
    public MysteryManager getMysteryManager() {
        return this.mysteryManager;
    }

    /** 获取无敌帧开关管理器。 */
    public IFrameManager getiFrameManager() {
        return this.iFrameManager;
    }

    /** 获取红圈生物禁令管理器。 */
    public MobBanManager getMobBanManager() {
        return this.mobBanManager;
    }

    /** 获取属性伤害（锈丸/炎上/还原 + 三选一互斥）管理器。 */
    public AttributeManager getAttributeManager() {
        return this.attributeManager;
    }

    /** 获取护甲商店（分类劫持 + 套装页）管理器。 */
    public ArmorShopManager getArmorShopManager() {
        return this.armorShopManager;
    }
}
