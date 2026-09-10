# SekiroBedwar

> **免责声明**：SekiroBedwar 是独立开发的 Minecraft 插件，与 FromSoftware Inc.
> 及 Bandai Namco Entertainment Inc. 无任何关联。
> "Sekiro" 及相关商标归其各自所有者所有。

将《只狼：影逝二度》(Sekiro: Shadows Die Twice) 的战斗机制带入 **ScreamingBedWars** 的 Minecraft Spigot 插件。它不修改 BedWars 本体，而是通过 BedWars API 与事件系统叠加实现一整套「架势—弹反—崩条—处决」的决斗玩法，让 BedWars 中的两人对决变成只狼式的攻防博弈。

- **服务端版本**：Paper / Spigot `1.21.11`
- **语言 / 运行时**：Java 21
- **依赖**：ScreamingBedWars（软依赖，见「环境要求」）
- **命令 / 权限**：无。所有机制全自动、事件驱动，无需任何指令或权限配置
- **配置文件**：`plugins/SekiroBedwar/duel.yml`（唯一配置文件，所有模块共用）
- **作用域约定**：所有玩法功能**只在 BedWars 对局内生效**，对局外（大厅 / 等待房）完全按原版——唯一例外是大厅发放的《玩法指南书》（进入对局自动收回）

---

## 功能一览

### 决斗与架势

| 模块 | 说明 |
| --- | --- |
| **决斗触发** (`DuelTriggerManager`) | 敌对双方在岛屿上互相有效命中后自动触发决斗，附带红白双圈粒子、双方高亮特效 |
| **决斗生命周期** (`DuelManager`) | `PENDING → ACTIVE → ENDING` 状态机；第三方闯入结束决斗、单决斗互斥 |
| **架势系统** (`StanceManager`) | 每个玩家拥有当前/最大架势值；最大架势按背包资源计算；决斗期间 BossBar 互显对方、经验条显示自己 |
| **架势自然恢复** (`StanceRecoveryTask`) | 空闲时向满架势恢复，越接近满恢复越快；中毒 / 凋零 / 着火期间视为战斗活跃（不挂恢复） |
| **架势崩溃 / 崩条** (`StanceBreakManager`) | 临界时未弹反的近战命中 / 被弹反触发崩条，进入处决窗口与受击状态 |
| **普通格挡 / 受击架势** (`BlockManager`) | 无格挡命中扣除受击方架势；盾牌普通格挡不完全免架势；支持斧头破盾 |
| **完美弹反** (`ParryManager`) | 命中窗口内完整弹开攻击并重创攻击方架势；含网络延迟补偿 |
| **连续弹反封印** (`ParrySealManager`) | 连续被对方完美弹反 N 次后，一段时间内己方攻势无效 |
| **危攻击 / 识破** (`DangerManager`) | 矛 + 突进附魔 + 疾跑攻击为「危」，不可弹反、格挡即破盾；识破（下蹲 170ms 内接危）反制 |
| **决斗结算** (`SettlementManager`) | 各情形按比例把资源直接转入对方背包；玩家死亡一切物品不掉落（未转移部分清除）；第三方介入回滚快照 |
| **决斗区域限制** (`DuelAreaGuard`) | 决斗期间不能主动离开白圈、搭路越界、传送越界（击退位移豁免） |
| **决斗冻结** (`freeze`) | 白圈内物资刷新暂停、队伍复活挂起、决斗双方方块保护 |
| **红圈生物禁令** (`MobBanManager`) | 决斗红圈内禁止铁傀儡 / 羊（TNT 羊）以任何途径生成；圈内非玩家生物进圈即移除 |
| **无敌帧开关** (`IFrameManager`) | 原版受击保护帧双开关：对局内默认**有**、决斗期间默认**无**（拼刀连击不被保护帧吞伤害） |

### 忍具与强化

| 模块 | 说明 |
| --- | --- |
| **忍具商店** (`SekiroShopManager`) | 插件自管 GUI 商店（入口在 BedWars 商店主页右下角）：全部插件商品的判定、扣费、发放都在本插件内完成，动态价格实时渲染 |
| **剑攻速强化** (`SpeedManager`) | 单按钮逐级购买（按钮显示下一级，买后自动更新），降低近战攻击冷却 |
| **剑格挡** (`SwordBlockingManager`) | 1.21.8+ 给剑赋予盾牌格挡能力（blocks_attacks 组件，右键举盾、可被斧破盾） |
| **巴之雷** (`LightningManager`) | 两级购买（不可跳级）：三连击接跳斩落雷（L1）、忠诚三叉戟衔接（L2）、雷反；落雷消耗纸人；与锈丸 / 炎上三选一 |
| **锈丸** (`AttributeManager`) | 连击窗口内每击叠中毒（L1 中毒 I、L2 中毒 II + 凋零，每击 +10s）；30/34 金两级 |
| **炎上** (`AttributeManager`) | 窗口命中附加火焰；L2 窗口内同时叠凋零、非窗口 30% 概率直接点燃；30/34 金两级 |
| **还原** (`AttributeManager`) | 20 金清空巴之雷 / 锈丸 / 炎上进度（含收回三叉戟）重新三选一；已专精任一系后可购 |
| **纸人** (`PaperDollManager`) | 忍具系统通用资源：动态价格购买（按存活队伍数）、背包绑定上限、投掷消耗抵扣、命中后传送 |
| **漂流纸人** (`DriftingPaperDollManager`) | 消耗品：血量 > 50% 时右键 → 血量上限减半 + 得 5 纸人（可超上限） |
| **盾牌弹反** (`DeflectManager`) | 持盾右键扣 2 纸人 → 2s 内来袭近战全部按完美弹反处理 → 窗口结束强制收盾 |
| **雾璃鸦** (`CrowManager`) | 绑定末影之眼（每局限购 1，消耗/死亡后自动补发）；右键激活头顶悬停 2s，期间首次受玩家伤害免伤并传送到攻击方身后 |
| **风弹** (`WindChargeManager`) | 投掷归为投掷物（耗 1 纸人、物品退还，命中后传送同规则）；释放瞬间面前掀起半椭圆爆风墙（TNT 特效左→右扫过 1s + 停留 1s），触碰者 0.5s 内不能防御与攻击，6s 内不可叠加 |
| **佛珠** (`BeadManager`) | 单局上限 4 次、每次 +5 最大血量、价格递增，全局增益 |
| **僵尸头颅 / 恐怖条** (`TerrorManager`) | 击杀 50% 掉头颅；左键使用施加反胃 + 扣血 + 隐藏恐怖值累积，恐怖满瞬秒 |

### 秘传（详见「核心玩法机制」）

| 模块 | 说明 |
| --- | --- |
| **秘传·飞渡浮舟** (`FeiduFuzhou`) | 七段节奏连击（7/10/6/5/6/16 tick ±0.3）：第 3 击起落地音 + 1s 防击退（刷新）；第 6 击有效命中额外 −10 架势；全连达成奖 2 纸人 + 5 架势 + 1 HP |
| **秘传·苇名十字斩** (`YamedoCrossSlash`) | 空手 0.5~1s 换刀起手二连（两击间隔 4 tick ±0.5）：第二击有效命中 → 击退 II + 对方 −7 架势 + 自身 +3 架势 |
| **秘传·龙闪** (`LongShan`) | 空手 ≥1s 换刀后左键释放（耗 2 纸人）：面朝方向音波柱扫出，命中 2 HP + 10 架势，1 秒后同方向补射第二波 |
| **秘传·一心七连** (`IsshinSevenStrike`) | 七段节奏连击（目标 7/5/5/6/8/10 拍），第 3 击起落地音 + 1s 防击退（刷新）、有效命中逐段叠加架势增伤；**第 7 击必须是危攻击**；全连达成奖 2 纸人 + 4 架势 |

### 商店与装备保障

| 模块 | 说明 |
| --- | --- |
| **护甲商店** (`ArmorShopManager`) | 商店主页「护甲」分类直达自管套装页：一键购买盔/胸/腿/靴并立即穿上，皮革染队伍色；档位制逐级向上替换 |
| **自动装备** (`AutoEquipManager`) | BedWars 商店购买盔/胸/腿/靴后自动从背包穿到身上（原穿着物退回背包） |
| **取消耐久** (`DurabilityGuardManager`) | 按类别开关：护甲 / 工具武器 / 盾牌，对局内不再损失耐久 |
| **玩法指南书** (`WelcomeManager`) | 进入服务器或离开对局回到大厅时发放 18 页成书（一页一主题：架势 / 弹反 / 危 / 决斗 / 忍具 / 四式秘传 / 心法）；进入对局自动收回 |

---

## 核心玩法机制

### 架势（Posture）

每个玩家拥有当前架势值与最大架势值。最大架势按背包携带的资源计算：

```
Smax = base + multiplier × W
W    = Σ (coeff × ln(1 + count))      // count 为背包中该资源数量
```

默认即 `W = ln(1+铁) + 3·ln(1+金) + 10·ln(1+钻石) + 20·ln(1+绿宝石)`。架势为消耗制，`current` 从 `max` 扣到 `0` 即进入临界状态。

### 弹反与格挡

- **完美弹反**：举盾后的命中窗口内挡下攻击，完整弹开（免伤害与击退），并按 `Dbase × parry-attacker-multiplier` 重创攻击方架势。
- **普通格挡**：盾牌格挡但未命中完美窗口，防守方仍扣 `Dbase × defender-multiplier` 架势（不完全免架势）。
- **无格挡命中**：受击方扣 `Dactual × hit-multiplier` 架势（`Dactual` 为护甲减伤后的实机伤害）。
- **破盾**：斧头命中普通格挡造成更高架势扣减并短暂禁用格挡。

### 崩条与处决

架势耗尽后进入临界状态（不会自动崩条）。临界中满足以下任一条件即崩条：

1. 未完美弹反对方的**近战**攻击（普通格挡或无格挡命中）；
2. 自己的近战攻击被对方完美弹反。

崩条 = 当前架势清零 + 进入处决 / 逃离窗口（`execution-seconds`）+ 短暂受击状态（`stagger`，期间无法格挡）。窗口内击杀被处决者、或窗口到期未击杀，均按崩条结算比例（`break-kill-ratio`，默认 50%）转移败者资源并结束决斗。

### 危攻击与识破

主手持矛（1.21.11 新增的 SPEAR 武器）+ `LUNGE`（突进）附魔并**疾跑**时攻击 = 「危」攻击：不可被完美弹反，格挡它会被破盾并扣防守方架势；识破（下蹲后 170ms 内接危）可免疫危伤害并反扣攻击方架势。

### 盾牌弹反

持盾右键（主 / 副手任一，即时扣 2 纸人；对容器、门等可交互方块的右键不触发）→ 2 秒内来袭的每一记近战命中都按**完美弹反**完整弹开，且不受「举盾后 170ms 窗口」与「一次按住只弹反一击」限制 → 窗口结束强制解除举盾（可再举盾再付纸人开新窗）。危攻击不可弹反；弓箭不参与；窗口内免疫恐怖区负面。

### 属性伤害专精（巴之雷 / 锈丸 / 炎上 / 还原）

巴之雷、锈丸、炎上为**三选一专精**：购买升级其中任意一系后，另外两系被封锁（树内仍逐级 Lv.1→Lv.2）。「还原」（20 金，需已专精任一系）清空三系进度并收回巴之雷 L2 三叉戟，可重新选择。

锈丸 / 炎上的触发结构与巴之雷三连击一致：连续 3 次近战攻击未被对方完美弹反（间隔 ≤1.5s，被完美弹反清零）→ 开启 5 秒属性窗口，窗口内每一记未被弹反的命中附加效果并续窗。

- **锈丸**：Lv.1 命中叠中毒 I（每次 +10s）；Lv.2 升级为中毒 II + 凋零（各 +10s）。
- **炎上**：Lv.1 窗口命中施加火焰附加（+4s 燃烧）；Lv.2 窗口命中在火焰上同时叠加凋零，且**非窗口期**未被弹反的命中有 30% 概率直接点燃对方。
- **巴之雷**：L1 三连击后空中攻击命中即落雷；L2 附赠忠诚三叉戟，远程命中衔接落雷；被雷击者可在窗口内「置空接雷」雷反，恢复自身并返还对方架势。

窗口开启期间，自己的经验条等级数字倒数剩余秒（与巴之雷跳击窗口同一位置显示）。

### 秘传与无敌帧

四式秘传**全局生效、无需购买**，按节奏打出近战连击即可触发（浮舟 / 一心 / 苇名的命中判定发生在 ACTIVE 决斗内，龙闪可在任意场景释放）。共同规则：

- **节奏判定**：相邻两击按**服务器 tick 数拍距**（`|Δtick − 目标拍| ≤ ceil(容差拍)`，对 TPS 波动稳定，按拍录制可精确复现）。被完美弹反的命中仍算打出的一击，连段继续；任何一击脱拍则打磨音提示、本击作为新的一式重连。
- **空手换刀式**（苇名 / 龙闪）：先持续空手再快捷栏换持近战武器进入「已武装」，起手动作须在换刀后的**衔接窗**（`mystery.arm-connect-ticks`，默认 4 拍）内完成；两式空手时长窗口互斥——0.5~1s 归苇名，≥1s 归龙闪。
- **音效语言**：成功接段 = 铁砧落地声，脱拍 = 铁砧打磨声（一次连续脱拍只播一次打磨）；同一玩家并行多式时按**领先者发声**——只有进度领先的式出声，落后的式静默重开。飞渡浮舟与一心七连均自**第三击**起每段成功播落地音。
- **防击退护身**（飞渡浮舟 / 一心七连）：自第三击起，每次成功命中获得 / **刷新** 1 秒防击退（不叠加；只抵消实体来源的击退位移，伤害照常结算）。时长 `mystery.knockback-guard-seconds` 可改。
- **长间隔窗口**（如 2→3、6→7 之间）允许右键格挡或投掷投掷物——这些动作不产生近战命中，不进序列也不打断。

**无敌帧**：原版受击保护帧的双开关——`iframe.global-enabled`（对局内非决斗，默认 true）与 `iframe.duel-enabled`（决斗期间，默认 false）。决斗中拼刀的快节奏连击不被保护帧吞伤害，是秘传节奏成立的前提；对局外一律原版行为。

#### 第一式·飞渡浮舟

七段近战连击，相邻命中拍距依次 **7 / 10 / 6 / 5 / 6 / 16 拍**。

- **第 6 击有效命中**（未被完美弹反）：受击方在普通架势换算外**额外 −10 架势**。
- **第 3 击起**每段成功播铁砧落地音，并刷新 1 秒防击退。
- **七击全部达标**：攻击方得 **2 纸人**（可超持有上限）**+ 恢复 5 架势 + 1 HP** + 完成音。

#### 第二式·苇名十字斩

空手蓄势 0.5~1 秒后换持近战武器，起手第一击须在衔接窗（默认 4 拍）内命中起计时，**第二击命中拍距 4 拍 ±1 拍**。

- **第二段为有效攻击**：受击方被**击退**（等同原版击退 II）+ 架势**额外 −7**，自身架势**恢复 +3** + 落地音。
- 第二击脱拍或被弹反 → 二连终结，须重新空手换刀再起。

#### 第三式·龙闪

空手 ≥1 秒后换持近战武器，**左键挥臂**释放（不占用普通攻击）：消耗 **2 纸人** + 落地音，朝面朝方向放出一道监守者音波式**飞行波柱**——判定路径覆盖脚位起向上 4 格，**飞出决斗白圈时消散**。

- 碰到的玩家（释放者除外）受 **2 HP 直接伤害**（不可格挡 / 弹反）+ **10 架势伤害**，每波每人一次。
- 第一波放出后隔 **1 秒**，在**同一触发位置、同一方向**自动补射第二波（不二次扣纸人）。
- 纸人不足则低音提示、解除武装。

#### 第四式·一心七连

七段近战连击，相邻命中拍距依次 **7 / 5 / 5 / 6 / 8 / 10 拍**。

- **第 3 击起**每段成功播落地音并刷新 1 秒防击退；自第 3 段起，有效命中的段让受击方被**逐段递增**额外扣架势：−3、−6、−9、−12（按已叠有效段数 ×3；被弹反的段不叠加但连段继续）。
- **第 7 击必须是危攻击**（矛 + 突进 + 疾跑）——非危的第七击不算完成全段（本击作为新一式重连）。
- **完成全段**：奖 **2 纸人 + 恢复 4 架势** + 完成音。

### 风弹与爆风墙

忍具商店购买（默认 10 金）获得绑定风弹（不可丢弃 / 入容器）。投掷每次消耗 1 纸人并退还物品；命中目标后 2 秒内左键可再耗 1 纸人传送——与普通投掷物同规则。

风弹的特殊之处在于**释放瞬间**于面前掀起一面「爆风墙」：以面朝方向为短轴 1.5 格、两侧各为长轴 2.5 格、高 3 格的**半椭圆面**。TNT 爆炸粒子从**左手侧向右手侧扫过 1 秒**，扫完后整面**停留 1 秒**。粒子存在期间，触碰到墙面的玩家（释放者本人除外）**0.5 秒内不能防御与攻击**——不能防御 = 强制收盾、禁止再举盾（含剑格挡）、决斗内进入「无法格挡」；不能攻击 = 一切对玩家的攻击无效化。同一玩家 **6 秒内不可叠加**。

### 雾璃鸦

忍具商店购买（默认 20 金）获得绑定的末影之眼，每人每局限购 1 只（按购买次数判定）。右键即激活（消耗 1 雾璃鸦 + 2 纸人，拦截原版投掷、眼不飞出）：悬停在头顶 2 秒，期间受到的**第一次玩家来源伤害**（近战或弓箭）被完全取消，并把玩家传送到攻击方身后的安全位置，随后破碎；到时未受击也破碎。激活消耗后 10 秒自动补发一只，本局购买过的玩家死亡后同样起补发计时。

### 死亡与资源转移

玩家死亡一切物品不掉落：需要转移的资源（铁 / 金 / 钻 / 绿宝石）直接放入对方背包，其余物品（含按比例未转移的剩余份额、背包装不下的溢出）直接清除。决斗内按崩条 / 普通 / 虚空各情形比例结算；非决斗被玩家击杀全额转移；第三方介入回滚到决斗开始快照。

### 忍具商店

全部插件商品（剑攻速 / 巴之雷 / 锈丸 / 炎上 / 还原 / 长矛 / 纸人 / 漂流纸人 / 雾璃鸦 / 风弹 / 佛珠 / 盾牌）集中在自管 GUI，属性三系与其「还原」相邻排列。入口位于 BedWars 商店主页右下角（原「下一页」按钮位；分类子页的翻页不受影响），点击即开。门槛、货币校验、扣费、发放在本插件内完成，纸人动态价 / 佛珠递增价如实显示与校验；剑攻速与巴之雷各为**单个按钮**——显示下一级效果与价格，购买后自动更新（逐级不可跳级）。购买失败（货币不足 / 已满级 / 达上限）只提示不扣费。GUI 右下角固定**「返回商店」按钮**，点击回到 BedWars 默认商店。

### 护甲商店与装备保障

商店主页「护甲」分类直达**自管套装页**——皮革（1 铁）/ 锁链（40 铁）/ 铁（12 金）/ 钻石（6 绿）四档，一键购买盔 / 胸 / 腿 / 靴四件并**立即穿上**（皮革自动染队伍色）。**档位制**：只能购买高于已拥有档位的套装，升级时销毁身上旧甲再穿新（不掉落不退回），同档 / 低档入口锁定；档位按局记录。经 BedWars 商店购买的任何散件盔 / 胸 / 腿 / 靴也会**自动穿到身上**（原穿着物退回背包）。另有**取消耐久**开关组（`no-durability`）：护甲默认不再磨损；工具与盾牌可分别开关（盾牌默认保留原版磨损，破盾机制本身与耐久无关）。

### 佛珠

忍具商店购买（单局上限 4 次、价格 4→6→8→10 钻递增，递增价在 GUI 实时显示），每次 +5 最大血量（全局增益，重生后保留）。

### 僵尸头颅与恐怖条

击杀玩家 50% 概率获得僵尸头颅；左键使用（耗 3 纸人 + 1 头颅）→ 以自身为圆心半径 3 内 2 秒施加反胃 + 每秒扣血 + 隐藏恐怖值累积；恐怖条满 100 无论血量直接瞬秒。处于盾牌弹反窗口内的玩家免疫该区所有负面。

---

## 环境要求

- Java **21**
- Paper / Spigot **1.21.11**（兼容 1.21.11+）
- **ScreamingBedWars** 插件（软依赖：存在时决斗、忍具商店、冻结等玩法生效；不存在时插件其余逻辑仍可安全加载）

---

## 构建

```bash
mvn clean package
```

构建产物为 `target/SekiroBedwar-1.0-SNAPSHOT.jar`（已通过 Maven Shade 打包，可直接部署）。

---

## 安装

1. 将构建产物 `SekiroBedwar-1.0-SNAPSHOT.jar` 放入服务器 `plugins/` 目录（**勿同时保留两份同名插件 jar**，否则服务器会按文件名排序加载旧的一份）。
2. 确保已安装 ScreamingBedWars。
3. 启动服务器，插件会自动在 `plugins/SekiroBedwar/duel.yml` 生成默认配置。
4. 按需编辑 `duel.yml` 后重启服务器生效（忍具商店 GUI 内的价格 / 门槛每次打开实时读取，无需重启）。

> 提示：升级自旧版本时，插件启动会自动清理 `shop.yml` 中残留的历史注入块；由于 BedWars 的商店缓存构建早于该清理，换 jar 后的第一次重启商店可能仍显示旧注入项一次，第二次重启即为最终状态（或直接删除 `plugins/ScreamingBedWars/shop/shop.yml` 让 BedWars 重建）。

---

## 配置说明

所有配置集中在 `plugins/SekiroBedwar/duel.yml`，按模块分区，附详细中文注释。主要分区：

| 配置段 | 作用 |
| --- | --- |
| `stance` | 架势系统：最大架势公式、临界线、崩条 / 处决窗口、自然恢复、BossBar / 经验条显示 |
| `parry` | 完美弹反：窗口、架势影响、延迟补偿、音效反馈、连续弹反封印 |
| `block` | 普通格挡 / 受击架势与破盾 |
| `settlement` | 四种结算情形的资源转移比例（死亡不掉落，转移直达对方背包） |
| （触发）`radius` / `attack-recall-seconds` / `duel-cooldown-seconds` 等 | 决斗触发条件 |
| `visuals` | 红白双圈粒子、高亮 |
| `duel` / `area` | 决斗生命周期与区域限制 |
| `freeze` | 物资刷新冻结、队伍复活冻结、方块保护 |
| `mob-ban` | 红圈生物禁令（开关、禁生生物列表、进圈清除、垂直容差、巡检间隔） |
| `sword-speed` | 剑攻速强化等级与价格 |
| `sword-blocking` | 剑格挡 |
| `lightning` | 巴之雷（雷击、三连击、三叉戟、雷反、商店价格） |
| `rust` / `burn` | 锈丸 / 炎上（两级价格、连击数、间隔、窗口、效果时长与概率） |
| `restore` | 还原（价格、图标） |
| `paper-doll` | 纸人（价格、背包上限、投掷抵扣、命中传送、落雷消耗、漂流纸人） |
| `deflect` | 盾牌弹反（纸人消耗、窗口时长、盾牌价格） |
| `danger` | 危攻击 / 识破（架势惩罚、识破窗口、破盾时长、长矛价格） |
| `iframe` | 无敌帧开关（对局内 / 决斗期间双开关 + 巡检间隔） |
| `mystery` | 秘传框架：`arm-connect-ticks` 换刀衔接窗、`knockback-guard-seconds` 第三击起防击退时长；四式各自一段（`fei-du-fu-zhou` / `yamedo-cross-slash` / `long-shan` / `isshin-seven-strike`：间隔序列、容差、加成与奖励数值） |
| `bead` | 佛珠（购买上限、每颗血量、价格递增） |
| `crow` | 雾璃鸦（价格、纸人消耗、悬停时长、破碎概率、限购、补给冷却） |
| `wind-charge` | 风弹（价格、半椭圆几何、扫过与停留时长、粒子、触碰半径、封印时长、不可叠加窗口） |
| `auto-equip` | 购买护甲自动装备开关 |
| `armor-shop` | 护甲商店（分类名、GUI 标题、套装档位材质 / 货币 / 价格） |
| `no-durability` | 取消耐久：总开关 + armor / tools / shield 三类别独立开关 |
| `welcome` | 玩法指南书（开关、成书名） |
| `terror` | 僵尸头颅 / 恐怖条（掉落率、纸人消耗、半径、恐怖值 / 衰减） |
| `shop` | 忍具商店（入口接管开关、入口物品、GUI 标题、返回按钮） |
| `islands` | 可选的决斗岛屿白名单（留空 = 任意实心地面都允许决斗） |

完整配置项与默认值见 `src/main/resources/duel.yml`。

---

## 项目结构

```
src/main/java/org/alpha/sekiroBedwar/
├── SekiroBedwar.java        # 插件主类：模块装配与生命周期
├── api/                     # 公共 API：只读查询 + 事件（外部插件依赖入口）
│   └── internal/            # API 内部实现（发射器与快照转换）
├── armory/                  # 护甲商店（分类劫持 + 套装页 + 档位制）
├── attribute/               # 属性伤害专精（锈丸 / 炎上 / 还原，三选一互斥）
├── bead/                    # 佛珠
├── block/                   # 普通格挡 / 受击架势 / 破盾
├── combat/                  # 战斗工具（攻击方解析、面板伤害、对局作用域）
├── crow/                    # 雾璃鸦
├── danger/                  # 危攻击 / 识破
├── deflect/                 # 盾牌弹反（纸人完美弹反窗口）
├── duel/                    # 决斗触发、生命周期、区域限制、结算、视觉、生物禁令支撑
├── equip/                   # 自动装备 + 取消耐久
├── event/                   # 决斗内部事件（DuelTriggered / DuelEnded）
├── freeze/                  # 物资刷新冻结、复活冻结、方块保护
├── lightning/               # 巴之雷（落雷 / 雷反 / 三连击 / 三叉戟）
├── mobban/                  # 红圈生物禁令
├── mystery/                 # 秘传（无敌帧开关 + 武技框架 + 四式：浮舟/苇名/龙闪/一心）
├── paperdoll/               # 纸人 / 漂流纸人
├── parry/                   # 完美弹反、延迟补偿、连续弹反封印
├── shop/                    # 忍具商店（自管 GUI 宿主）
├── speed/                   # 剑攻速强化
├── stance/                  # 架势系统（状态、BossBar、经验条、恢复、崩条）
├── swordblock/              # 剑格挡（blocks_attacks）
├── terror/                  # 僵尸头颅 / 恐怖条
├── windcharge/              # 风弹（爆风墙）
└── welcome/                 # 玩法指南书
```

---

## 公共 API（供外部插件开发）

SekiroBedwar 是**战斗规则核心**：它只产生稳定的战斗事件与只读查询；连段统计、使用率 / 成功率、排位 / MMR、匹配、排行榜、录像、打法评价等业务**一律不写在核心内**——外部插件（如未来的 `SekiroBedwar-Stats` / `SekiroBedwar-Rank` / `SekiroBedwar-Replay`）监听事件自行实现，统计公式如何演进都不反向牵动核心。

**依赖方式**：外部插件 `plugin.yml` 写 `depend: [SekiroBedwar]`，编译期引用本 jar，只用 `org.alpha.sekiroBedwar.api` 包。所有事件只读、不可取消——外部无法绕过核心规则改变战斗。

### 只读查询（`SekiroBedwarApi`）

| 方法 | 用途 |
| --- | --- |
| `isInDuel(Player / UUID)` | 是否在决斗中 |
| `duelOf(...)` / `duelOpponentOf(...)` / `activeDuels()` | 决斗只读快照（双方 / 对手 / 阶段 / 时长 / 结束原因） |
| `stanceOf(Player / UUID)` | 架势只读快照（当前 / 最大 / 阶段 / 禁格挡），无写入口 |
| `techniques()` / `technique(key)` | 全部秘传 id（核心四式 + 外部注册）；按键查询 |
| `registerTechnique(owner, key, name)` / `unregisterTechnique(...)` | 外部插件注册 / 注销自己的秘传 |
| `tickClock()` | 核心 tick 时基：外部秘传节奏判定直接数拍（与 TPS 波动无关，免自建计数器） |
| `setComboProgress(id, player, hits)` / `topProgressFor(id, player)` | 外部秘传上报连段进度 / 查询其他式的领先进度（槽位随完成 / 失败 / 取消自动回收） |
| `playTechniqueCue(id, player, TechniqueCue)` | 统一发声：SUCCESS = 铁砧落地 / FAIL = 铁砧打磨，核心按**领先者发声**规则决定是否出声 |
| `tools()` | 忍具 id 列表 |

### 事件（`api.events`，主线程广播）

| 组 | 事件 |
| --- | --- |
| 决斗 | `DuelStartEvent` / `DuelEndEvent` / `DuelInterruptEvent` / `DuelDeathEvent` / `DuelSettlementEvent`（转移比例 + 到手资源明细，或回滚） |
| 架势 | `StanceChangeEvent` / `StanceBreakEvent`（含对手）/ `StanceCriticalEnterEvent` |
| 战斗 | `PerfectParryEvent` / `BlockEvent` / `ShieldBreakEvent`（AXE / DANGER）/ `HitLandedEvent` / `DangerAttackEvent` / `MikiriEvent` |
| 秘传 | `SecretTechniqueStartEvent` / `HitEvent`（hitIndex / parried / target）/ `CompleteEvent` / `FailEvent`（脱拍 / 衔接超时 / 资源不足 / 终结不满足）/ `BranchEvent`（派生预留）/ `CancelEvent` |
| 忍具 | `ShinobiToolUseEvent`（`ToolId` + target + `ToolUseResult`） |

**扩展约定**：新增秘传——核心内置或外部注册——都复用同一组 `SecretTechnique*Event`；消费方按 id 匹配并对未知值 `default` 兜底即可零改动兼容。

### 示例一：外部插件添加秘传

```java
public final class SekiroMysteryExtra extends JavaPlugin {
    private TechniqueId myArt;

    @Override
    public void onEnable() {
        myArt = SekiroBedwarApi.registerTechnique(this, "kagerou-shin", "阳炎身");
    }

    int comboHits = 0;
    private int lastHitTick = -99;

    // 节奏判定直接用核心 tick 时基数拍，发声走统一 cue——与核心四式共享
    // 「领先者发声」视野（并行多式混音问题自动消解）：
    private void onMyStageHit(Player p, int targetBeats, int toleranceBeats) {
        int now = SekiroBedwarApi.tickClock();
        if (Math.abs((now - lastHitTick) - targetBeats) <= toleranceBeats) {
            lastHitTick = now;
            comboHits++;
            SekiroBedwarApi.setComboProgress(myArt, p.getUniqueId(), comboHits);
            SekiroBedwarApi.playTechniqueCue(myArt, p, TechniqueCue.SUCCESS);
            Bukkit.getPluginManager().callEvent(new SecretTechniqueHitEvent(
                    p.getUniqueId(), myArt,
                    SekiroBedwarApi.duelOf(p).map(DuelInfo::id).orElse(null),
                    comboHits, false, p.getUniqueId()));
        } else {
            comboHits = 0;
            lastHitTick = now; // 本击作为新一式第一击
            SekiroBedwarApi.playTechniqueCue(myArt, p, TechniqueCue.FAIL); // FAIL 自动回收进度槽
        }
    }
    // 启动 / 完成 / 失败同样广播 SecretTechniqueStartEvent / CompleteEvent / FailEvent

    @Override
    public void onDisable() {
        SekiroBedwarApi.unregisterTechnique(this, myArt);
    }
}
```

外部秘传**只发事件与 cue、不改玩家状态**（架势 / 资源等数值只能经核心规则变化）；节奏请走 `tickClock()` 数拍、发声请走 `playTechniqueCue`——不要自 playSound，这样服务器秘传的音效语言（落地 = 成功、打磨 = 脱拍、领先者出声）对全体秘传一致。

### 示例二：连段成功率统计插件

```java
public final class SekiroBedwarStats extends JavaPlugin {
    @EventHandler
    public void onTechStart(SecretTechniqueStartEvent e) {
        counter(e.player(), e.technique()).start++;
    }

    @EventHandler
    public void onTechComplete(SecretTechniqueCompleteEvent e) {
        var c = counter(e.player(), e.technique());
        c.complete++;
        c.validHits += e.validHits();          // 成功率 = complete / start
    }

    @EventHandler
    public void onTechFail(SecretTechniqueFailEvent e) {
        counter(e.player(), e.technique()).fail++;
        // e.reason(): OUT_OF_RHYTHM / CONNECT_TIMEOUT / FINISHER_NOT_MET / INSUFFICIENT_RESOURCE
        // e.reachedHits(): 平均连段长度
    }

    @EventHandler
    public void onSettle(DuelSettlementEvent e) {
        // e.winner()/loser()/ratio()/received() → 排位 MMR
    }
}
```

查询侧随时可用 `SekiroBedwarApi.duelOf(player)` / `stanceOf(player)` 读取当前决斗与架势快照（如录像插件按 tick 采样 `StanceSnapshot` 回放架势曲线）。

---

## 支持开发

如果这个项目对你有帮助，欢迎通过以下方式支持：

- 爱发电：https://ifdian.net/a/7478t

所有捐赠均为**自愿赠与**，不换取任何服务、功能或商业授权。

---

## 许可证

本项目基于 MIT 许可证开源。

```
Copyright (c) 2026 b站zhenshicai

特此免费授予任何获得本软件及相关文档文件（“软件”）副本的人不受限制地处理本软件的权利，
包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或出售本软件副本的权利，
并允许本软件被提供给他人，只要对方遵守以下条件：

上述版权声明和本许可声明应包含在本软件的所有副本或实质部分中。

本软件按“原样”提供，不附带任何明示或暗示的保证，包括但不限于对适销性、
特定用途的适用性和非侵权性的保证。在任何情况下，作者或版权持有人均不对任何索赔、
损害或其他责任负责，无论是在合同、侵权或其他方面，因本软件或本软件的使用或其他交易而产生、由此产生或与之相关。
```
