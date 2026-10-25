# 定向喷管（`ccpe:thrust_nozzle`）工作记录

> 状态：**代码逻辑完成，已进游戏验证**（推力方向已实测校定；排气粒子已换成自研 `ccpe:exhaust`，见 §3.6）。缺合成配方；模型/贴图由用户完成。
> 粒子当前形态：自研粒子（世界坐标、无重力、扫掠碰撞 + 撞墙反弹/滑行、撞地变大）；**撞地分裂已按用户要求关闭**，`USE_EXHAUST_PARTICLE=false` 可整体回退旧 `create:air`。
> 粒子随寿命演变（v7，§3.6）：**越老越大 / 越老越慢 / 越老越透明**，三条曲线都由 `life = age/lifetime` 驱动。
> 出力随气压缩放（§2.1.1）：`THRUST_PER_RPM = 1` ⇒ **1 RPM = 1 pN**，气压 1.0 时 256 RPM = 256 pN/管。
> **远距离看不见粒子已解决**（§3.7）：根因是原版 32 格 `addParticle` 硬限制，改用 9 参数 `addAlwaysVisibleParticle(ignoreRange=true)`。
> **高空尾迹（contrail）已实现**（v1，2026-08）：自研粒子 `ccpe:contrail`，只在冷空气里、按移动距离发射，
> 无碰撞/无重力/原地随机飘逸。代码完成 + 编译通过，**待进游戏验证**，细节与测试清单见 §9。
> 上游背景：悬浮车自动驾驶的推进方案见 `memo/flip-autopilot.md`（火箭推力功率不生效 ⇒ 放弃火箭，改自研定向喷管）。
> 相关：`memo/my_sensor_system.md`、`memo/api-code-map.md`。

## 0. 需求与决策链（用户定稿）

| 议题 | 结论 |
|---|---|
| 动力来源 | 仍装在 `create:encased_fan` 出风面，**力由风扇转速决定**（不新增 CC 写入，Lua 脚本一行不改） |
| 力的模型 | **纯轴向推力**：不做射线、不看表面 ⇒ 任意**距离**出力相同；**乘气压**（§2.1.1）⇒ 高度有影响 |
| 强度 | **1 RPM = 1 pN**（`THRUST_PER_RPM = 1`）⇒ 气压 1.0 时 256 RPM 单管 **256 pN**。用户第三次定稿，图公式简单；初版 800 pN 实测"高达 750 多"过猛，200 pN 偏软 |
| 方块名 | `ccpe:thrust_nozzle` |
| mixin | **不需要**（见 §1） |

## 1. 源码结论：为什么不需要 mixin

### 1.1 Create 侧（`api/create-1.21.1-6.0.10-280-sources/.../kinetics/fan/`）

- `NozzleBlock`：纯方向方块，`canSurvive` 要求背后是 `IAirCurrentSource` 且
  `getAirflowOriginSide() == FACING`（`EncasedFanBlockEntity.java:73-76` 直接返回 `FACING`）。
- `NozzleBlockEntity`：**对物理体零作用**，只对原版实体 `setDeltaMovement(+ pushVec·1/32)`（108-112 行）；
  `range = source.getMaxDistance()`。对 Sable 物理体出力的其实是 Sable 的 mixin。

### 1.2 Sable 侧（`references/sable-main/neoforge/.../mixin/compatibility/create/nozzle/`）

- 运行时已装的三件套：`NozzleHoveringMixin`（实现 `BlockEntitySubLevelActor`）、
  `NozzleHoveringHelper`（射线 + 力公式）、`NozzleBEFixesMixin`（子次元坐标修正）。
- 力公式：13 根射线（6 根水平伞 + 6 根 45° + 1 根轴向），射线长 **6.4 格**，3.2 格内满功率，
  `force = 5 · scale · softSpeed(>128 RPM 时 128+(rpm-128)/4) · airPressure · Σp/13`。
- **实测反解**：256 RPM ⇒ `softSpeed = 160` ⇒ 理论上限 800 pN；实测 319.46 pN ⇒ `Σp/13 ≈ 0.4`
  （即旧喷嘴只用出理论 40%，其余丢在射线打空/打斜）。这也正好对应 §0 的 800 pN 标定基准。

### 1.3 为什么不能"继承 + 改数"

- `getFanMagnitudeCalculation` 是 **private static**，射线数 `N=13` 做分母，`rayPoints` 是 mixin 的
  `@Unique` 字段 ⇒ 子类拿不到任何改写点；
- Sable 的力逻辑住在 **mixin 类**里，Mixin 不能再 mixin 另一个 mixin；
- 本项目已有 3 个 `BlockEntitySubLevelActor` 实现（`MyBearingBlockEntity`、`MyBearingPlateBlockEntity`、
  `TrailingWheelMountBlockEntity`）⇒ 自研方块 + BE 的成本最低、且完全可控。

### 1.4 重要发现：装喷嘴后风扇自身推力归零

`fans_provide_force/EncasedFanBlockEntityMixin.java:27-30,57-60`：Sable 的螺旋桨推力
`isActive() = !blocked && |propSpeed| > 0.01`，其中 `blocked = !level.getBlockState(pos.relative(FACING)).isAir()`。
喷管恰好占住那一格 ⇒ **风扇那一路推力恒为 0**，总推力 = 喷管这一路，数值可预测、不会叠加。
旧喷嘴的 "68 pN → 319 pN（4.68 倍）" 本质是"关掉机制 A、启用机制 B"，不是放大。

## 2. 实现

| 文件 | 内容 |
|---|---|
| `src/main/java/com/zzy205/myfirstmod/block/ThrustNozzleBlock.java` | `DirectionalBlock` + `EntityBlock`；`FACING` = 点击面；`canSurvive` / `neighborChanged` 照抄 `create:nozzle`；六向选择框/碰撞盒；**仅客户端** `BlockEntityTicker` |
| `src/main/java/com/zzy205/myfirstmod/block/ThrustNozzleBlockEntity.java` | `BlockEntity implements BlockEntitySubLevelActor`；`sable$physicsTick` 施加轴向点力；`tickClient` 喷粒子 |
| `block/MyModBlocks.java` | 注册 `ccpe:thrust_nozzle`（`registerBlocks` 自动带 BlockItem + 创造栏），音效 `SoundType.COPPER` |
| `block/MyModBlockEntities.java` | 注册 `thrust_nozzle` BE |
| `assets/ccpe/blockstates/thrust_nozzle.json` / `models/item/` / `models/block/`（用户 Blockbench 模型）/ `textures/block/thrust_nozzle.png` | 资源；blockstate 未旋转变体 = `facing=up` |
| `data/ccpe/loot_table/blocks/thrust_nozzle.json` | 挖掘掉落 |
| `data/ccpe/physics_block_properties/thrust_nozzle.json` | `sable:mass = 1.0`、`sable:volume = 0.5`（对齐 memo 里"每个喷嘴 1 kg"） |
| `assets/ccpe/lang/en_us|zh_cn.json` | Directional Thrust Nozzle / 定向喷管 |

### 2.1 力模型（`sable$physicsTick`）

```
pressure  = DimensionPhysicsData.getAirPressure(level, worldCenterOf(nozzle))  // 海平面 1.0
magnitude = (REVERSE_SIGN ? -1 : 1) · THRUST_PER_RPM · fanRpm · pressure · timeStep   // pN·s（含符号）
impulse   = -facingNormal · magnitude                                        // plot/机体局部系
group.applyAndRecordPointForce(centerOf(worldPosition), impulse)             // sable:propulsion 力组
```

- `THRUST_PER_RPM = 1.0 pN/RPM` ⇒ **出力数值 = 转速数值**（256 RPM ⇒ 256 pN），无需换算；
  **线性**（没有 Sable 的 >128 RPM 软缩放），
  保持 memo §6.5 已验证的"推力 ∝ 转速"手感。
- `REVERSE_SIGN = false` 是**进游戏实测校定的正确值**：正转速 ⇒ 机体沿 **-FACING** 受力。
  喷口 `facing=down`（装车底）时：**正转速 = 上升/刹车，负转速 = 下压/贴地**，与旧喷嘴"正推反吸"一致；
  喷管随车身翻转，倒挂时"负转速 = 吸向头顶赛道"的语义不变。
- **力向量与作用点必须每次新建**：`QueuedForceGroup.applyImpulseAtPoint` 会**就地改写传入的 position**
  （减去质心求力臂），`recordPointForce` 又保留引用（`FlightDataRecorder` 遥测会读）；复用临时向量会污染数据。

#### 2.1.1 气压项（取数口径与 Sable 原版喷嘴逐位一致）

```
pressure = DimensionPhysicsData.getAirPressure(level,
             parentSubLevel.logicalPose().transformPosition(atCenterOf(fbe.getBlockPos())))
```

即 `NozzleHoveringHelper.java:173` 的写法，本项目搬进 `ThrustNozzleBlockEntity#airPressureAt(ServerSubLevel)`。

- 必须用**世界坐标**：气压是 y 的函数，而 BE 的 `worldPosition` 是 plot 坐标（§3.5），
  所以先 `logicalPose().transformPosition(...)` 投到世界系再取。漏这一步会取到 plot 里的 y，
  气压会在装配/移动瞬间乱跳。
- 开关 `THRUST_SCALES_WITH_AIR_PRESSURE`（现 true）、指数 `AIR_PRESSURE_EXPONENT`（现 1.0 = 纯线性乘）。
- 异常/非有限值一律退回 1.0：NaN 冲量进物理步会把物理体直接打飞。
- 曲线形状（`DimensionPhysics#createDefault`，无 datapack 覆盖时）：海平面 1.0、向上 `e^(−0.004·Δy)`
  指数稀薄、地底夹到 1.5、接近建筑限高最后 40 格平滑掉到 0。

#### 2.1.2 力组与施力

- 力组解析：**不能 `import ForceGroups`**（其静态字段类型是 veil 的 `RegistryObject`，veil 不在编译
  classpath，`FlightDataRecorder.java:320-323` 已踩过）；改用
  `ResourceKey.createRegistryKey(rl("sable","force_groups"))` + `registry.get(rl("sable","propulsion"))`。
- 打点力自带力臂 ⇒ 多喷管位置差自动产生力矩（滚转/俯仰），无需额外处理。

### 2.2 出力表（40.25 kg 车，重量 442.8 pN）

| 转速 | 单管 | 4 管 | ÷重量 |
|---|---|---|---|
| 256（100%） | 256 pN | 1024 pN | 2.31× |
| 192（75%） | 192 | 768 | 1.73× |
| 128（50%） | 128 | 512 | 1.16× |
| 102（40%） | 102 | 408 | 0.92× |

- 悬停油门（气压 1.0）：**4 管 ≈ 43%**（442.8/1024，≈111 RPM）、3 管 ≈ 58%、2 管 ≈ 87%。
- **单管永远飞不起来**：1.5 气压（地底）也只有 384 pN < 442.8 pN。至少 2 管，实务上 4 管。

**气压随高度（§2.1.1，主世界海平面 y=63，估算值）**

| y | 气压 | 单管 256 RPM | 4 管 | 说明 |
|---|---|---|---|---|
| ≤ −38 | 1.50 | 384 pN | 1536 | 地底饱和（夹到 1.5） |
| 63 | 1.00 | 256 | 1024 | 海平面 = 标定基准 |
| 163 | 0.67 | 172 | 686 | 悬停需 ≈65% 油门 |
| 213 | 0.55 | 141 | 564 | 悬停需 ≈79% 油门 |
| 263 | 0.45 | 115 | 461 | 悬停需 ≈96% 油门 |
| 273 | ≈0.432 | 111 | 443 | **4 管满载的升限（刚好 = 重量）** |
| 280 | 0.42 | 108 | 430 | 限高衰减段起点 |
| 320 | 0 | 0 | 0 | 建筑限高 |

- ⇒ **实用升限**（估算，4 管满载）：**y ≈ 273**（800 pN 版 ≈305、200 pN 版 ≈210）。
- ⇒ 悬停油门变成高度的函数：**43% 只在海平面成立**，y≈163 时 ≈65%、y≈213 时 ≈79%、y≈263 时 ≈96%，
  y≳273 直接失去悬停能力。`flip-autopilot.md` 里按 14% 标定的控制律增益/死区**必须按高度重标**，
  或者让脚本读 `sensor` 的气压做前馈（`SensorSystemAPI` 已暴露气压，见 `memo/my_sensor_system.md`）。
- 左右差动（各 2 管、r=2 m、I≈160 kg·m²）：**单边满速** ⇒ τ = 2×256×2 = 1024 N·m ⇒ α_roll ≈ **6.4 rad/s²
  —— 刚好压在 `flip-autopilot.md` 的翻滚预算 6.4 线上**（余量为 0，姿态机动的偏差修正会很紧）；
  想留余量就**双边反向差动**（一侧 +256、另一侧 −256）：τ = (512+512)×2 = 2048 N·m ⇒ α_roll ≈ **12.8 rad/s²**。
- 落地：（4 管 1024 pN）/40.25 ≈ **25.4 m/s²**，吃掉 10 m/s 需 **0.39 s / 2.0 m**
  （800 pN 版 0.13 s / 0.65 m）⇒ FLARE 段的刹车距离预算按 2.0 m 排。

### 2.3 选择框 / 碰撞箱（六向）

- 基准盒（`facing=up` 未旋转模型帧，用户定稿）：`(1,12,1,15,15,15)` 顶盖 + `(2,0,2,14,12,14)` 筒身，
  与 Blockbench 模型的 `body` 元素一致。
- 六向由 `rotateX`/`rotateY`（每 90° 的 AABB 映射）生成，与 `blockstates/thrust_nozzle.json` 的
  x/y 旋转**严格对应**：up 原样、down = x180、north = x90、east/south/west = north 再绕 Y 转 90/180/270。
  变换链照抄 `AicBlock`（已验证的案例）。
- `rotateX` 1 圈：`(x,y,z) → (x, z, 1−y)`；`rotateY` 1 圈：`(x,y,z) → (1−z, y, x)`。
- 生成结果（px）：up `(1,12,1,15,15,15)+(2,0,2,14,12,14)`；down `(1,1,1,15,4,15)+(2,4,2,14,16,14)`；
  north `(1,1,1,15,15,4)+(2,2,4,14,14,16)`；east `(12,1,1,15,15,15)+(0,2,2,12,14,14)`；
  south `(1,1,12,15,15,15)+(2,2,0,14,14,12)`；west `(1,1,1,4,15,15)+(4,2,2,16,14,14)`。
- 选择框与实体碰撞共用 `getShape`（`getCollisionShape` 默认转发），Sable 的 VoxelColliderData 也走它。

## 3. 粒子特效（三次迭代，别重踩）

### 3.1 平台事实（源码）

- `create:air`（`AirParticle`）：**位置插值**粒子（起点 → 起点+位移，按 `lifetime` 走完），
  `hasPhysics = false` **完全不碰撞**；`lifetime = 位移长度/speed`（整数，**硬上限 60**），
  `progress = (age/lifetime)^drag`；`AirParticleData` 只暴露 `drag`/`speed`，
  颜色、尺寸、8 帧精灵图全部写死。Sable 有 `AirParticleMixin`（`tick` 里按子次元姿态重投影）。
- `create:air_flow`（`AirFlowParticle`，电扇自己用的）：方向/速度/`bounds` 全部由风扇
  `AirCurrent` 决定，`tick` 里做 `bounds.contains` 与 `maxDistance` 判定（61-78 行）。
- `AirCurrent.getFlowLimit()`（229-257 行）：沿 `FACING` 逐格看 `getCollisionShape()`，
  **遇第一个挡路方块就把 `maxDistance` 截断**；`shouldAlwaysPass` = 是否在 `create:fan_transparent` 标签里。
- 推论：喷管占住风扇前一格 ⇒ `maxDistance` 从 24 被截到 **~1.03 格**，
  `air_flow` 粒子速度掉到 ~0.06 格/tick 且几乎贴脸。

### 3.2 迭代历史

| 版本 | 做法 | 结果 |
|---|---|---|
| v1 | `create:air`，从喷口截面随机撒点 + 自定速度/寿命 | 可用（现行基础） |
| v2 | 完全复刻 `create:air_flow` + 把喷管加进 `create:fan_transparent` 标签，每 tick 追加 N 颗 | **观感不好，已回退**（标签还会让电扇的实体推力/批量加工穿过喷管生效） |
| v3 | 在 v1 上加**遮挡射线** | 现行基础 |
| v4 | 遮挡射线改到**世界系**（主世界方块才挡得住，§3.4） | 现行 |
| v5 | **自研粒子 `ccpe:exhaust`** + 扫掠碰撞 + 撞墙反弹/滑行 + 撞地变大（§3.6） | **现行**（第一版碰撞坐标系写错⇒撞墙无反应；后又修：出生点保护、碰撞冷却；撞地分裂已关闭） |
| v6 | 光照：`IGNORE_LIGHTMAP` 全亮渲染（§3.6 光照节） | 现行（修「黄黄的粒子」） |
| v7 | 随寿命演变：越大 / 越慢 / 越透明三条单调曲线（§3.6） | 现行 |

### 3.3 当前参数（用户在 v1 基础上调过）

| 常量 | 现值 | 说明 |
|---|---|---|
| `MAX_PARTICLES_PER_TICK` | **10** | 满转速每 tick 颗粒数，按 `|rpm|/256` 缩放（同屏 ≈ 本值 × 寿命） |
| `PARTICLE_SPEED` | **1.0** | 平均速度（块/tick = 20 块/秒） |
| `PARTICLE_DRAG` | 0.7 | <1 = 出膛快、尾段减速 |
| `PARTICLE_TRAVEL_MIN/MAX` | **6.0 / 10.0** | 位移长度 ⇒ 寿命 = 长度/speed ≈ **6~10 tick** |
| `MIN_PARTICLE_TRAVEL` | 0.2 | 射线可走距离短于它就整段不生成 |
| `PARTICLES_BLOCKED_BY_OTHER_BODIES` | true | 其它物理体是否也挡粒子 |
| `INVERT_PARTICLE_FLOW` | false | 纯视觉翻向，不动物理 |

排气方向 = 机体受力的反向 = `+FACING · sign(rpm)`（随 `REVERSE_SIGN` 自动一致）。

### 3.4 遮挡（v4：射线改到世界系后主世界方块才挡得住）

- 每 tick 沿排气方向打**一条**射线（`ClipContext.Block.COLLIDER` + `Fluid.NONE`，
  语义 = 电扇的 `getFlowLimit`：只挡实体碰撞形状、不挡水/空气），
  每颗粒子位移 = `min(随机位移, clear)`；`clear < MIN_PARTICLE_TRAVEL` ⇒ 不出粒子。
- 成本：每喷管每 tick 1 条射线（客户端），不是每颗粒子一条。
- **射线必须打在世界系**（v4 修正）。Sable 的 `mixin/clip_overwrite/BlockGetterMixin.java:48-134`：
  一旦 `from` 落在某个 plot 里，就把 `from` 投到世界系，然后 ①对**主世界**跑一次 `originalClip`
  （这一支返回的 `getLocation()` 是**世界系**），②再逐个物理体把射线反投回各自局部系
  （这一支返回的是**那个物理体**的局部系）。所以在 plot 系里发射线时：主世界命中会被当成
  局部系坐标与起点相减 ⇒ 几十上百格的距离 ⇒ **遮挡被静默跳过**。这就是「物理体挡得住、
  静态方块挡不住」的根因；另外 `to` 只要跑出 plot 区块就不再被投出，整条射线都会变形。
  ⇒ 现在起点与方向都先 `projectToWorld`，命中点也统一换算到世界系量距离
  （刚体变换保长度，`travel` 参数不用改）。
- 命中点坐标系判别：`getContaining(level, hit.getLocation())` 查得到 ⇒ 是该物理体的局部系（要投出来）；
  查不到 ⇒ 主世界命中，本身就是世界系。万一判断失误导致距离 ≥ 射线长度，按「没挡住」处理（不会误挡）。
- 开关语义：`true` = 别的物理体和普通方块一样挡；`false` = 只有自身物理体/主世界的方块挡
  （**这才是原版 `encased_fan` 的真实行为**：`getFlowLimit` 只查自己那一层的 `getBlockState`，
  看不到别的物理体）。
- **`encased_fan` 自己的「静态方块挡不住」是同一个根因**：它的 `AirCurrent.getFlowLimit()` 在 Create 里、
  Sable 没给它打补丁 ⇒ 要修只能自己 mixin 进 Create（本项目至今零 mixin，暂不做）。

### 3.5 坑：粒子跟着喷嘴跑（像喷嘴的子物体）— 根因与修法

**现象**：车身/喷管一移动，已射出的粒子整条轨迹跟着平移旋转，像被挂在喷管上。

**根因（源码确认）**：

- 物理体内的方块实体 `getLevel()` 就是真正的 `ClientLevel`，`worldPosition` 是 **plot 区块坐标**
  （`EmbeddedPlotLevelAccessor` 只被 `SableSpawnCommands` 用来生成结构，方块实体不走它 ⇒
  `addParticle` 时坐标不会自动加 plot center 偏移）。
- `create:air` 的 `AirParticle` 被 Sable 写了
  `references/sable-main/.../compatibility/create/particles/AirParticleMixin.java:55-94`：
  `@Inject <init> TAIL` 把 `(x,y,z)` 与 `(x+dx, y+dy, z+dz)` 记成 origin/target；
  `@Overwrite tick()` 每 tick 用 `Mth.lerp(progress, origin, target)` 算出 desired 点，再
  `Sable.HELPER.projectOutOfSubLevel(level, desired)` **按当前姿态投一次**。
  这是给「物理体里的电扇」写的修正：电扇传的是 plot 坐标 ⇒ 投成世界坐标。
  **但如果传的就是 plot 坐标，等价于把整条轨迹定义在机体局部系** ⇒ 车身一动粒子就跟着动。
- `projectOutOfSubLevel`（`ActiveSableCompanion.java:171-205`）= `getContaining(level, pos)`
  查到所在 plot 才 `pose.transformPosition`，查不到就原样返回。

**修法**（`tickClient`）：生成轨迹后先把起点与「起点+位移」两点都用
`Sable.HELPER.projectOutOfSubLevel` 投到世界系，再 `addParticle`；位移 = 两个世界点的差。
这样粒子的 origin/target 都是世界坐标，绝大多数情况已经不在 plot 区块里 ⇒ mixin 里的
`projectOutOfSubLevel` 退化为恒等变换，粒子在世界里独立飞。
射线遮挡（`clearDistance`）仍在 plot 系里打，保持不变。

**残留边界**：物理体的世界位置恰好落在自己/别的 plot 区块内时（刚装配、姿态接近单位阵时），
mixin 仍会再投一次；此时姿态≈单位阵所以通常看不出问题。真要绝对干净就得自研粒子类型
（新 ParticleType + provider + 精灵图），当前不做。

### 3.6 v5：自研排气粒子 `ccpe:exhaust`（扫掠碰撞 + 撞墙向四周扩散）

> 现状：`USE_EXHAUST_PARTICLE = true`（**现行**）。
> 第一版曾因下方「致命 bug」导致撞墙毫无反应而被回退，修好后重新启用，待进游戏复核。

**为什么自研**：`create:air` 是「起点→终点插值」的纯视觉粒子（`hasPhysics = false`，永不碰撞），
而且 `AirParticleMixin` 每 tick 重投影，做不出「撞到方块停下并散开」的效果。
参考 `references/create-propulsion-simulated-main`（thruster 排气）：它的烟粒子就是自研粒子类型，
自带 `move()` 里的扫掠碰撞 + 撞墙散射。

**文件**

| 文件 | 内容 |
|---|---|
| `particle/ExhaustParticleData.java` | `record(scale, lifetime, r, g, b, ownerId)` + MapCodec/StreamCodec；`ccpe:exhaust` 的参数 |
| `particle/MyModParticles.java` | `DeferredRegister<ParticleType<?>>` 注册 `ccpe:exhaust`（NeoForge 原生 `ParticleType`，不用 Create 的 `ICustomParticleData`） |
| `client/particle/ExhaustParticle.java` | 客户端粒子：速度积分 + 淡入淡出 + 膨胀 + 阻尼/浮力 + 撞墙散射 |
| `client/particle/ExhaustCollision.java` | 球-箱**扫掠**碰撞（slab），主世界逐方块 + 其它物理体反投局部系两段式 |
| `assets/ccpe/particles/exhaust.json` | 精灵图列表 = `minecraft:generic_0..7`（与 `create:air` 同图） |
| `CCPeripheralExtender` / `CCPeripheralExtenderClient` | 各加一行注册（类型 / `registerSpriteSet` provider） |

**参数**（全部在 `ThrustNozzleBlockEntity` 顶部，可直接调）

| 常量 | 现值 | 说明 |
|---|---|---|
| `USE_EXHAUST_PARTICLE` | true | false = 回退旧的 `create:air` 路径（旧常量仍在） |
| `EXHAUST_SCALE` | **0.3** | 基准尺寸（块）；实际出生 = 本值 × 0.35、死亡 = 本值 × 1.50（见下方"随寿命演变"，2.0/0.6 都被嫌大，用户已调到 0.3） |
| `EXHAUST_LIFETIME` | **16** | 基准寿命 tick，实际 +0~9 随机 |
| `EXHAUST_SPEED` | **2.0** | 出生速度 块/tick，乘 (0.65 + 0.35×油门) |
| `EXHAUST_SPREAD` | 0.05 | 横向散开初速度上限 |
| `EXHAUST_RED/GREEN/BLUE` | 0.86/0.90/0.96 | 冷白偏蓝 |

粒子内部常量（`ExhaustParticle`）：`ALPHA_MAX 0.62`、`FADE_IN_END 0.08`、`ALPHA_FALLOFF 1.0`、
`SIZE_START 0.35` / `SIZE_GROWTH 1.15`、`SPEED_SLOW 0.28`、
水平阻尼 0.935 / 垂直 0.965、浮力 0.0008+0.0009×life、正弦摆动 0.0018、
碰撞半径 = `quadSize × 0.2`（夹到 0.08~0.35，视觉尺寸与碰撞半径分开）。

**随寿命演变（v7，用户要求"粒子随存在时间越来越大、越来越慢、越来越透明"）**

`life = age / lifetime`（0 出生 → 1 死亡），三条曲线全部单调：

| 观感 | 公式 | 旋钮 | 现值下的端点 |
|---|---|---|---|
| 越来越大 | `quadSize = baseScale × (SIZE_START + life × SIZE_GROWTH) × impactGrowth` | `SIZE_START` / `SIZE_GROWTH` | 0.35 → 1.50（约 4.3 倍） |
| 越来越慢 | 每 tick 阻尼 = 固定阻尼 × `(1 − SPEED_SLOW × life)` | `SPEED_SLOW` | 0.28；寿命末期水平每 tick 实际 ×0.67、末期几乎停住 |
| 越来越透明 | `alpha = ALPHA_MAX × smoothstep(0, FADE_IN_END, life) × (1 − life)^ALPHA_FALLOFF` | `ALPHA_MAX` / `ALPHA_FALLOFF` | 0 → 0.62 → 0，**全寿命单调变淡** |

- 旧版 alpha 是「10% 淡入 → 10%~75% 恒定 0.46 → 后 25% 淡出」（memo 早期写的 18%/62% 是再早的一版），
  中间那一大段完全不透明恒定 ⇒ 看不出"存在越久越淡"。现在改成出生后一路衰减到 0，
  所以 `ALPHA_MAX` 从 0.46 抬到 **0.62** 补偿平均值变淡（嫌整片排气太淡就继续加 `ALPHA_MAX`，
  嫌前段太浓就加 `ALPHA_FALLOFF`）。
- `SPEED_SLOW` 的副作用：总射程变短（同样寿命下轨迹短约一半），尾段会在原地"停住并散开"。
  嫌短就降到 0.15，嫌不够慢就加到 0.5。
- 撞墙的 `impactGrowth` / 散射仍叠加在这三条曲线之上（撞地变大和"随寿命变大"是两回事，互不冲突）。

**撞墙公式**（`ExhaustParticle#scatter`）：法向**反弹** + 切向滑移 + 随机溅射：

```
impact  = -v·n                       // 法向入射速度（正数）
rebound = n × impact × BOUNCE        // 0.45 ⇒ 明显弹开（不是贴墙滑）
tangent = (v - n(v·n)) × TANGENT_KEEP  // 0.90 ⇒ 沿表面继续滑
spread  = 垂面上随机方位 × impact × SPREAD_KEEP  // 0.80 ⇒ 四散
v'      = normalize(rebound+tangent+spread) × max(0.02, |v| × ENERGY_KEEP)  // 0.85
```

- 第一版照搬参考实现的「法向只留 10%」，实测表现为「扩散但继续下坠、没有反弹」——因为法向几乎被吃掉，
  粒子只能贴着表面滑。改成反弹式后正面砸地面/墙面会明显弹开。
- **「撞完像炸开、四面八方乱飞」的真正原因**：粒子贴面时几乎每 tick 都会再次命中，若每次都重新随机一次方向，
  就成了随机游走＝均匀四散。现在有 `COLLISION_COOLDOWN = 3` tick：一次有效碰撞后的冷却期内再贴面只调
  `slide()`（消掉朝表面里的分量、不再换方向）⇒ 稳定沿面滑行。`SPREAD_KEEP` 也从 0.8 降到 0.35。
- `SPREAD_ANGLE = π` = 不限制散射方向（用户明确不需要限制切向）；改成 0.96 可只往切向前方扇。
- 结果的法向分量恒正（只有 rebound 带法向分量），粒子撞完一定离开表面，不会陷进去。
- **无重力**：`gravity = 0`、tick 里没有任何向下加速度，只有微小向上浮力；撞后不下坠靠的就是上面的反弹。

**撞地变大 / 分裂**（`ExhaustParticle#onImpact`，两个独立开关；分裂已按用户要求关闭，只剩变大）

| 常量 | 现值 | 作用 |
|---|---|---|
| `GROW_ON_IMPACT` | true | 撞到东西就变大 |
| `IMPACT_GROWTH` | 2.0 | 每次有效碰撞尺寸 ×2.0 |
| `MAX_IMPACT_GROWTH` | 4.0 | 累计上限 4 倍 |
- 分裂代数走 `ExhaustParticleData.gen`（喷管直喷 = 0）。
- **子粒子只在切向偏转**：把父粒子方向投到命中面法线的垂面上得到基准切向，再绕法线随机角度
  旋转（`tangent`/`side` 正交基），**不加法向分量** ⇒ 子粒子全部贴着地面/墙面铺开，不会往表面里钻。
  出生点沿法线抬起 0.02 格（避免埋进方块里），横向抖动也只在切向。
- **子粒子不能当场 addParticle**：那是在 `ParticleEngine#tick` 遍历粒子列表时加元素（并发修改风险）。
  现在先入 `PENDING_SPLITS` 队列，由客户端 `ClientTickEvent.Post` 调 `ExhaustParticle#flushPendingSplits()`
  统一 flush（延迟 1 tick，肉眼无感）。队列上限 512。
- 代价：同屏粒子数最多 ×3（1 → +2 子粒子），按 §3.6 的扫掠成本估算仍是亚毫秒级，
  但会叠加在"寿命内粒子数"上；嫌重就把 `SPLIT_ON_IMPACT` 关掉只留变大。

**致命 bug（第一版回退的原因，已修）**

- `getCollisionShape(level, pos).toAabbs()` 返回的 AABB 是**方块局部坐标（0~1）**，必须加 `pos` 偏移才是
  世界/plot 坐标。第一版漏了这一步 ⇒ 所有候选碰撞盒都堆在原点附近 ⇒ `slab` 永远 `null` ⇒
  **碰撞代码每 tick 都在跑但永远撞不到东西**，表现就是「粒子直接穿过墙/地面」。
  现在 `slab(...)` 直接接收 `ox/oy/oz` 偏移（不新建 AABB，零额外分配）。
- 教训：这类「算得对但坐标系错了」的 bug 不会报错也不会卡顿，只能靠进游戏看表现；
  参考实现的 `ParticleCollision.java:80-84` 里那几个 `+ pos.getX()` 就是容易被漏掉的细节。

**关键坑**

- **出生点保护（现方案）**：只忽略离出生点 `SPAWN_GRACE_RADIUS = 0.75` 格以内的命中。
  早期版本是「整个喷管所在物理体都不参与碰撞」（`ownerId` 直接跳过），结果**车体/舱壁自己的方块也挡不住**，
  用户实测出现「大量粒子穿到墙后面（红线绿线都有）」。现在车身结构照常挡，只有喷口周围一小圈放行。
  `ownerId` 字段仍保留在粒子数据里，但碰撞不再用它。
- 粒子碰撞全用世界坐标（喷管侧已 `projectToWorld`），所以不会像 `create:air` 那样被车身拖着走。
- 成本：每颗粒子每 tick 一次扫掠（半径 0.08~0.35、位移约 0.5 格 ⇒ 约 27 个方块），
  同屏 ~60 颗时约 1.6k 次 `getBlockState`/tick，可接受。

**光照（"怎么出现黄黄的粒子"）**

- 粒子默认吃 lightmap：站在火把/岩浆/熔炉这些**暖色光源**旁边，白光烟团会被光图染成黄/橙色。
  这不是本想做的颜色，也不是贴图问题。
- 修法：`ExhaustParticle#getLightColor` 在 `IGNORE_LIGHTMAP = true`（默认）时直接返回 `0xF000F0`
  （天空 15 + 方块 15）全亮渲染，颜色只由 `EXHAUST_RED/GREEN/BLUE` 决定；想恢复受光照影响就置 false。

**残留边界**：纯客户端计算，各客户端看到的撞墙点/散射方向各自独立（与原版粒子一样不同步）；
要让所有人完全一致就得改成服务端 `sendParticles` 广播（参考实现是这么做的），当前不做。

### 3.7 远距离看不见粒子（根因：原版 32 格硬限制，`addParticle` vs `addAlwaysVisibleParticle`）

**现象**：离喷管约 1 区块就"完全不发射粒子"，出力完全正常；**`create:encased_fan` 有同样现象**；一直存在。

**排查中已排除的（均有实测数据）**

| 假设 | 结论 / 证据 |
|---|---|
| 客户端 BE ticker 停摆 | 否。36 格外的日志仍每秒打印 |
| 客户端转速没同步 | 否。`speed=256` |
| 遮挡射线把粒子截断 | 否。`clear=10.1`（打空） |
| 投影退化到 plot 坐标 | 否。`mouthWorld=(-126.07, 93.89, 0.37)` 始终是世界坐标 |
| 粒子根本没生成 | 否。自研粒子存活计数在 20~33 格处仍有 285~311 颗 |
| 图形 mod 作怪 | 否。Sodium 0.8.12 不碰粒子；Iris 着色器关闭（`enableShaders=false`）；Sodium Extra 只有按类型开关 |

**根因**（MC 源码，项目内就有：`build/moddev/artifacts/neoforge-21.1.235-sources.jar`）：

```java
// LevelRenderer.java:2714-2717
if (force) { return particleEngine.createParticle(...); }
else if (camera.getPosition().distanceToSqr(x, y, z) > 1024.0) { return null; }   // ← 32 格硬墙
```

`level.addParticle(...)` 传 `force = false`，正中这条判定。

**⚠️ 关键坑：两个 `addAlwaysVisibleParticle` 重载里只有一个能绕过**

```java
// ClientLevel.java:733-737   7 参数 —— 不绕过！
addAlwaysVisibleParticle(data, x,y,z, vx,vy,vz)
    -> levelRenderer.addParticle(data, false, true, ...)      // 第二个布尔是 decreased，不是 force
// ClientLevel.java:739-754   9 参数 —— 能绕过
addAlwaysVisibleParticle(data, ignoreRange, x,y,z, vx,vy,vz)
    -> levelRenderer.addParticle(data, data.getType().getOverrideLimiter() || ignoreRange, true, ...)
```

第一版就是错用了 7 参数重载，现象毫无变化（和 `encased_fan` 一模一样），白测一轮。

**证据链**

1. Sable `mixin/particle/LevelRendererMixin` 专门 `@Redirect` 了 `addParticleInternal` 里的
   `Vec3.distanceToSqr`，换成 `distanceSquaredWithSubLevels` —— 反证这个距离判定真实存在且会丢粒子。
2. Create 电扇同理：`AirCurrent.java:69`（`world.addParticle(new AirFlowParticleData(...))`）、
   `AirFlowParticle.java:149` 都是裸 `addParticle` ⇒ **`encased_fan` 一模一样**。
3. Create 里"远距离也要可见"的粒子全用 `addAlwaysVisibleParticle`：
   `BasinBlockEntity.java:684,710,730`、`ChuteBlockEntity.java:334`、`FluidFX.java:73,92`、
   `SpoutBlockEntity.java:243,257`；`LitBlazeBurnerBlock.java:94` 甚至用 9 参数重载传 `ignoreRange = true`。

**修法（现行）**

- `spawnExhaust` 与旧 `create:air` 回退路径都改成
  `level.addAlwaysVisibleParticle(data, true /* ignoreRange */, x, y, z, vx, vy, vz)`（**9 参数**）。
- 再加 `MAX_EXHAUST_DISTANCE = 64.0` 自己限一个可见上限（0 = 不限）：绕开原版限制后不能无脑全图喷，
  否则看不见的远处每颗粒子每 tick 还在做扫掠碰撞。
- 等价路线（备选）：把 `ParticleType` 注册成 `overrideLimiter = true`
  （`new ParticleType<>(true, ...)`，见 `MyModParticles`），那样连裸 `addParticle` 都不会被丢。
  现在选在调用点显式传 `ignoreRange`，语义更局部。
- 副作用：`force = true` 同时也跳过 `ParticleStatus.MINIMAL` 判断 ⇒ 玩家把粒子设置调到最低时
  排气仍会显示（对载具尾焰来说通常是想要的）。

**遗留**：`create:encased_fan` 的远处粒子仍然没有（那是 Create 代码，本项目不改）；
需要远处可见的排气只能看喷管这条链路。

**✅ 用户实测确认解决**：改用 9 参数重载后，32 格外尾焰正常可见，64 格处按 `MAX_EXHAUST_DISTANCE` 淡出。

## 4. 已知边界 / 未做

- **推力不做遮挡判定**：喷口被方块堵死时物理推力照样输出（要加的话应在每个游戏 tick 算一次遮挡并缓存给 substep 复用）。
- **气压只影响推力，不影响粒子**：高空排气粒子看起来和地面一样浓（想同步就在 `spawnExhaust` 里把
  `MAX_PARTICLES_PER_TICK` 乘气压，未做）。
- 气压每 substep 求一次（`HashMap` 查维度数据 + 一次贝塞尔求值 + 一次 `Vector3d` 分配，常数级），
  多管时按管数线性增长，实测量级可忽略。
- **粒子可见距离 = `MAX_EXHAUST_DISTANCE = 64 格**（§3.7）。原版对 `addParticle` 的硬限制是 32 格，
  已用 9 参数 `addAlwaysVisibleParticle(..., ignoreRange = true)` 绕过；但超过 64 格我们主动不生成（省扫掠碰撞）。
  远处的可见度还受"粒子本身很小很淡"影响（`EXHAUST_SCALE=0.3` ⇒ 出生仅 0.1 格），
  真嫌远处看不见就得放大 `EXHAUST_SCALE`/`ALPHA_MAX`，而不是再改生成逻辑。
- 排气粒子是纯客户端计算：各客户端看到的撞墙点/散射方向各自独立（原版粒子行为，未做服务端广播）。
- 粒子碰撞是**每颗粒子每 tick 一次扫掠**（半径 0.08~0.35、位移约 0.5~2 格 ⇒ 约 8~27 个方块）：
  单管 10 颗/tick × 寿命 ~16 tick ⇒ 同屏约 160 颗，实测未造成明显掉帧；多管并联时按管数线性增长。
  嫌重就先关撞地变大/缩短 `EXHAUST_LIFETIME`，或把扫掠改成「先长射线 clip，命中附近才逐方块扫」。
- **撞地分裂已关闭**（`SPLIT_ON_IMPACT=false`）：代码、`gen` 字段、`PENDING_SPLITS` 队列与客户端 flush 全部保留，
  改回 `true` 即恢复；届时同屏粒子数最多 ×(1+SPLIT_COUNT)。
- 没有合成配方（`data/ccpe/recipe/thrust_nozzle.json`），材料待用户定。
- 没有原版实体推挤（旧 `create:nozzle` 会推物品/生物）。
- 蓝图/装配兼容未专门验证：`canSurvive` 会在部署瞬间复核风扇，异常时照 `MyBearingPlateBlockEntity` 做条件豁免。
- 若模型的未旋转变体是"口朝下"，需把 `BASE_SHAPE` 上下翻一次（当前假设 up 帧口朝上）。
- **不新增 CC 写入**：喷管不是外设，出力由风扇转速决定 ⇒ `flip-autopilot.md` §3.2 的"6 写/20 Hz"预算不变。

## 5. 下一步

1. 定合成配方材料（并补 `recipe/`）。
2. 排气粒子已换成自研 `ccpe:exhaust`（§3.6）：待用户在游戏里确认撞墙滑行/撞地变大的最终手感，再微调
   `EXHAUST_SCALE / EXHAUST_SPEED / BOUNCE / SPREAD_KEEP / ENERGY_KEEP / IMPACT_GROWTH`。
   v7 新增的"随寿命越大/越慢/越透明"三条曲线的旋钮是 `ALPHA_MAX / ALPHA_FALLOFF / SIZE_START /
   SIZE_GROWTH / SPEED_SLOW`（都在 `ExhaustParticle` 顶部，改完热重载即可看）。
   `PARTICLES_BLOCKED_BY_OTHER_BODIES` 仍决定其它物理体是否挡粒子（现 true）。
3. 回到 `flip-autopilot.md`：用新喷管重做落地/倒挂调参（悬停油门 **43%**（气压 1.0），
   原来是按 14% 算的；控制律的增益/死区要重标定，且滚转单边差动只剩 6.4 rad/s² 的零余量，
   建议用双边反向差动）。
   **气压项（§2.1.1）让 14% 只在海平面成立**，高空油门要更大；嫌麻烦就让脚本用 `sensor` 的气压做前馈，
   或先把 `THRUST_SCALES_WITH_AIR_PRESSURE` 关掉再标控制律。
4. ~~高空尾迹（contrail）~~：**已实现，待进游戏验证**（§9，方案见 §8）。测试清单见 §9.4。

## 6. 参考来源（均只读）

| 参考 | 借鉴点 |
|---|---|
| `api/create-1.21.1-6.0.10-280-sources/.../kinetics/fan/{NozzleBlock,NozzleBlockEntity,EncasedFanBlockEntity,IAirCurrentSource,AirCurrent,AirFlowParticle}.java` | 喷嘴/电扇语义、`getFlowLimit` 遮挡、两种气流粒子机制 |
| `.../foundation/particle/{AirParticle,AirParticleData}.java` | `create:air` 的参数与寿命公式 |
| `references/sable-main/neoforge/.../compatibility/create/nozzle/{NozzleHoveringMixin,NozzleHoveringHelper,NozzleBEFixesMixin}` | 原版喷嘴的力公式（magnitude=5、软缩放、气压、13 射线） |
| `.../compatibility/create/fans_provide_force/EncasedFanBlockEntityMixin.java` | `isActive()` 被喷管挡死 ⇒ 风扇推力归零 |
| `.../compatibility/create/particles/{AirFlowParticleMixin,AirParticleMixin}.java` | 子次元粒子处理（bounds 放行、姿态重投影）；`AirParticleMixin#tick` 是「粒子跟着机体跑」的根因（见 §3.5） |
| `api/sable-common-1.21.1-2.0.3-sources/.../api/{block/BlockEntitySubLevelActor,physics/force/{ForceTotal,QueuedForceGroup,ForceGroups}}.java`、`sublevel/ServerSubLevel.java` | `sable$physicsTick` / 力组 / `applyQueuedForces` |
| `src/main/java/.../block/{MyBearingPlateBlockEntity,TrailingWheelMountBlockEntity}.java`、`compat/cc/FlightDataRecorder.java` | 项目内已验证的"施力 + 力组遥测 + 子次元射线"写法 |
| **`build/moddev/artifacts/neoforge-21.1.235-sources.jar`** | ⭐ **项目内就有完整 Minecraft 源码**（`ClientLevel.java`、`LevelRenderer.java` 等）。查原版行为优先用它，别靠记忆 —— §3.7 的 32 格限制与两个重载的差异都是从这里查实的 |
| `wiki/docs/engine/temperature.zh.md`、`EngineCoreBlockEntity` 的 `T_AMB_*` 常量与 `ambientTemp()` | **高度→环境温度**模型（尾迹复用，见 §8） |

## 7. 变更时间线（2026-08 排气重做，含踩过的坑）

| # | 需求 / 现象 | 处理 | 结果 |
|---|---|---|---|
| 1 | 粒子跟着喷嘴跑，「像喷管的子物体」 | 起点/位移两端 `projectOutOfSubLevel` 投世界系（§3.5） | ✅ 已解决 |
| 2 | 粒子被物理体挡住但挡不住世界方块 | `clearDistance` 射线改世界系（§3.4） | ✅ 已解决 |
| 3 | 想复刻参考 mod 的「撞墙停下并向四周扩散」 | 自研粒子 `ccpe:exhaust` + `ExhaustCollision` 扫掠（§3.6） | ✅ 实现 |
| 4 | 撞墙毫无反应 | `getCollisionShape` 返回方块局部坐标，漏加 `pos` 偏移 | ✅ 已修 |
| 5 | 粒子太大 | `EXHAUST_SCALE` 2.0 → 0.3（用户调） | ✅ |
| 6 | 撞墙后继续下坠、无反弹 | 法向改真反弹（`BOUNCE`），确认无重力 | ✅ |
| 7 | 撞地后想变大 / 分裂 | `GROW_ON_IMPACT` / `SPLIT_ON_IMPACT`（§3.6） | 变大保留，**分裂已关闭** |
| 8 | 出现「黄黄的粒子」 | 暖色光图染色 ⇒ `IGNORE_LIGHTMAP` 全亮 | ✅ |
| 9 | 撞墙后仍「四面八方乱飞」 | 每 tick 重新随机 ⇒ 随机游走；加 `COLLISION_COOLDOWN` + `SPREAD_KEEP` 降到 0.35 | ✅ |
| 10 | 大量粒子穿到墙后面 | 「忽略整个物理体」把车体结构也放行了；改出生点保护 0.75 格 | ✅ |
| 11 | 想让粒子随存在时间越大 / 越慢 / 越透明 | alpha 去掉恒定平台期改全寿命单调衰减；尺寸区间 0.42+0.95 → 0.35+1.15；阻尼追乘 `(1−SPEED_SLOW×life)`（§3.6） | 已改，待进游戏确认手感 |
| 12 | 需要出力随所处气压变化（乘气压） | `airPressureAt()`：喷管中心投世界系 → `DimensionPhysicsData.getAirPressure`，乘进冲量（§2.1.1） | ✅ 用户实测有效果 |
| 13 | 出力过大（气压 1.0、256 RPM 实测 750 多 pN） | `THRUST_PER_RPM` 800/256 → 200/256 → 再一次改成 **1.0（1 RPM = 1 pN，256 RPM = 256 pN）**，§2.2 每次全部重算 | 现行 256 pN/管 |
| 14 | 想要"1 RPM 对应 1 pN"的简单公式 | `THRUST_PER_RPM = 1.0`；连带：悬停 43%、4 管升限 y≈273、落地 25.4 m/s² | 已改，待进游戏确认 |
| 15 | 离喷管约 1 区块就不出粒子（出力正常，Create 电扇同样） | 原版 `addParticleInternal` 离相机 >32 格（1024）直接丢粒子；**第一次错用 7 参数 `addAlwaysVisibleParticle`（force=false）无效**，改 9 参数 `ignoreRange=true` + 自设 64 格上限（§3.7） | ✅ 用户实测解决 |
| 16 | 想要"真实高空尾迹"预研 | 科普 + 需求定稿 + 实现计划（§8），未动代码 | 计划就绪 |
| 17 | 开始实现尾迹 | 静态化 `ambientTempAt`、新粒子类型 `ccpe:contrail` + `ContrailParticle`、`tickContrail()` 按位移发射（§9） | 编译通过，待进游戏验证 |
| 18 | 尾迹看不清 / 太短 / 太小 | 染色 冰晶白 → 冷青 `0.45/0.80/1.00`；`ALPHA0` 0.14 → 0.28；寿命 60/300 → **180/600**；`WIDTH0` 0.6 → **1.0**；`WIDTH_TAU` 100 → 200（§9.5） | 编译通过，待进游戏确认 |
| 19 | 用户把寿命调到 12000~18000 后：**分布很散** + **要等一段才显现** | 淡入改固定 6 tick（原为寿命的 5% ⇒ 45 s）；`TRAIL_MAX_DRIFT_RADIUS=2.0` 给随机游走加边界、`TRAIL_DRIFT` 0.2→0.05；`TRAIL_SPACING_S_FLOOR=0.35` 防止低 s 时间距拉到 12 格；新增 `MAX_LIVE_CONTRAIL=6000` 存活安全阀（`ContrailBudget`，§9.7） | 编译通过，待进游戏确认 |
| 20 | 云层在 Y200、要求峰值曲线 + Y160 就要看清 | s 从单调爬升改为峰值曲线（上升段 × 高空变稀段）+ `TRAIL_Y_MIN=160` 阶跃硬门；节点与实测表见 §9.8 | 编译通过，待进游戏确认 |
| 21 | 要粒子**随时间越来越散** | 扩散半径从常数改 `R0 + RATE·√age`（软边界 + 6 格封顶）；边界内朝外速度按 `(dist/半径)²` 衰减（§9.9） | 编译通过，待进游戏确认 |
| 22 | 改成「初速 = 飘荡距离 ÷ 寿命」，且飘荡距离随高度变化（云层×1、越低越散） | 删除随机游走/抖动/浮点边界；`spawnContrail` 反算恒定速度，新增 `trailTurbulence()`（200→1.0，160→2.5）与 `TRAIL_DRIFT_DISTANCE=2.0`（§9.10） | 编译通过，待进游戏确认 |
| 23 | 想手感染料给**尾迹**染色 + 护目镜显示 | `useItemOn`/`useWithoutItem` 染料交互、BE 存 `trailDye`（NBT + `getUpdateTag/handleUpdateTag/getUpdatePacket` 同步）、`IHaveGoggleInformation` tooltip，颜色走进 `ContrailParticleData`（§10） | 编译通过，待进游戏确认 |
| 25 | 去掉「潜行空手右键清除染色」 | 删 `ThrustNozzleBlock#useWithoutItem`、goggle 的清除提示行、lang 的 `trail_dye_hint` / `trail_dye_cleared`（§10） | ✅ 编译通过 |
| 24 | 性能参数要能配置调 | 16 个性能参数搬进 `ccpe-client.toml`（`Config` CLIENT 段）；BE 的常量换成 `Config.XXX.get()` 访问器（现查现用，改完即时生效），粒子里每 tick 用到的 alpha/tau 在构造时缓存（§11） | 编译通过，待进游戏确认 |

## 8. 下一步：高空尾迹（contrail）实现计划

> 状态：**已实现**（2026-08，代码见 §9；§8.7 的清单已全部落地，两处偏离见 §9.0）。
> 前置条件已满足：粒子可见距离问题已修（§3.7，必须用 9 参数 `addAlwaysVisibleParticle`）。

### 8.1 需求定稿（用户拍板）

| 议题 | 结论 |
|---|---|
| 温度模型 | **复用项目已有的"高度→温度"**（`wiki/docs/engine/temperature.zh.md`、`EngineCoreBlockEntity.ambientTemp()`），不新写 |
| 湿度 | **不计算**。所有与湿度相关的量都用**纯高度函数**代替 |
| 尾迹高度窗口 | **Y = 160 ~ 320**；低空（Y < 160）**完全没有尾迹** |
| 风的处理 | **不随风飘**。粒子出生后**原地随机飘逸**，不做风场平流 |
| 性能 | 沿用本 memo 的粒子预算思路（§3.6），必要时按 §8.6 降级 |

### 8.2 物理依据速查（科普版）

真实尾迹 = **排气水汽在高空冷空气里结成的冰晶云**，不是烟。三条独立的物理量各自控制三件事：

| 观感 | 由什么决定 | 现实表现 |
|---|---|---|
| **有没有** | 温度阈值（Schmidt-Appleman）：干燥空气需 T ≲ −40 °C，越湿阈值越高（最湿可到 ≈ −9 °C） | **阈值型**，不是"越高越多" |
| **多长命** | 环境对冰的过饱和度：不饱和 ⇒ 几秒~2 分钟；过饱和 ⇒ 20 分钟~数小时 | **带状**（对流层顶最强），不是单调 |
| **多浓多大** | 光学厚度：亮度 ∝ 冰晶浓度 × 截面积 / 尾迹宽度 | **前段细亮、后段宽淡**；这是质量守恒的必然结果，不是两个独立效果 |

关键推论（本项目要遵守的三条设计规则）：

1. **气压本身不参与判定**（只是高度的代理变量）。现有 `getAirPressure` 是 1.5 → 0 的单调曲线，**不能**直接拿来当"尾迹强度"。
2. **"越高越好"是错的**：真实世界里高到平流层反而太干、没有尾迹。本项目按用户定稿只做 160 → 320 窗口，**不**额外加上边界。
3. **尺寸与透明度必须成对算**：`alpha ∝ 1/宽度`，否则"散开"和"变淡"会互相打架。

### 8.3 高度 → 环境温度（复用现有模型，不重写）

`EngineCoreBlockEntity.T_AMB_*` 定义的分段线性曲线（主世界）：

```
Y <= 63        -> 20 C
63 -> 200      -> 20 -> 0 C      (云层 / 结冰层)
200 -> 320     -> 0 -> -40 C     (尾迹带在这里)
Y >= 320       -> -40 C
地狱 = 155 C 恒温；末地 = 0 C 恒温
```

落到尾迹窗口上：

| Y | 160 | 200 | 240 | 260 | 280 | 300 | 320 |
|---|---|---|---|---|---|---|---|
| T (°C) | **+5.8** | 0 | −13.3 | −20.0 | −26.7 | −33.3 | **−40** |
| 尾迹适宜度 s（8.4 曲线） | ≈0 | 0.10 | 0.70 | 0.96 | 1.00 | 1.00 | 1.00 |

**改动**：把 `ambientTemp()` 的曲线抽成 `public static float ambientTempAt(Level level, double y)`，
实例方法 `ambientTemp()` 改为委托（行为零变化），尾迹直接调静态版。
（`SensorSystemAPI.getPressureForEngine(double y)` 已是现成的静态气压入口，需要气压时用它。）

### 8.4 三个关系 → 三个（而不是一个）函数

用户已定：不用湿度、纯高度函数。于是用**一个驱动量** s（尾迹适宜度，0~1）加三条不同形状的映射：

```
T(y)  = ambientTempAt(level, y)                 // 复用 8.3
s     = smoothstep01((T_START - T) / (T_START - T_FULL))
        T_START = +6 C   (~Y 160，低于此完全无尾迹)
        T_FULL  = -24 C  (~Y 275，到这里拉满)

(1) 生成：按"移动距离"发射，而不是按 tick
    spacing = TRAIL_SPACING / max(s, 1e-4)      // s 越小尾迹越稀
    累计位移 >= spacing 就吐一颗；悬停不动 => 不发射（不会在原地堆一坨）

(2) 寿命：与 s 同向
    life = TRAIL_LIFE_MIN + (TRAIL_LIFE_MAX - TRAIL_LIFE_MIN) * s

(3) 尺寸 / 透明度：互相绑定（质量守恒）
    width(t) = TRAIL_WIDTH0 * (1 + t/tau)^0.5   // 湍流扩散 sqrt(t) 律
    alpha(t) = TRAIL_ALPHA0 * (width0 / width(t))  // 摊开 => 变淡，成对出现
```

**为什么"生成"按移动距离而不是 tick**：现实里尾迹浓度由"单位航程排了多少水汽"决定，悬停不动的飞机不会
原地堆出云团。按距离发射还顺带解决性能问题：静止时零发射。

### 8.5 "原地随机飘逸"（用户明确不要风）

- 出生时给一个**很小的随机初速**（0.01~0.03 格/tick，方向随机），之后几乎不再加速；
- 每 tick 叠加缓慢变化的抖动（可借用 `ExhaustParticle` 里 SWIRL 正弦摆动的思路，但更慢更弱）；
- 极小的向上浮力，让尾迹整体略微升高、变松；
- **不做风场平流**（虽然 `SableCompat.getWorldAirVelocity` 现成可用，但只有装了天气/风模组才有值；这里明确不用）。

> 注意：不随风 ≠ 静止。完全静止会像"贴纸"，所以必须有随机游走；只是**整体平流 = 0**。

### 8.6 必须遵守的工程约束（都是本 memo 踩过的坑）

1. **必须用 9 参数 `addAlwaysVisibleParticle(data, true, x, y, z, vx, vy, vz)`** —— 见 §3.7；
   7 参数那个 `force = false`，32 格就被原版丢掉。尾迹比尾焰更需要远处可见。
2. **不能碰撞、不能受重力、不能撞地反弹**：尾迹是空气里的冰晶，`ExhaustCollision` 那套扫掠**完全不要复用**
   ⇒ 新建粒子类型 `ccpe:contrail`，不要改造 `ExhaustParticle`。
3. **粒子数 = 发射率 × 寿命**：按 8.8 默认值估算（`TRAIL_SPACING ≈ 1.2 格`、`LIFE_MAX ≈ 300 tick`）
   ⇒ 20 m/s 时约 **250 颗/管**存活，4 管约 1000 颗。无碰撞扫掠，成本主要是半透明 quad 绘制，可接受；嫌重先降 `LIFE_MAX`。
4. **距离上限单独一条**：`MAX_CONTAIL_DISTANCE`（建议 96，比尾焰的 64 大），超出就不发射。
5. **出生点滞后**：真实尾迹不在喷口出现，要先飞过 50~100 m 的喷流混合段才开始结冰
   ⇒ 本项目尺度上**沿排气反方向后退 `TRAIL_START_OFFSET ≈ 2~4 格`再出生**（观感差别很大）。
6. **世界坐标**：出生点必须走 §3.5 的 `projectToWorld`，否则粒子会钉在机体上跟着跑。

### 8.7 文件清单（待确认后实施）

| 文件 | 改动 |
|---|---|
| `block/EngineCoreBlockEntity.java` | 抽出 `public static float ambientTempAt(Level, double y)`；实例 `ambientTemp()` 委托。**行为零变化** |
| `particle/MyModParticles.java` | 注册 `ccpe:contrail`（可顺便设 `overrideLimiter = true`，见 §3.7 备选方案） |
| `particle/ExhaustParticleData.java` | **复用**（`scale/lifetime/r/g/b/ownerId/gen` 字段够用），不新建 record |
| `client/particle/ContrailParticle.java` | **新建**：无碰撞、sqrt(t) 膨胀、`alpha ∝ 1/宽度`、原地随机飘逸、缓慢消散 |
| `CCPeripheralExtenderClient.java` | `registerSpriteSet` + provider 注册（与 `ccpe:exhaust` 并列） |
| `resources/assets/ccpe/particles/contrail.json` | 精灵图列表（先用 `minecraft:generic_0..7`，之后可换专用白云贴图） |
| `block/ThrustNozzleBlockEntity.java` | 新增 `tickContrail()`（在 `tickClient()` 里调用）；常量见 8.8 |
| `memo/thrust-nozzle.md` | 实施后补 §9 实现细节与实测 |

### 8.8 常量草案（全部放在 `ThrustNozzleBlockEntity` 顶部，与 `EXHAUST_*` 并列）

| 常量 | 建议值 | 说明 |
|---|---|---|
| `USE_CONTRAIL` | true | 总开关 |
| `TRAIL_T_START` | +6 °C | 低于此温度才开始有尾迹（≈ Y 160，对上用户窗口） |
| `TRAIL_T_FULL` | −24 °C | 到这里拉满（≈ Y 275） |
| `TRAIL_SPACING` | 1.2 格 | 每飞这么远吐一颗（悬停不出） |
| `TRAIL_LIFE_MIN / MAX` | 60 / 300 tick | 3 s → 15 s |
| `TRAIL_WIDTH0` | 0.6 格 | 出生宽度 |
| `TRAIL_WIDTH_TAU` | 100 tick | sqrt(t) 扩散的时间常数（越大散得越慢） |
| `TRAIL_ALPHA0` | 0.14 | 出生不透明度上限（比尾焰的 0.62 淡很多） |
| `TRAIL_DRIFT` | 0.02 格/tick | 原地随机飘逸的速度上限 |
| `TRAIL_START_OFFSET` | 3 格 | 出生点沿排气反方向后退 |
| `MAX_CONTAIL_DISTANCE` | 96 格 | 超过就不发射 |
| `TRAIL_RED/GREEN/BLUE` | 0.92/0.94/0.97 | 冰晶白（比尾焰更白更冷） |

### 8.9 需要用户确认的 4 件事（开工前）

1. **寿命 15 s 够不够**？再长就进入"粒子数 = 发射率 × 寿命"的爆炸区（30 s ⇒ 500 颗/管）。
   真要几分钟的尾迹就得改成**带状 mesh** 方案（工作量 3~5 倍，本 memo 暂不选）。
2. **悬停不动时完全不出尾迹**（按移动距离发射）—— 接受吗？
3. **出生点滞后 3 格**（真实尾迹在喷流混合段之后才结冰）—— 要做，还是先做 0 偏移看效果？
4. **末地（恒 0 °C）会蹭到约 10% 强度的淡尾迹**（`T = 0 °C` ⇒ `s ≈ 0.10`）。
   要不要给末地/地狱加维度开关？（地狱 155 °C 天然为 0，不受影响）

### 8.10 实施顺序（建议）

1. 抽 `ambientTempAt` 静态方法 → 编译，确认引擎行为没变（这步是纯重构）。
2. 注册 `ccpe:contrail` + `ContrailParticle`（先把参数写死成"明显能看见"的夸张值，确认链路通）。
3. 接 `tickContrail()`：先做**按距离发射 + 适宜度曲线**，不做滞后偏移与随机飘逸。
4. 加随机飘逸与 `sqrt(t)` 扩散 + `alpha ∝ 1/宽度`，进游戏调手感。
5. 最后补 `TRAIL_START_OFFSET`、距离上限、以及 memo §9。
   —— **以上 5 步已一次性做完**，见 §9。

## 9. 高空尾迹（contrail）实现记录（v1，2026-08）

> 状态：**代码完成、`gradlew classes` 通过、未进游戏实测**。方案 = §8（用户 4 项定稿见 §9.1）。
> 与 §8.7 计划的两处**必要偏离**见 §9.0。

### 9.0 与 §8.7 计划的两处偏离

1. **不复用 `ExhaustParticleData`，新建 `ContrailParticleData`**。
   `ExhaustParticleData#getType()` 写死返回 `ccpe:exhaust`，而 `ParticleEngine`
   是**按 `options.getType()` 找 provider** 的 ⇒ 拿它去生成尾迹只会生成排气粒子。
   尾迹也不需要 `ownerId`/`gen`（不碰撞、不分裂），所以新 record 只有 `width/lifetime/rgb` 五个字段
   （手写 `StreamCodec`，和排气那个同写法）。
2. **温度曲线抽成静态方法**：`EngineCoreBlockEntity.ambientTempAt(Level level, float y)`（`public static`），
   原实例方法 `ambientTemp()` 现在只是「取维度 + `engineAltitude()`」再委托 ⇒ **引擎行为零变化**。

### 9.1 用户定稿（开工前问过，全部"推荐项"）

| 议题 | 结论 |
|---|---|
| 寿命 | `TRAIL_LIFE_MAX = 300`（15 s），MIN 60 |
| 悬停 | **完全不出尾迹**（按移动距离发射，悬停位移为 0 ⇒ 零发射） |
| 出生点滞后 | **要**，`TRAIL_START_OFFSET = 3` 格 |
| 维度 | `CONTRAIL_OVERWORLD_ONLY = true`：末地/地狱都不出尾迹 |

### 9.2 文件

| 文件 | 改动 |
|---|---|
| `block/EngineCoreBlockEntity.java` | 新增 `public static float ambientTempAt(Level, float y)`；`ambientTemp()` 委托 |
| `particle/ContrailParticleData.java` | **新建** record `(width, lifetime, r, g, b)` + CODEC/STREAM_CODEC |
| `particle/MyModParticles.java` | 注册 `ccpe:contrail`（同样 `overrideLimiter = false`，靠调用点传 `ignoreRange`） |
| `client/particle/ContrailParticle.java` | **新建**：无碰撞、`sqrt(t)` 扩散、`alpha ∝ 1/宽度`、原地随机飘逸 |
| `CCPeripheralExtenderClient.java` | `registerSpriteSet(MyModParticles.CONTRAIL, ContrailParticle.Provider::new)` |
| `assets/ccpe/particles/contrail.json` | 精灵图 `minecraft:generic_0..7`（与排气同图，之后可换专用白云贴图） |
| `block/ThrustNozzleBlockEntity.java` | `tickContrail()` / `spawnContrail()` / `contrailSuitability()` + 13 个 `TRAIL_*` 常量；`tickClient()` 重排（先把共用量算好，尾迹在排气门控**之前**调用） |

### 9.3 发射逻辑（`tickClient` → `tickContrail`）

```
moved    = |喷口世界坐标 − 上一 tick 锚点|              // 客户端状态：contrailAnchorWorld / contrailAccum
moved > TRAIL_MAX_TICK_MOVE(4)  ⇒ 判为瞬移，本 tick 不发射、累积清零
accum   += moved
s        = contrailSuitability(level, 喷口世界 Y)      // =0 ⇒ 直接返回（低空天然为 0）
spacing  = TRAIL_SPACING / max(s, 1e-4)               // 越不冷 ⇒ 越稀（s=0.1 时 12 格才一颗）
life     = LIFE_MIN + (LIFE_MAX−LIFE_MIN)·s  × 随机(0.85~1.15)
accum ≥ spacing 就吐一颗，单 tick 最多 MAX_CONTRAL_PER_TICK(3) 颗，剩余累积封顶 1 个 spacing
```

- 适宜度曲线：`s = smoothstep01((TRAIL_T_START − T)/(TRAIL_T_START − TRAIL_T_FULL))`，
  `+6°C ⇒ 0`、`−24°C ⇒ 1`。落点与 §8.3 的表格一致：Y160 ≈0 / Y200 0.10 / Y240 0.71 / Y260 0.96 / **Y275 起 1.0**。
- **出生点** = 喷口世界坐标 **+ 排气下游 `TRAIL_START_OFFSET = 3` 格** + 垂直截面内 ±`TRAIL_WIDTH0×0.35` 格随机。
  ⚠ §8.6.5 当时写的是「沿排气**反**方向后退」，与它自己那句「先飞过喷流混合段才开始结冰」是矛盾的：
  实现取**下游**（远离喷口、落在尾焰之后）。想要反过来把常量改成负值即可（已在该常量注释里写明）。
- **初速** = 球面随机方向 × `TRAIL_DRIFT × 0.3~1.0`（≤ 0.02 格/tick）⇒ **整体平流 = 0**，不随风（用户定稿）。
- **距离上限** `MAX_CONTRAL_DISTANCE = 96`（独立于排气的 64）；`withinParticleDistance()` 已改成
  「调用方传上限」的形式，两个链路各用各的常量。
- **出生与排气解耦**：尾迹不查遮挡射线、不看油门大小（水汽量与推力无关），只要风扇在转且机体在动。

### 9.4 粒子（`client/particle/ContrailParticle`）

- **无碰撞**：`hasPhysics = false`，不复用 `ExhaustCollision`，直接 `setPos` 推进（不走 `Particle#move`，
  那个会做 `collideBoundingBox` 与贴地判停）。
- **无重力**：`gravity = 0`，只有 `BUOYANCY = 0.0002` 的向上浮力。
- **两条成对曲线**（质量守恒，现实里"前段细亮、后段宽淡"的成因）：

  ```
  spread(t) = sqrt(1 + age / TRAIL_WIDTH_TAU(100))      // 寿命中点 1.58×、末尾 2.0×
  quadSize  = baseWidth × spread(t)
  alpha     = TRAIL_ALPHA0 / spread(t) × 淡入(0~5% 寿命) × 淡出(80%~100% 寿命)
  ```

  即出生 0.14 → 寿命末尾 ~0.07 → 淡出到 0（不硬切）。
- **原地随机飘逸**：每 tick 抖动 `JITTER=0.0016` + 缓慢正弦 `SWIRL=0.0006`（φ=0.07/tick）+ 浮力，
  再乘 `DRIFT_DAMP=0.985` 并**把速度钳到 `TRAIL_DRIFT`**⇒ 一生最多漂约 6 格。
- **光照/渲染**：继承 `SimpleAnimatedParticle` 直接拿到 `PARTICLE_SHEET_TRANSLUCENT` 与全亮
  `getLightColor = 15728880`（天空 15 + 方块 15）—— 冰晶不该被暖色光源染黄。
  精灵图**出生时随机定死一张**（不调 `setSpriteFromAge`），避免 1000 颗同时跳帧。
- **必须用 9 参数 `addAlwaysVisibleParticle(..., true, ...)`**（§3.7 的老坑），尾迹比尾焰更需要远处可见。

### 9.5 常量总表（全在 `ThrustNozzleBlockEntity` 顶部，改完热重载即看）

| 常量 | 现值 | 作用 / 怎么调 |
|---|---|---|
| `USE_CONTRAIL` | true | 总开关 |
| `CONTRAIL_OVERWORLD_ONLY` | true | 只在主世界出尾迹 |
| `TRAIL_T_START` / `TRAIL_T_FULL` | +6 / −24 °C | 温度窗口（≈ Y160 / Y275）；想更低空就有尾迹 ⇒ 把 START 调大 |
| `TRAIL_SPACING` | 1.2 格 | 发射密度（越小越密） |
| `TRAIL_LIFE_MIN` / `MAX` | **360 / 1200 tick（18~60 s，"1 分钟左右"）** | 越冷越耐久；用户反馈"太短"后从 60/300 翻倍。`MAX` 是**性能主旋钮**（粒子数 ≈ 密度 × 寿命） |
| `TRAIL_WIDTH0` | **1.0 格** | 出生宽度（quad 半边，可见约 2 格）；用户反馈"要放大"后从 0.6 提到 1.0 |
| `TRAIL_WIDTH_TAU` | **300 tick（经验：≈寿命/4）** | 扩散快慢（寿命翻倍后同步放大，末段仍是 2.0 倍宽） |
| `TRAIL_ALPHA0` | **0.28** | 出生不透明度（配合染色从 0.14 抬起）；嫌浓就降 |
| `TRAIL_DRIFT` | 0.02 格/tick | 原地飘逸上限（0 = 完全钉死） |
| `TRAIL_START_OFFSET` | 3.0 格 | 出生点沿排气下游偏移（负值 = 反向） |
| `MAX_CONTRAL_DISTANCE` | 96 格 | 超过就不发射 |
| `MAX_CONTRAL_PER_TICK` | 3 | 单 tick 补发上限 |
| `TRAIL_MAX_TICK_MOVE` | 4.0 格 | 超过当瞬移，丢弃 |
| `TRAIL_RED/GREEN/BLUE` | **0.45/0.80/1.00** | 冷青色（用户要求"染色看清"；改回 0.92/0.94/0.97 即冰晶白） |

### 9.6 进游戏验证清单（按顺序做，方便定位）

1. **低空对照**：地面（Y<160）开风扇 ⇒ 只有排气、**没有**尾迹 → 证明温度门控生效。
2. **高空出现**：飞到 Y≈200 起飞巡航 ⇒ 出现**淡**尾迹；Y≈260+ ⇒ 明显、更耐久。
3. **悬停不出**：高空把机体稳住不动（风扇仍转）⇒ 不产生新粒子，旧粒子自己淡掉。
4. **随高度变浓**：在 Y=200 / Y=240 / Y=280 各平飞一段，尾迹应依次变**密 + 变长命**。
5. **不跟随机体**：飞过去之后回头看，尾迹应**留在原地**形成一条云带，而不是跟着车身平移/旋转（§3.5 的老坑）。
6. **远处可见**：飞到 ~64~90 格（>32 格原版限制）回望 ⇒ 尾迹仍在（§3.7）。
7. **出生点滞后**：尾迹应与喷口之间有约 3 格"空白喷流段"。
8. **末地无尾迹**：去末地高空飞 ⇒ 无尾迹。
9. 性能：寿命拉到 30 s 后，4 管满载约 **2000 颗**同屏（每颗还是 ~2~4 格的半透明 quad）；
   若掉帧先降 `TRAIL_LIFE_MAX`（600 → 300 直接减半），其次降 `TRAIL_WIDTH0`。

**待用户回报**：①尾迹整体是不是"太白/太淡/太粗"（调 `TRAIL_ALPHA0` / `TRAIL_WIDTH0`）；
②出生点偏移方向是否符合预期（3 格在下游 vs 想要反向）；③高空尾迹的密度与长度手感（`TRAIL_SPACING` / `TRAIL_LIFE_MAX`）。

### 9.7 v2 修正：寿命调高后的「分布很散」+「要等很久才显现」（2026-08）

用户在游戏中把寿命调到 **12000~18000 tick（10~15 分钟）**、`TRAIL_DRIFT` 0.2、`WIDTH_TAU` 6000、
`ALPHA0` 0.6（颜色临时改成纯红 1/0/0 便于观察），随后反馈两个症状。两条都有**结构性根因**，不是参数没调好：

| 症状 | 根因 | 修法 |
|---|---|---|
| **要等一段才显现** | 淡入写成了「寿命的 5%」：寿命 300 时 15 tick（无感），寿命 18000 时 **900 tick = 45 秒** | 淡入改成**固定 tick**：`FADE_IN_TICKS = 6`（0.3 s），不再随寿命缩放 |
| **分布很散** | ① 随机游走**无界**：`TRAIL_DRIFT = 0.2` 格/tick × 18000 tick，单颗粒子能游走几十格；② 间距 `TRAIL_SPACING / s` 在 Y≈200（s≈0.1）时变成 **12 格才一颗**，天然断线 | ① 新增 `TRAIL_MAX_DRIFT_RADIUS = 2.0`：超半径只删掉「朝外」的速度分量；`TRAIL_DRIFT` 降回 0.05。<br>② 新增 `TRAIL_SPACING_S_FLOOR = 0.35`：间距最多稀到 2.9 倍（≤3.4 格） |

新增 **存活总数安全阀** `MAX_LIVE_CONTRAIL = 6000`：寿命每翻一倍，同屏粒子数就翻一倍
（18000 tick 理论上是几万颗半透明 quad）。计数住在 common 包的 `particle/ContrailBudget`
（只有 `int`，BE 可以安全读；不能直接读客户端粒子类），由 `ContrailParticle` 的构造 / `remove()` 维护，
换维度时在客户端 `ClientTickEvent.Post` 里 `reset()`（旧世界的粒子被直接丢弃、不走 `remove()`，
不重置的话计数会永久卡在上限）。

**教训（写进 memo 免得重踩）**：

- **寿命相关的曲线一律不要写成「寿命的比例」**，除非它本来就该随寿命缩放。淡入/淡出这类
  「出生那一小段」必须用**固定 tick** —— 否则用户把寿命从 300 调到 18000，淡入被同步放大 60 倍。
  （淡出保留比例是合理的：它是「末段」的语义。）
- **随机游走必须配边界**。只要粒子会自己加速（抖动/浮力），寿命一长就会无界扩散；
  「离出生点半径 + 只削法向分量」是最省事且观感稳定的写法（完全钳速度会像贴纸、完全不管会成云）。
- **密度和强度要分开表达**：不冷的时候该做的是「更淡、更短命」，而不是「更少粒子」——
  抽粒子只会让人看到断线，看不出「弱」。这也呼应 §8.2 那条「气压不参与判定」。

**⚠️ 性能提醒**：15 分钟寿命 + 1.2 格间距 ⇒ 尾迹会拖出上千格，但同屏粒子被 `MAX_LIVE_CONTRAIL`
压在 6000。真掉帧就按 6000 → 3000 → 1500 往下砍，或者把寿命降回 1200~2400（1~2 分钟）——
**「尾迹看起来长」主要来自速度 × 寿命，但肉眼可见的只有镜头附近那几百格**，
所以超过约 2400 tick 之后继续加寿命基本都是浪费。

### 9.8 v3：适宜度改为**峰值曲线**（2026-08，用户定稿）

原来的 s 是**单调爬升**（Y160 的 0 涨到 Y275 的 1 后封顶）。用户指出「我的世界云层在 200 左右」，
要求改成**峰值曲线**，同时「Y≈160 就要看见清晰尾迹」。现实依据：尾迹判据要**冷 + 湿**，
冷随高度单调、**湿是先增后减**（对流层顶最湿、平流层极干）⇒ 尾迹本来就有一个「最浓高度带」，越往上越稀。

```
s_rise = smoothstep01((T_START − T) / (T_START − T_FULL))               // 上升段
s_fade = smoothstep01((T_FADE_START − T) / (T_FADE_START − T_FADE_END)) // 高空变稀段
s      = s_rise × (1 − (1 − S_CEILING) · s_fade)                       // 峰值 1.0，世界顶 0.15
s      = 0  当 Y < TRAIL_Y_MIN = 160                                    // 阶跃硬门
```

温度仍走 `EngineCoreBlockEntity.ambientTempAt`（以后改 `T_AMB_*` 时整条尾迹带自动平移，不用改两处）。
`TRAIL_Y_MIN` 用**阶跃**而不是平滑过渡：因为「160 以下完全没有」和「160 就已经明显」
在同一高度没法用一条平滑曲线同时满足 —— 这是刻意的取舍，不是漏改。

| 节点 | 温度 | Y | 作用 |
|---|---|---|---|
| `TRAIL_T_START` | +10 °C | ≈132 | 上升段起点（被硬门挡在 160） |
| `TRAIL_Y_MIN` | +5.8 °C | **160** | **硬门**：以下为 0；一过 160 直接 s≈0.58 |
| `TRAIL_T_FULL` | +2.5 °C | ≈183 | 上升段拉满（云层下方就已经最浓） |
| `TRAIL_T_FADE_START` | −8 °C | ≈224 | 峰带结束（云层 ~200 在峰内），开始变稀 |
| `TRAIL_T_FADE_END` | −40 °C | 320 | 世界顶，只剩 `TRAIL_S_CEILING = 0.15` |

实测口径（寿命 360/1200、间距 1.2、s floor 0.35）：
159 → **0**；160 → s0.58 / 间距 2.1 / 42 s；175 → 0.94 / 1.3 / 57 s；
**183~224 → 满（1.2 / 60 s）**；240 → 0.94；260 → 0.73；280 → 0.47；300 → 0.25；320 → 0.15。
**峰值带 = Y 183~224**，云层 Y≈200 正落在峰内。
### 9.9 v4：粒子随时间**越来越散**（2026-08，用户定稿）

紧接 §9.7（当时为了修「分布很散」给随机游走加了一个**固定** 2 格半径）。现在用户要的正好是反方向：
「粒子随着时间越来越散」。两者不冲突 —— **常数边界**只能做到「要么整体散、要么整体不散」，
而**随时间增长的边界**才是「刚出膛时实、越老越蓬松」。

```
半径(age) = min(TRAIL_MAX_DRIFT_RADIUS, TRAIL_SPREAD_R0 + TRAIL_SPREAD_RATE · √age)
            0.4 + 0.16·√age，封顶 6 格

边界以内：朝外速度按 (dist/半径)² 衰减    // 软边界，粒子铺满球体而不是挤在壳上
边界以外：朝外分量直接剔除              // 保住切向蠕动
```

| 年龄 | 扩散半径 | 单颗可见宽 | 透明度 |
|---|---|---|---|
| 0.3 s | 0.79 格 | 2.02 格 | 0.59 |
| 3 s | 1.64 | 2.19 | 0.55 |
| 15 s | 3.17 | 2.83 | 0.42 |
| 30 s | 4.32 | 3.46 | 0.35 |
| 60 s（寿命尽头） | 5.94 → 封顶 6 | 4.47 | 0.27 |

- **年龄 ≈ 离车尾的远近**（机体在往前飞）⇒ 视觉结果：喷口附近一条 1~2 格的细实带子，
  往车尾方向逐渐摊到 12 格宽的松散云带。这就是「越来越散」。
- 用**软边界**（`1 − (dist/半径)²` 衰减朝外速度）而不是「超出就削平」：硬削会让同龄粒子全挤在
  同一个球面，边缘像刀切；软边界下粒子自然铺满球体。
- 三道约束的分工：速度上限 `TRAIL_DRIFT`（防抖）→ 软边界（防炸开）→ 边界随年龄增长（提供扩散）。
- 旋钮：`TRAIL_SPREAD_RATE`（散得快慢，0 = 退回固定半径）、`TRAIL_MAX_DRIFT_RADIUS`（最终封顶 6 格）、
  `TRAIL_SPREAD_R0`（喷口处的实心半径）。粒子**自身尺寸**的增长（`TRAIL_WIDTH_TAU`）是另一条腿：
  尺寸 ×2.2、位置扩散 ×7（0.79 → 6 格），两者叠加才像「云摊开」。

### 9.10 v5：飘荡改为「总距离 ÷ 寿命」的匀速直线模型（2026-08，用户定稿）

用户提出更直接的模型并取代 v4 的随机游走 + 软边界：

```
出生时随机决定：寿命 lifetime（由适宜度 s 决定）、总飘荡距离 distance（随机 × 高度湍流系数）
速度 speed = distance / lifetime         // 块/tick，恒定不变，方向随机（球面均匀）
位移(age) = speed × age                  // 线性 ⇒ 死亡那一刻恰好离出生点 distance 格

湍流系数(Y) = 1 + (GAIN−1)·(200−Y)/(200−160)   // Y≥200 → 1.0；Y=160 → GAIN=2.5
```

**为什么这个模型更好**：位移量是一个**直接可读**的参数（"这颗粒子一生飘 2 格"），
而老模型（随机游走 + 抖动 + 边界钳位）只能靠调参间接控制，寿命一长就失控（§9.7 的教训）。

删除的东西：`JITTER` / `SWIRL` / `BUOYANCY` / `DRIFT_DAMP` / `clampDrift()` / `spreadRadius()` / `spawnCenter`、
常量 `TRAIL_DRIFT` / `TRAIL_MAX_DRIFT_RADIUS` / `TRAIL_SPREAD_R0` / `TRAIL_SPREAD_RATE`。
新增：`TRAIL_DRIFT_DISTANCE=2.0`、`TRAIL_DRIFT_DISTANCE_MIN/MAX=0.55/1.60`、
`TRAIL_TURB_CLOUD_Y=200`、`TRAIL_TURB_LOW_GAIN=2.5`、`trailTurbulence(y)`。

**实测口径**（`TRAIL_DRIFT_DISTANCE = 2.0`）：

| Y | 湍流系数 | 一生飘荡距离 | 60 s 粒子速度 | 18 s 粒子速度 |
|---|---|---|---|---|
| 160 | ×2.50 | 2.75 ~ 8.00 格 | 0.046 ~ 0.133 格/秒 | 最远 0.444 格/秒 |
| 180 | ×1.75 | 1.93 ~ 5.60 | 0.032 ~ 0.093 | 0.311 |
| 200（云层） | ×1.00 | 1.10 ~ 3.20 | 0.018 ~ 0.053 | 0.178 |
| ≥200 | ×1.00 | 1.10 ~ 3.20 | 0.018 ~ 0.053 | 0.178 |

- 尾带宽度 ≈ 2 × 最大飘荡距离 ⇒ 云层以上约 **6~8 格**、Y160 附近约 **16 格**。
  比 v4（软边界半径 6 格 ≡ 直径 12 格）**更紧**，想让"越来越散"更夸张就抬 `TRAIL_DRIFT_DISTANCE`（4.0 直接翻倍）。
- 短寿命粒子的速度天然更大（同样的距离摊到更少 tick）—— 这符合直觉，也让低适宜度处的尾迹"散得快"。
- "越来越散"由两个独立效果叠加：①每颗的飘荡方向/距离随机（整体越铺越宽）；
  ②单颗尺寸 sqrt(t) 变大 + alpha 同步变淡（§9.4 的成对曲线，未改动）。
## 10. 尾迹染色（手持染料右键 + Create goggle tooltip，2026-08）

需求（用户定稿）：手持<b>染料</b>右键喷口 ⇒ 给<b>尾迹粒子</b>染色（**不是**排气/气流粒子）；
戴 Create 护目镜看喷管时，tooltip 显示当前染色。

### 10.1 文件

| 文件 | 改动 |
|---|---|
| `block/ThrustNozzleBlock.java` | `useItemOn`（`DyeItem` → 染色）；`SoundEvents.DYE_USE` + 动作栏消息。~~`useWithoutItem`（潜行空手清除）~~ 已删除 |
| `block/ThrustNozzleBlockEntity.java` | 实现 Create `IHaveGoggleInformation`；`trailDye` 字段 + `setTrailDye/trailRgb/dyeChannel`；NBT + `getUpdateTag/handleUpdateTag/getUpdatePacket`；`addToGoggleTooltip`；`spawnContrail` 改用染色出的 rgb |
| `lang/en_us.json` / `zh_cn.json` | `tooltip.ccpe.thrust_nozzle.{header,trail_dye,trail_dye_default,trail_dyed}`（`trail_dye_hint` / `trail_dye_cleared` 已随清除功能删除） |

### 10.2 数据流（服务端权威 → 客户端喷粒子）

```
右键(服务端) → DyeItem#getDyeColor().getId() → BE.setTrailDye(id)
           → setChanged() + level.sendBlockUpdated(pos, state, state, 2)
           → 客户端收到 getUpdateTag 里的 TrailDye
客户端 tickContrail → trailRgb() → ContrailParticleData(r,g,b) → ccpe:contrail
```

- **NBT key**：`TrailDye`（未染色时不写盘，读时缺省 = `NO_DYE = -1`）。
- **颜色来源**：`DyeColor#getTextureDiffuseColor()`（染料在皮革/烟花上的标准色，偏暗），
  乘 `TRAIL_DYE_BRIGHTNESS = 1.15` 再钳到 1.0 —— 全亮渲染的粒子上发闷的颜色会显脏。
  未染色时用 `TRAIL_RED/GREEN/BLUE`（现 1.0/1.0/1.0 纯白）。
- **只染尾迹**：排气粒子 `ccpe:exhaust` 仍读 `EXHAUST_RED/GREEN/BLUE` 常量，完全没接这个字段。

### 10.3 交互细节

| 操作 | 结果 |
|---|---|
| 手持染料右键 | 染成该染料色；消耗 1 个（创造模式不消耗）；播 `item.dye.use` + 动作栏「尾迹已染色：X」 |
| 同色重复右键 | 返回 SUCCESS 但**不消耗染料**（`setTrailDye` 返回 false） |
| 潜行 + 空手右键 | ~~清除染色~~ **已按用户要求删除**（无清除入口；只能用另一种染料覆盖，想恢复默认色需拆掉重放喷管） |
| 手持非染料右键 | `PASS_TO_DEFAULT_BLOCK_INTERACTION`（不干预别的交互） |

### 10.4 goggle tooltip

```
    定向喷管
     尾迹染色 红色 #B02E26
 ```

- 染料名取 `DyeItem.byColor(color).getDescriptionId()`（走原版 `item.minecraft.*_dye` 的语言文件，不额外加 16 条 lang）。
- 颜色**按染料色上色**：深色染料（黑 `0x1D1D21`、棕、灰）在深色 tooltip 背景上几乎看不见 ⇒
  `readableRgb()` 把最大通道抬到 ≥ `0x90`（只提亮度、保持色相）。

### 10.5 踩过/注意的点

1. **`getUpdatePacket()` 默认返回 `null`**，只写 `getUpdateTag` 不够 —— 客户端拿不到即时更新，
   tooltip 与粒子颜色会停在旧值（同 `QuickFillFluidTankBlockEntity` 里的注释）。三个方法都要 override。
2. **颜色必须走粒子数据**（`ContrailParticleData` 的 `r/g/b`），不能是静态常量：
   粒子类型全局共享、`ParticleEngine` 按 `options.getType()` 找 provider，颜色只能随数据下发。
3. 判定与消耗都在**服务端**；客户端只吃 `SUCCESS` 拿手感，避免两端各算一遍导致消耗不一致。
4. **不做的**：不支持任意 RGB（只有 16 种染料色）、染色不影响推力/排气粒子、不改变 alpha 与尺寸曲线。

### 10.6 进游戏验证清单

1. 手持红染料右键喷管 ⇒ 尾迹变红、有染色音效、动作栏提示；**喷口那团排气仍是原色**（本次只管尾迹）。
2. 戴 Create 护目镜看喷管 ⇒ tooltip 出现「尾迹染色 红色 #B02E26」。
3. **重进世界 / 走远让区块卸载再回来** ⇒ 颜色还在（NBT + 区块加载同步）。
4. 双人/双客户端 ⇒ 另一侧看到的颜色一致（`sendBlockUpdated` 同步）。
5. ~~潜行空手右键清除~~ —— 该入口已按用户要求删除；现在只保留染料右键染色（想恢复默认色只能拆掉重放喷管）。
6. 同色连续右键 ⇒ 染料数量不变。
7. 手持黑染料 ⇒ tooltip 文字仍看得清（`readableRgb` 提亮），尾迹本身是深灰黑。
## 11. 性能参数改为配置文件可调（2026-08，用户要求）

需求：把「粒子留存时间、每 tick 数量」这类**性能相关大参数**从代码里的 `public static final` 常量改成配置项。
落地位置：`Config.java` 的 **CLIENT** 段（`ccpe-client.toml`）—— 这些粒子只在客户端生成/推进。

### 11.1 新增配置项（16 项，默认值 = 用户当前实测值）

| 配置键 | 默认 | 作用 |
|---|---|---|
| `contrailEnabled` | true | 尾迹总开关 |
| `contrailLifeMin` / `contrailLifeMax` | 360 / 1200 | **留存量主旋钮**：寿命 18~60 s（越冷越接近上限） |
| `contrailSpacing` | 1.2 格 | **密度主旋钮**：每飞这么远吐一颗 |
| `contrailMaxPerTick` | 6 | 单 tick 补发上限（防瞬移/低帧率吐一长串） |
| `contrailMaxLive` | 6000 | **硬安全阀**：存活粒子总数上限（0 = 不限） |
| `contrailMaxDistance` | 96 格 | 发射距离上限 |
| `contrailWidth` | 1.0 格 | 出生宽度（填充率开销） |
| `contrailWidthTau` | 300 tick | 扩散时间常数（经验值 ≈ 寿命/4） |
| `contrailAlpha` | 0.6 | 出生不透明度上限 |
| `contrailDriftDistance` | 4.0 格 | 单颗一生飘荡距离（速度 = 距离 ÷ 寿命） |
| `exhaustParticlesPerTick` | 10 | **排气密度主旋钮**（满 256 RPM 时） |
| `exhaustLifetime` | 16 tick | 排气寿命（+0~9 随机） |
| `exhaustScale` | 0.3 格 | 排气尺寸 |
| `exhaustSpeed` | 2.0 格/tick | 排气初速 |
| `exhaustMaxDistance` | 64 格 | 排气发射距离上限 |

### 11.2 实现方式（为什么是访问器而不是缓存字段）

- `ThrustNozzleBlockEntity` 里原有 14 个常量 → 换成 `private static xxx()` **访问器**，函数体是
  `Config.XXX.get()`。调用点全部改成 `xxx()`（编译器逐个校验，漏一个都编不过）。
  **每次使用都现查配置** ⇒ 改完配置立刻生效，不需要重启；不存在"启动时缓存导致改了没反应"的老坑。
- `ContrailParticle` 用的 `contrailAlpha` / `contrailWidthTau` 是**每颗粒子每 tick** 都要读的两个值：
  在**构造时读一次**缓存进实例字段（`alpha0` / `widthTau`），避免 6000 颗 × 每 tick 查配置。
  副作用：改这两个值只影响**之后新生成**的粒子，老粒子保持旧值 —— 对视觉类参数是合理行为。
- lang：每个配置项两条 key —— `ccpe.configuration.<路径>`（标题）+ `.tooltip`（说明），中英各 32 条。

### 11.3 仍然是代码常量的（有意保留）

这些不是性能旋钮，而是**物理/曲线定义**，改动属于改设计而非调性能，所以留在代码里（要的话可以再往配置搬）：
`TRAIL_Y_MIN`(160 硬门)、`TRAIL_T_START/FULL/FADE_*`（峰值曲线节点）、`TRAIL_S_CEILING`、`TRAIL_TURB_CLOUD_Y` /
`TRAIL_TURB_LOW_GAIN`、`CONTRAIL_OVERWORLD_ONLY`、`TRAIL_SPACING_S_FLOOR`、`TRAIL_DRIFT_DISTANCE_MIN/MAX`、
`TRAIL_START_OFFSET`、`TRAIL_MAX_TICK_MOVE`、`TRAIL_RED/GREEN/BLUE` 与 `TRAIL_DYE_BRIGHTNESS`、
`EXHAUST_SPREAD/SPEED_RANDOM_*/RED/GREEN/BLUE`、`PARTICLES_BLOCKED_BY_OTHER_BODIES`。

### 11.4 验证

- `gradlew classes` 通过；`zh_cn.json` / `en_us.json` 均为合法 JSON（各 289 键，粒子配置 32 条）。
- 进游戏看：Mods → CC Peripheral Extender → Config → 客户端分类，应能看到「尾迹 / 排气」两段共 16 项。
  也可以直接改 `config/ccpe-client.toml`，改完存档/重进即时生效（大部分项下一次发射就生效）。







