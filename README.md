# 星之迹闪现修复补丁（mimikko_radiant_displacer_fix）

> 本补丁用于修复兽耳助手「星之迹-级」的 AI 完全不使用战术系统「闪现」的问题。
> **发布已获得兽耳助手作者许可**；本补丁不修改游戏本体，也不改写兽耳助手本身的任何文件
> ——它在自己的 mod 目录内提供系统规格与脚本，卸载即完全恢复原版行为。

## 这个补丁做什么

把「星之迹-级」（`mimikko_kasutariri_radiant_1`，兽耳助手 Mimikko）的战术系统从原版
`displacer` 换成 **多模块舰 AI 兼容版闪现** `mimikko_kasutariri_radiant_displacer`，
让 AI（军官/无人舰）能正常使用闪现。

- 玩家手操**不受影响**（原本就正常）。
- 只改这一条船，不动原版 `displacer`，其它舰船行为不变。

## 当前方案（路线 B）：原版闪现本体 + 兼容多模块的 AI

**系统本体就是原版闪现**：`type: DISPLACER`、`range 300`、3 充能 / 回充 0.1 / 冷却 0、
硬幅能 / 不耗散、音效与抖动——与 vanilla `displacer` 逐字段相同
（`.system` 仅 `id` / `aiType` / `aiScript` 三项不同；`ship_systems.csv` 仅 `name` / `id` 不同）。

**AI 行为**：按 `voidsuper` 逐条复刻（每 0.25–0.5s 评估、落点 = 本体 + range×行驶方向、
系统门控、`loc11`/`loc14`、赶路 / 撤退 / 目标失能突进各路径），
**唯一有意改动**：

> 落点净空判定里，**同属主舰船（友军，含本舰自身模块）一律不再阻塞落点**。
> 原版规则是"同一原属主且挡在落点前方 30°、距离 < 半径和 + 500 → 否决"，
> 模块化舰船因此被自己的模块永久否决。

友军之外全部保留原版规则：跳过战机/穿梭舱；落点离敌舰 < 半径和 + 50 → 否决；
落点附近有敌方导弹 → 否决（原版还比较护盾覆盖与碰撞体，公开 API 不可得，此处按距离近似）。

**交付**：`mods/兽耳助手mimikko098v0.762/data/hulls/ship_data.csv` 星之迹行的 `system id`
→ `mimikko_kasutariri_radiant_displacer`（已备份）。

**回退**：把该行改回 `displacer`，或从
把该行改回 `displacer`（或从你自己的备份恢复）即可。

### 备选（路线 A）：原版 AI 驱动的瞬移系统

同目录另附 `mimikko_kasutariri_radiant_blink`（`type:TELEPORTER` + `aiType:PHASE_TELEPORTER_2`，数值照抄闪现）。
零自定义 AI 代码、完全由引擎驱动，但位移语义是"瞬移到 AI 选定目标点"。
切换只需把上述两处 `system id` 改成该 id。

## 根因（为什么原版 AI 不用闪现）

原版 `aiType: PHASE_DISPLACER` 的 AI 类是 `com.fs.starfarer.combat.ai.system.voidsuper`。
它每次激活系统前会做一次**落点净空判定**：

```java
// 遍历场上每一艘 Ship（只跳过战机/穿梭舱/自己）
if (dist(落点, s) < s.碰撞半径 + 本船碰撞半径 + 50)  return false;   // 判定①
if (该船前端30°锥内含落点 && !该船是残骸 && 本船原属主 == 该船属主)
    if (dist(落点, s) < s.碰撞半径 + 本船碰撞半径 + 500) return false; // 判定②
```

该循环**没有排除本舰自己的子模块**。而模块化舰船的模块是独立 `Ship` 实体，
由引擎 `CombatEngine.updateStationModuleLocations()` 定位/定向：

```java
module.getLocation().set(slot.computePosition(parent, moduleOffset));  // 挂点全在[0,0] → 与本体同位置
module.setFacing(slot.computeMidArcAngle(parent));                     // 与本体同朝向
```

于是对星之迹：

| 项 | 值 |
|---|---|
| 闪现落点距离 | **300**（`int.getDisplacerDestination()` = 本体位置 + 300×行驶方向；`range:300`） |
| 核心舰碰撞半径 | 200.5 |
| 5 个模块碰撞半径 | 154.5 / 159 / 165 / 179.5 / 181.5 |
| 模块与落点距离 | **300**（模块与本体重位） |
| 判定①阈值 | 154.5 + 200.5 + 50 = **405**（最大模块 432） |

**300 < 405 → 判定必然失败 → AI 每次都直接返回，永不激活系统。**
原版单舰 `radiant` 没有模块，落点干净，所以正常闪现。

参考：中文论坛已有两次同样报告（2025-01-27 / 2025-01-30，均无人解答）：
<https://www.fossic.org/thread-13760-1-1.html>

## 修复方式

新系统 `mimikko_kasutariri_radiant_displacer`：

```json
"type":"DISPLACER",
"aiType":"CUSTOM",                       // 必须 CUSTOM，引擎才会采用 aiScript
"aiScript":"data.scripts.mimikko_radiant_displacer_fix.MimikkoModularDisplacerAI",
"range":300,
```

自定义 AI（源码见 `src/`）逻辑与原版对齐（间隔 0.25–0.5s、同样的落点计算、
同样的判定①/②、目标距离 > range+200 时用于接近），
**唯独在净空判定中跳过本舰自身模块**（`s.getParentStation() == 本舰` 或在本舰
`getChildModulesCopy()` 内）。仅使用公开 API，不依赖混淆类。

### 主路径：每帧战斗插件（由 pickShipAI 注册，无前置条件）

`ModFixPlugin.pickShipAI(...)` 是引擎为**每艘船**创建 AI 时都会调用的 mod 钩子。
本补丁在里面注册一个 `EveryFrameCombatPlugin`（`DisplacerFixCombatPlugin`），
然后返回 `null`（**不接管舰船 AI**，原版行为不变）。

该插件每帧遍历战场，对"星之迹船系"（`mimikko_kasutariri_radiant*`，含模块）
或"装着本补丁系统的船"：

- 打印完整诊断（船体 / 系统 id / 舰船 AI 类 / `controlsLocked` / 模块数 / 弹药 / 目标距离 / 船插列表）；
- 若船上是闪现类系统（我们的或原版 `displacer`）、且不是玩家座舰，则按模块兼容规则驱动激活。

这条路径**不依赖**：舰船 AI 是不是 `BasicShipAI`、船插有没有真的挂上、系统 id 有没有换成功——
因此是最可靠的一层。

### 第二层：每帧船插驱动

实测发现：只靠 `.system` 的 `aiScript` **并不一定被驱动**（本机 0 条日志），因为
引擎只在 `BasicShipAI` 里创建"系统 AI"，而：

- 其它 mod 可以整条接管舰船 AI（`ModPlugin.pickShipAI`），此时 `BasicShipAI` 不存在；
- `BasicShipAI.advance()` 在 `ship.controlsLocked()` 为真时会**逐帧
  `blockCommandForOneFrame(USE_SYSTEM)`**，系统同样用不出来。

因此本补丁的主路径改为：给星之迹加一个**隐藏内置船插**
`mimikko_radiant_displacer_fix_hullmod` → `DisplacerFixHullMod`。
船插的 `advanceInCombat(ship, amount)` 由引擎**对每艘船每帧调用**，与"谁在开这条船"无关：

- 判定与原版 AI 对齐：系统空闲有弹药 → 落点净空（**跳过自身模块**）→ 目标超出 `range+200`；
- 每 3 秒打印一条诊断（`[displacerFix]`，见下）；
- 玩家自己的座舰不干预（由玩家手操）。

三者（船插 + `aiScript` + `ship_data.csv` 覆盖行）互相幂等，谁生效结果都一样。

### 诊断日志

`starsector.log` 中：

```
[displacerFix] <shipId> sys=... ai=<舰船 AI 类> controlsLocked=<bool> shipWithModules=<bool>
               ammo=3/3 active=false cd=0.0 target=<id>(d=1234)
[displacerFix] skip: <原因>            或   [displacerFix] USE SYSTEM -> active=true state=...
[displacerAI]  alive: ship=... system=... hasEngine=...      ← 仅当 aiScript 路径被驱动时才出现
```

`ai=` 与 `controlsLocked=` 这两项即可判定到底是"AI 被别的 mod 接管"还是"控制权被锁"。


### 系统 id 的指定方式（双保险）

同 id 的 `ship_data.csv` 行由多个 mod 提供时，引擎的合并**只保留"先被处理"的那一条**，
而处理顺序取决于目录读取顺序——本机实测**兽耳助手先于本补丁被处理，导致 CSV 行覆盖失败**
（日志里 `Applying data from ship_data.csv to [mimikko_kasutariri_radiant_1]` 只出现一次，
且系统仍是原版 `displacer`）。因此本补丁用两种方式指定，二者幂等：

1. **主路径（可靠）**：`ModFixPlugin.onApplicationLoad()` 里调用公开 API
   `ShipHullSpecAPI.setShipSystemId(...)`。时机在所有 spec 加载完成之后，与 mod 顺序无关。
2. **备用路径**：`data/hulls/ship_data.csv` 的覆盖行（若恰好胜出，结果一致）。

启动时日志会打印一行，可直接确认（`starsector.log`）：

```
INFO [...] data.scripts.mimikko_radiant_displacer_fix.ModFixPlugin - [onApplicationLoad]
    mimikko_kasutariri_radiant_1 ship system: <改前> -> mimikko_kasutariri_radiant_displacer
```

其中 `<改前>` 若为 `displacer`，即说明 CSV 行覆盖确实输了、由插件兜底。

### 判定逻辑：复刻原版 `voidsuper`（唯一改动 = 跳过自身模块）

判定/激活在 `DisplacerDriver`（战斗插件 / 船插 / aiScript 三层共用同一份）。

**保留的原版结构与条件**：

| 原版行为 | 本补丁 |
|---|---|
| 每 0.25–0.5s 评估一次（`IntervalTracker(0.25,0.5)`） | ✅ 一致 |
| 系统激活中 / 冷却中 / 无弹药 / 不可激活 → 返回 | ✅ 一致 |
| 落点 = 本体位置 + `range` × 行驶方向 | ✅ 一致 |
| 净空判定闸门（不可行即返回，所有路径共用） | ✅ 一致，**但跳过本舰自身模块** ← 唯一实质修改 |
| `loc14`：`range > randomRange + 200` | ✅ 一致 |
| `loc11`：移动 AI 期望航向 与 当前运动方向 夹角 < 20° | ⚠️ 近似：以 `MOVEMENT_DEST` 方向作为"期望航向" |
| ① 非交战 + 距命令目的地 > `range+500` → 赶路用 | ✅ 结构一致（`engaged` 以"是否有活目标"近似） |
| ② `isRetreating()` → 撤退用 | ✅ 一致 |
| ③ 危险规避（导弹/碰撞/护盾未覆盖） | ⚠️ 近似：改为"有来袭导弹靠近" |
| ④ 目标过载/排热 > 5 秒 → 突进 | ✅ 一致 |
| 充能保留 / 武器射程门控 / 额外激活间隔 | ❌ 已移除（原版没有，属上一版的臆造） |

> ⚠️ **API 陷阱（踩过）**：`CombatEngineAPI.isUIAutopilotOn()` **返回的是反面**
> （引擎实现为 `return !ui.isAutopilotOn();`，**手动飞行时为 true**）。
> 判定"玩家是否在自动驾驶"必须用 `CombatEngineAPI.getCombatUI().isAutopilotOn()`
> （原版 `NeuralLinkScript` 也是读它）。早期版本把前者当兜底，导致"手动飞行时反而自动闪现"。

**公开 API 拿不到的内部信号**（只能近似）：移动 AI 的期望航向、威胁评估 `D`、
碰撞追踪 `ooOO`、`BasicShipAI.isEngaged()`、`Ship.getShield()`。

> 为什么不能"直接让原版 AI 能调用"：否决逻辑在引擎类 `voidsuper` 的私有方法
> `Object(...)` 内部，mod 侧只有"整条替换 AI"的钩子（`aiScript` / `pickShipAI`），
> 无法修改它；而纯数据绕过要求模块碰撞半径 <49.5 且模块不朝向落点（会连带
> 翻掉模块自己的武器朝向），不可行。**引擎侧修复或自定义 AI 是仅有的两条路。**

## 启用 / 卸载

**启用**：在 `mods/enabled_mods.json` 的列表里加入 `"mimikko_radiant_displacer_fix"`，
**并放在 `"Mimikko"` 之后**（后加载者覆盖先加载者；新加的 mod 一般本来就在末尾）。

**卸载**：把该 id 从 `enabled_mods.json` 移除即可，或直接删除
`mods/mimikko_radiant_displacer_fix/` 整个目录。不修改游戏本体、不修改兽耳助手文件。

## 游戏内验证

> 改动 jar / 配置后**必须重启游戏**才生效（`onApplicationLoad` 只在启动时跑一次）。

1. **看日志（最可靠）**：`starsector.log` 里搜 `mimikko_radiant_displacer_fix`，应出现
   `... ship system: <改前> -> mimikko_kasutariri_radiant_displacer`。
   若 `<改前>` 是 `displacer`，说明 CSV 行覆盖输了、由插件兜底（正常）。
2. 装配界面看星之迹的战术系统名：应显示 **「闪现（模块兼容）」**（仍显示「闪现」= 未生效）。
3. 模拟战：星之迹交给 AI（或装军官），先让敌人保持在 500 单位以外 → 应能看到闪现突进。
4. 对照组：原版 `radiant` 行为不变。

## 重新编译

```bash
cd <游戏根目录>
JAVAC=/usr/lib/jvm/java-25-openjdk-amd64/bin/javac   # 需 JDK 17+
$JAVAC --release 17 -encoding UTF-8 \
  -cp "starfarer.api.jar:lwjgl.jar:lwjgl_util.jar" \
  -d build/classes \
  src/data/scripts/mimikko_radiant_displacer_fix/MimikkoModularDisplacerAI.java
# 然后把 build/classes/data/... 打进 jars/mimikko_radiant_displacer_fix.jar
```

## 文件清单

```
mod_info.json
jars/mimikko_radiant_displacer_fix.jar          # 编译后的 AI 类
src/data/scripts/mimikko_radiant_displacer_fix/MimikkoModularDisplacerAI.java
data/shipsystems/mimikko_kasutariri_radiant_displacer.system   # 新系统（照抄 displacer，改 id/aiType/aiScript）
data/shipsystems/ship_systems.csv               # 新系统的数值行（照抄 vanilla displacer 行）
data/hulls/ship_data.csv                        # 仅星之迹一行，system id 改为新系统
data/strings/descriptions.csv                   # 新系统的文本（照抄 displacer 文本）
```

> 编译产物只放在 `jars/` 里，mod 内不留 `build/`（避免被脚本扫描器误读）。
> 重编时用上面的命令输出到任意临时目录，再打进 jar 即可。
