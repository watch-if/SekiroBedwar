# SekiroBedwar

> **免责声明**：SekiroBedwar 是独立开发的 Minecraft 插件，与 FromSoftware Inc.
> 及 Bandai Namco Entertainment Inc. 无任何关联。
> "Sekiro" 及相关商标归其各自所有者所有。

将《只狼：影逝二度》(Sekiro: Shadows Die Twice) 的战斗机制带入 **ScreamingBedWars** 的 Minecraft Spigot 插件。它不修改 BedWars 本体，而是通过 BedWars API 与事件系统叠加实现一整套「架势—弹反—崩条—处决」的决斗玩法，让床战中的两人对决变成只狼式的攻防博弈。

- **服务端版本**：Paper / Spigot `1.21.8`
- **语言 / 运行时**：Java 21
- **依赖**：ScreamingBedWars（软依赖，见下文）
- **命令 / 权限**：无。所有机制全自动、事件驱动，无需任何指令或权限配置
- **配置文件**：`plugins/SekiroBedwar/duel.yml`（唯一配置文件，所有模块共用）

---

## 功能一览

| 模块 | 说明 |
| --- | --- |
| **决斗触发** (`DuelTriggerManager`) | 敌对双方在岛屿上互相有效命中后自动触发决斗，附带红白双圈粒子、双方高亮特效 |
| **决斗生命周期** (`DuelManager`) | `PENDING → ACTIVE → ENDING` 状态机；第三方闯入结束决斗、单决斗互斥 |
| **架势系统** (`StanceManager`) | 每个玩家拥有当前/最大架势值；最大架势按背包资源计算；决斗期间 BossBar 互显对方、经验条显示自己 |
| **架势自然恢复** (`StanceRecoveryTask`) | 空闲时逐 tick 向满架势恢复，越接近满恢复越快 |
| **普通格挡 / 受击架势** (`BlockManager`) | 无格挡命中扣除受击方架势；盾牌普通格挡不完全免架势；支持斧头破盾 |
| **完美弹反** (`ParryManager`) | 命中窗口内完整弹开攻击并重创攻击方架势；含网络延迟补偿 |
| **连续弹反封印** (`ParrySealManager`) | 连续被对方完美弹反 N 次后，一段时间内己方攻势无效 |
| **架势崩溃 / 崩条** (`StanceBreakManager`) | 临界时未弹反的近战命中 / 被弹反触发崩条，进入处决窗口与受击状态 |
| **决斗结算** (`SettlementManager`) | 崩条/普通/虚空三种情形按比例转移物品；第三方介入回滚资源快照 |
| **决斗区域限制** (`DuelAreaGuard`) | 决斗期间不能主动离开白圈、搭路越界、传送越界（击退位移豁免） |
| **决斗冻结** (`freeze`) | 白圈内物资刷新暂停、队伍复活挂起、决斗双方方块保护 |
| **剑攻速强化** (`SpeedManager`) | 商店购买、等级化，降低近战攻击冷却（幂等注入 shop.yml） |
| **剑格挡** (`SwordBlockingManager`) | 1.21.8+ 给剑赋予盾牌格挡能力（blocks_attacks 组件，右键举盾、可被斧破盾） |
| **巴之雷** (`LightningManager`) | 商店两级购买：三连击接跳斩落雷（L1）、忠诚三叉戟衔接（L2）、雷反；落雷消耗纸人 |
| **纸人** (`PaperDollManager`) | 忍具系统铺垫资源：动态价格购买、背包绑定上限、抛投物消耗、投掷命中传送 |

---

## 核心玩法机制

### 架势（Posture）

每个玩家拥有当前架势值与最大架势值。最大架势按背包携带的资源计算：

```
Smax = base + multiplier × W
W    = Σ (coeff × ln(1 + count))      // count 为背包中该资源数量
```

默认即 `W = ln(1+铁) + 3·ln(1+金) + 10·ln(1+钻石) + 10·ln(1+绿宝石)`。架势为消耗制，`current` 从 `max` 扣到 `0` 即进入临界状态。

### 弹反与格挡

- **完美弹反**：举盾后的命中窗口内挡下攻击，完整弹开（免伤害与击退），并按 `Dbase × parry-attacker-multiplier` 重创攻击方架势。
- **普通格挡**：盾牌格挡但未命中完美窗口，防守方仍扣 `Dbase × defender-multiplier` 架势（不完全免架势）。
- **无格挡命中**：受击方扣 `Dactual × hit-multiplier` 架势（`Dactual` 为护甲减伤后的实机伤害）。
- **破盾**：斧头命中普通格挡造成更高架势扣减并短暂禁用格挡。

### 崩条与处决

架势耗尽后进入临界状态（不会自动崩条）。临界中满足以下任一条件即崩条：

1. 未完美弹反对方的**近战**攻击（普通格挡或无格挡命中）；
2. 自己的近战攻击被对方完美弹反。

崩条 = 当前架势清零 + 进入处决/逃离窗口（`execution-seconds`）+ 短暂受击状态（`stagger`，期间无法格挡）。处决者可在窗口内击杀被处决者获得全额结算；窗口到期未击杀则只能结算对方一半物资。

---

## 环境要求

- Java **21**
- Paper / Spigot **1.21.8**（兼容 1.21.8+）
- **ScreamingBedWars** 插件（软依赖：存在时决斗/冻结/商店注入等功能生效，不存在时插件其余逻辑仍可加载）

---

## 构建

```bash
mvn clean package
```

构建产物为 `target/SekiroBedwar-1.0-SNAPSHOT.jar`（已通过 Maven Shade 打包，可直接部署）。

---

## 安装

1. 将构建产物 `SekiroBedwar-1.0-SNAPSHOT.jar` 放入服务器 `plugins/` 目录。
2. 确保已安装 ScreamingBedWars。
3. 启动服务器，插件会自动在 `plugins/SekiroBedwar/duel.yml` 生成默认配置。
4. 按需编辑 `duel.yml` 后重启服务器生效。

> 提示：`sword-speed` 的商店注入仅在启动时执行一次，改动相关配置需重启服务器。

---

## 配置说明

所有配置集中在 `plugins/SekiroBedwar/duel.yml`，按模块分区，且附有详细中文注释。主要分区：

| 配置段 | 作用 |
| --- | --- |
| `stance` | 架势系统：最大架势公式、临界线、崩条/处决窗口、自然恢复、BossBar/经验条显示 |
| `parry` | 完美弹反：窗口、架势影响、延迟补偿、音效反馈、连续弹反封印 |
| `block` | 普通格挡/受击架势与破盾 |
| `settlement` | 四种结算情形的物品转移比例 |
| （触发）`radius` / `attack-recall-seconds` / `duel-cooldown-seconds` 等 | 决斗触发条件 |
| `visuals` | 红白双圈粒子、高亮 |
| `duel` / `area` | 决斗生命周期与区域限制 |
| `freeze` | 物资刷新冻结、队伍复活冻结、方块保护 |
| `sword-speed` | 剑攻速强化等级、价格 |
| `sword-blocking` | 剑格挡 |
| `lightning` | 巴之雷（雷击、三连击、三叉戟、雷反、商店价格） |
| `paper-doll` | 纸人（价格、背包上限、抛投物绑定、命中传送、巴之雷消耗） |
| `islands` | 可选的决斗岛屿白名单（留空 = 任意实心地面都允许决斗） |

完整配置项与默认值见 `src/main/resources/duel.yml`。

---

## 项目结构

```
src/main/java/org/alpha/sekiroBedwar/
├── SekiroBedwar.java        # 插件主类：模块装配与生命周期
├── block/                   # 普通格挡 / 受击架势
├── combat/                  # 战斗工具（武器面板伤害等）
├── duel/                    # 决斗触发、生命周期、区域限制、结算、视觉
├── event/                   # DuelTriggeredEvent / DuelEndedEvent 自定义事件
├── freeze/                  # 物资刷新冻结、复活冻结、方块保护
├── lightning/               # 巴之雷（雷击 / 雷反）
├── paperdoll/               # 纸人（忍具系统铺垫资源）
├── parry/                   # 完美弹反、延迟补偿、连续弹反封印
├── speed/                   # 剑攻速强化（商店注入）
├── stance/                  # 架势系统（状态、BossBar、经验条、恢复、崩条）
└── swordblock/              # 剑格挡（1.21.2+）
```

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

