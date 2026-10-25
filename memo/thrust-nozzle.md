# 定向喷管（`ccpe:thrust_nozzle`）工作记录

> 状态：**代码逻辑完成，已进游戏验证**（推力方向已实测校定；排气粒子已换成自研 `ccpe:exhaust`，见 §3.6）。缺合成配方；模型/贴图由用户完成。
> 粒子当前形态：自研粒子（世界坐标、无重力、扫掠碰撞 + 撞墙反弹/滑行、撞地变大）；**撞地分裂已按用户要求关闭**，`USE_EXHAUST_PARTICLE=false` 可整体回退旧 `create:air`。
> 粒子随寿命演变（v7，§3.6）：**越老越大 / 越老越慢 / 越老越透明**，三条曲线都由 `life = age/lifetime` 驱动。
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
| 15 | 离喷管约 1 区块就不出粒子（出力正常，Create 电扇同样） | 原版 `addParticleInternal` 离相机 >32 格（1024）直接丢粒子；**第一次错用 7 参数 `addAlwaysVisibleParticle`（force=false）无效**，改 9 参数 `ignoreRange=true` + 自设 64 格上限（§3.7） | 已改，待进游戏确认 |
