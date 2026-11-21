# 可调配重方块（ccpe:ballast）可行性方案

> 状态：**方案定稿（含用户实测补充），未实现**。等待用户说开工再实现。
> 需求来源：用户询问能否做出「游戏内动态调整重量」的配重方块（先要可行性方案，不要代码）。
> 用户实测补充：原版栅栏门「关闭有碰撞/质量、打开无碰撞/质量为 0」的状态切换在**飞行中可正常生效** → 印证 blockstate 级质量变更链路可用（含飞行态）。
> 用户设定档位：15 个离散档位 × 0.25 单位质量/档，状态 0..15 → 质量 0..3.75（状态 0 = 0 质量，状态 15 = 3.75）。

## 一句话结论

**可行，但只能离散档位调、不能连续任意值调**。重量是 Sable 的 BlockState 级静态属性（reload 时烘焙进 BlockState 对象），Sable 唯一原生支持的质量变更触发点是「方块状态变化」。因此做成「多档位状态方块 + 游戏内切换状态」是最干净稳定的路线；每实例 NBT 连续质量需 mixin Sable 内部，不推荐。

## 机制调研证据（Sable 2.0.3 源码，参考来源）

- 数据包：`PhysicsBlockPropertiesDefinitionLoader`（`SimpleJsonResourceReloadListener`，目录名 `physics_block_properties`）加载 `data/<ns>/physics_block_properties/*.json`，每条 = `{selector(方块或 tag), priority, properties:{sable:mass,...}, overrides?}`；`overrides` 键为 blockstate 属性条件字符串（如 `"level=5"`）。
  - 文件：`api/sable-common-1.21.1-2.0.3-sources/dev/ryanhcode/sable/physics/config/block_properties/PhysicsBlockPropertiesDefinition{,.java}Loader.java`、`BlockStateConditionSet.java`
- 缓存：reload 时对 selector 命中方块的每个可能 BlockState 调用 `BlockStateMixin.sable$loadProperties`，属性值直接存进 BlockState 对象（`sable$properties[]`）。→ 质量与坐标/BE/NBT 无关，纯由 blockstate 决定。
  - 文件：`.../mixin/block_properties/BlockStateMixin.java`、`mixinterface/block_properties/BlockStateExtension.java`
- 读取：`PhysicsBlockPropertyHelper.getMass(level,pos,state)`，方块须实心（`VoxelNeighborhoodState.isSolid`，非实心质量强制 0）→ 配重块必须全实心方块。
  - 文件：`.../physics/config/block_properties/PhysicsBlockPropertyHelper.java`、`physics/chunk/VoxelNeighborhoodState.java`
- 触发：Sable mixin `LevelChunk.setBlockState` → `SableCommonEvents.handleBlockChange` → `SubLevelPhysicsSystem.handleBlockChange` → `updateMassDataFromBlockChange(old,new)` 对比新旧质量差 → `MassTracker.addBlockMass(pos,±Δm)` → `MergedMassTracker.update()` 重算总质量/质心/惯量张量并稳定 pipeline pose。
  - 文件：`.../sublevel/system/SubLevelPhysicsSystem.java`（L458/516）、`sublevel/ServerSubLevel.java`（updateMergedMassData）、`api/physics/mass/MergedMassTracker.java`、`mixin/plot/LevelChunkMixin.java`
- 结论推论：同一方块不同 blockstate 配不同 `sable:mass` 后，游戏内任何服务端 setBlock 换状态 → Sable 立即按质量差更新整机质量与质心；`ccpe.sensor_system.getPhysicsMass/getPhysicsCenterOfMassRel` 直读 MassTracker，自动反映新值。

## 实测证据（用户提供，2025 现场观察）

- 原版栅栏门：关闭状态有碰撞 → 计质量；打开状态无碰撞 → `isSolid=false` → Sable 质量强制为 0。开/关切换即「质量 0 ↔ 非 0」的 blockstate 变更。
- 该切换**在飞行中正常使用**（飞行中子次元内做 blockstate 变更、Sable 质量更新链路照常生效）→ 解决了此前「飞行中是否可调」的悬置问题：**机制在飞行态可用**。
- 注：栅栏门靠碰撞有无产生质量差，配重块靠数据包 override 产生质量差；二者到达 `updateMassDataFromBlockChange` 的路径完全相同，故预期同样适用（仍需用真实方块做最终验收）。

## 项目内契合点（低风险依据）

- Lua `setLights` 已实现「运行时改写 Sable plot 内 blockstate」（position light LIT）→ 服务端改状态进 Sable 链路已验证。
- 频道/外设/服务端权威全套可照抄 `ControlDeskBlockEntity` + `ControlDeskPeripheral`（物理体作用域频道空间 = `ShortRangeLinkerRegistry`），无新抽象。
- 新增方块走 `RegistrateBlocks` runData 流程（见 `registrate-datagen.md` 三个坑）。

## 定稿设计（用户已确认：CC Lua 外设；15 档 × 0.25；飞行中可调）

| 项 | 决定 |
|---|---|
| 方块 | `ccpe:ballast`，全实心（16 个状态全部实心，保证每档质量都被 Sable 计入），blockstate 仅 `level`(IntegerProperty 0..15)，无朝向/水浸变体 |
| 档位质量 | `physics_block_properties/ballast.json`：16 条 override，`mass(level) = level × 0.25` → level=0 → 0.0，level=15 → 3.75（单位 = `sable:mass` 单位，传感器 API 按 kg 显示；如显示单位需换算只改 JSON 值） |
| 数据流 | Lua → 服务端 BE（权威）→ setBlock 切 `level` → Sable 自动重算质量/质心（停场与飞行同一路径，飞行可用性已被栅栏门实测佐证） |
| 寻址 | 物理体作用域频道（同 controlDesk），`ccpe.sensor_system.getPeripheral(ch)` 获取 |
| 外设 | type `"ccpe:ballast"`，`setLevel(n)`（0..15 钳制）/ `getLevel()` / `getMass()`（当前档质量），mainThread=true 服务端权威（照抄 Throttle setFreeMode/setAxis） |
| 默认 | 放置即 level=0（0 质量）；不提供右键/GUI，纯 Lua 驱动 |

## 实施文件清单（开工时用）

1. `src/main/resources/data/ccpe/physics_block_properties/ballast.json`（新）
2. `RegistrateBlocks.java` 注册（照 red_position_light；纯属性方块无需模型变体）
3. `block/BallastBlock.java`（新，BaseEntityBlock + level 属性）
4. `block/BallastBlockEntity.java`（新，频道注册 onLoad/setRemoved/20tick 复核 + setLevel 服务端应用 + NBT/updateTag）
5. `compat/cc/BallastPeripheral.java`（新）+ 注册进作用域频道 registry 查询路径
6. 可能的新 payload（若 updateTag 同步不够再定）

## 开放点 / 待验收

- **最终验收测试（开工后）**：停场与飞行中各调几档，确认 `getPhysicsMass`/质心 API 同步变化、机体姿态/配平符合预期、切换瞬间无可见抖动。
- 档位值密度：3.75 上限/0.25 步进为当前设定；后续只需改 JSON（新增/细分 override）即可扩档，不动代码。
- 质量单位换算若需与传感器显示（kg）对齐，改 JSON 数值即可。

## 不推荐路线 B（备忘）

NBT 连续质量需 mixin `PhysicsBlockPropertyHelper`/`MassTracker` 调用链（Sable 无 per-instance 概念），且 Sable 只在 blockstate 变化时重算，光改 BE NBT 不生效，还得人为触发 setBlock —— 高耦合高成本，放弃。

---

## 补充调研（2026-11-21 14:55，Sable 2.0.5 源码逐行核对）——**方块质心到底怎么算**

> 起因：用户问「Sable 是不是按方块选择框几何中心算重心」。**答案：是按「碰撞形状」，不是选择框**；而且有官方接口可以自定义。

### 质量与质心的完整链路

| 环节 | 事实 | 源码 |
|---|---|---|
| 质量来源 | **BlockState 级静态属性** `sable:mass`，数据包 `data/<ns>/physics_block_properties/*.json`（`selector` + `overrides` 按 blockstate 条件）| `physics/config/block_properties/PhysicsBlockPropertiesDefinitionLoader.java` |
| 质量默认值 | **1.0**（任何实心方块不写 JSON 就是 1.0 kg）| `PhysicsBlockPropertyTypes.java:28` |
| 是否计数 | `VoxelNeighborhoodState.isSolid` = **碰撞形状非空**（`getCollisionShape`；**不是** `getShape`/选择框）。非实心 ⇒ `getMass` 直接 `return 0.0` | `physics/chunk/VoxelNeighborhoodState.java:46`、`PhysicsBlockPropertyHelper.java:24-33` |
| **质心** | `MassTracker.BLOCK_CENTER_OF_MASS`（静态、按 `BlockState.hashCode()` **memo**，每次 reload 重算）：① 空气 → (0.5,0.5,0.5)；② **方块实现 `BlockSubLevelCustomCenterOfMass` → 用方块自己给的值**；③ 碰撞形状是整块 → (0.5,0.5,0.5)；④ 否则 = **碰撞形状 AABB ∩ 单位立方 的中心**；⑤ 空碰撞形状 → (0.5,0.5,0.5)（但质量为 0，进不了累加）| `api/physics/mass/MassTracker.java:33-61` |
| 自定义质心接口 | `BlockSubLevelCustomCenterOfMass#getCenterOfMass(BlockGetter, BlockState)` → 返回**相对方块下限角**的 Vector3dc，按 blockstate 烘焙 | `api/block/BlockSubLevelCustomCenterOfMass.java` |
| 惯量 | `sable:inertia`（Vec3）按对角线 × 质量；不填按立方体 `m/6` | `MassTracker.addBlockInertia` |
| 累加 | `centerOfMass += blockMass × (blockPos + BLOCK_CENTER_OF_MASS)`，再 `moveCenterOfMass` 平移惯量张量 | `MassTracker.java:197-243` |

**两条容易踩的推论：**

1. **模型小 ≠ 质心偏**。只有**碰撞形状**小才会偏；碰撞形状是整块时，哪怕模型只有 1/8，质心也在方块中心。
2. **质心是 blockstate 的纯函数**（memo 按 state hash 缓存）⇒ 不能用 BE/NBT/坐标算质心，只能编码进 blockstate。

### 本模组自己的数据（已完成核对）

| 方块 | `sable:mass` | 碰撞形状 | 实际质心（北向基准，相对方块下限角） |
|---|---|---|---|
| `ccpe:my_control_desk` | **0.25** | `box(0,0,8,16,8,16)` = 1/4 块 | **(0.5, 0.25, 0.75)** = 方块中心下 0.25、+Z 0.25 |
| `ccpe:thrust_nozzle` | 0.5 | 整块 | 方块中心 |
| `ccpe:aic` | 0.0 | — | 不计数 |

- ⇒ 用户的直觉**对了**：1/4 块形状的控制台确实把 0.25 kg 放在偏离方块中心 0.25 格的位置，单台贡献 `0.25×0.25 = 0.0625 kg·m` 的配平量（11 kg 机体 ≈ **5.7 mm** 质心位移）。
- ⇒ 也解释了用户的抱怨：想用它配平就必须**只放一台**（两台朝向差 180° 会互相抵消），既占一个频道又难看。

## 配重块定稿方向修正：**「质量档位 × 朝向」= 亚格质心微调**

原方案（只调质量、质心在方块中心）只能做**1 格粒度**的配平，对 11 kg 机体是 ~9% 的质心跳变，太粗。用上面的接口即可免费升级：

| 项 | 设计 |
|---|---|
| 方块 | `ccpe:ballast`，**碰撞形状整块**（保证 `isSolid`、且视觉对称——外观偏不偏与质心无关了） |
| 状态 A | `level` 0..15（IntegerProperty）→ 质量 = `level × 0.25` kg（JSON `overrides`） |
| 状态 B | `FACING` 6 向（或 4 水平向）→ **质心 = 方块中心 + facing × 0.25**（`BlockSubLevelCustomCenterOfMass`）|
| 效果 | 一块方块同时给出「质量」和「质心方向」，配平量 = `level×0.25 × 0.25` ⇒ 步进 **0.0625 kg·m ≈ 5.7 mm 质心**，15 档 ≈ 8.5 cm 行程 |
| 状态数 | 16 × 6 = **96**（blockstate JSON 全部指向同一 cube 模型，成本可忽略；无 BE、无外设、无频道） |
| 为什么用接口而不是半块碰撞 | 半块碰撞会把这格变成 Sable 的 `CORNER` 邻域状态，影响碰撞/浮力判定，而且外观必然有缺口；接口只改质心，物理与外观都干净 |

**结论（对「四发很难做到前后对称」的回答）**：不需要几何上造得对称。**
四台喷口按 `±a / ±b` 精确镜像摆放（这个能做到），剩下的质量分布不对称用**一个**配重块按「档位 + 朝向」补掉即可；
配重块是纯结构方块，**不占频道、不需要 Lua**（如要 Lua 调档再按原方案加外设）。

