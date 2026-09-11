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
