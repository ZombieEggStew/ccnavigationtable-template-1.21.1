# 定向喷管（`ccpe:thrust_nozzle`）工作记录

> 状态（2026-08 末）：**推力 / 排气（含轻微重力）/ 高空尾迹 / 染料染色 / 配置化 / tooltip / 扳手拆除 全部完成，
> `gradlew classes` 通过；斜喷穿墙已修复并经用户多角度实测确认**。
> **交接速览见 §13**（文件清单、玩家可见行为、参数在哪、待办）；**穿墙修复 + 重力的完整记录见 §14**，
> **扳手快速拆除见 §15**。
> 缺合成配方；模型/贴图由用户完成。
>
> 六个能力一句话版：
> 1. **推力**（§2.1）：`THRUST_PER_RPM = 1` ⇒ 1 RPM = 1 pN，气压 1.0 时 256 RPM = 256 pN/管，且随气压缩放；
> 2. **排气 `ccpe:exhaust`**（§3.6）：世界坐标、扫掠碰撞 + 撞墙反弹 + 沿面滑行、越老越大/越慢/越淡；
>    **轻微重力**由配置 `exhaustGravity`（默认 0.02）控制，设 0 = 失重；撞地分裂按用户要求关闭；
> 3. **高空尾迹 `ccpe:contrail`**（§9）：按移动距离发射、峰值适宜度曲线（Y160 起 / 183~224 最浓 / 320 最稀）、
>    匀速直线飘荡（速度 = 总飘荡距离 ÷ 寿命，§9.10）、无碰撞无重力、9 参数 `addAlwaysVisibleParticle` 远处可见；
> 4. **染料染色**（§10）：手持染料右键喷口 ⇒ 只染**尾迹**粒子，护目镜 tooltip 显示当前染色；
> 5. **配置 + tooltip**（§11、§12）：17 个参数进 `config/ccpe-client.toml`（改完即时生效）；物品 tooltip 走 Create `ItemDescription`；
> 6. **扳手拆除**（§15）：`IWrenchable` ⇒ `create:wrench` **潜行右键快速拆除**（掉落直接进背包）、普通右键不旋转。
>
> 第 7 个能力（2026-08 末新增）：**进气粒子 `ccpe:intake`**（§16）——负转速 = 吸力时，
> 空气从喷口前方被**吸进口面**（加速收拢 + 缩小淡出）；此前负转速下**一颗粒子都没有**。
> v2（§16.9）：**漏斗长度随转速缩放**（满 256 RPM = 8.0 格，128 RPM 减半）。
>
> **仍待进游戏确认**（其余已由用户实测确认）：配置**界面**的标题/说明文案、物品 tooltip 展开、
> "潜行空手清除染色"已移除、**扳手潜行右键拆除 / 普通右键不旋转**（§15.3）、**进气粒子观感**（§16.7）。
> 上游背景见 `memo/flip-autopilot.md`（火箭方案作废 ⇒ 自研喷管）；相关 `memo/my_sensor_system.md`、`memo/api-code-map.md`。

## 0. 需求与决策链（用户定稿）

| 议题 | 结论 |
|---|---|
| 动力来源 | 仍装在 `create:encased_fan` 出风面，**力由风扇转速决定**（不新增 CC 写入，Lua 脚本一行不改） |
| 力的模型 | **纯轴向推力**：不做射线、不看表面 ⇒ 任意**距离**出力相同；**乘气压**（§2.1.1）⇒ 高度有影响 |
| 强度 | **1 RPM = 1 pN**（`THRUST_PER_RPM = 1`）⇒ 气压 1.0 时 256 RPM 单管 **256 pN**。用户第三次定稿，图公式简单；初版 800 pN 实测"高达 750 多"过猛，200 pN 偏软 |
| 方块名 | `ccpe:thrust_nozzle` |
| mixin | **不需要**（见 §1） |
| 尾迹高度窗口 | **Y 160~320**，Y<160 完全无尾迹；实际做成**峰值曲线**（Y≈183~224 最浓，往上变稀），见 §9.8 |
| 尾迹发射 | **按移动距离**发射（悬停不动 ⇒ 零发射），见 §9.3 |
| 尾迹飘荡 | **匀速直线**：速度 = 总飘荡距离 ÷ 寿命，方向随机；云层以上 ×1、越低越散（模拟对流层湍流），见 §9.10 |
| 湿度 | **不计算**，全部退化为高度/温度函数 |
| 染色 | 手持**染料**右键喷口 ⇒ 只染**尾迹**粒子；护目镜显示当前色；**无清除入口**（潜行空手清除已删除），见 §10 |
| 性能参数 | **17 项全部进配置** `ccpe-client.toml`，见 §11 |
| 物品 tooltip | Create `ItemDescription`：装在风扇出风面增加出力 / 高空产生尾迹 / 染料改色，见 §12 |

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
| `block/MyModBlocks.java` | 注册 `ccpe:thrust_nozzle`（`registerBlocks` 自动带 BlockItem），音效 `SoundType.COPPER`。⚠️ **创造栏不是自动的**：本模组的标签页用显式 `output.accept(...)` 列表，必须手动在 `item/MyModCreativeModeTabs.java` 加一行（§17） |
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

### 3.3 当前参数（⚠️ v1 的 `create:air` 回退路径，现在默认不走这条）

> 现在 `USE_EXHAUST_PARTICLE = true`，实际用的是 §3.6 的自研 `ccpe:exhaust`；
> 下表只有把 `USE_EXHAUST_PARTICLE` 改回 `false` 时才生效。
> **排气粒子目前的旋钮**：密度/寿命/尺寸/初速/重力/距离已进配置（§11.1 的 `exhaust*` 六项），
> 撞墙与视觉细节在 `ExhaustParticle` 顶部。

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
> 第一版曾因下方「致命 bug」导致撞墙毫无反应而被回退，修好后重新启用 —— **已由用户实测确认**（撞墙反弹/滑行、撞地变大）。

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
- **重力**：原版 `Particle#gravity` 保持 0（tick 自己实现）；向下加速度走配置 `exhaustGravity`
  （**默认 0.02**，设 0 = 失重），与向上浮力 `BUOYANCY` 在同一条里相抵，见 §3.6.1。

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

### 3.6.1 轻微重力（`exhaustGravity`，2026-08 新增）

**实现**：`ExhaustParticle` 构造时读一次 `Config.EXHAUST_GRAVITY` 缓存进 `sinkAccel`，
`tick()` 里和浮力写在同一条：

```java
this.yd += BUOYANCY + life * BUOYANCY_GROWTH - this.sinkAccel;
```

- **默认 0.02（用户进游戏 0.002/0.005/0.02 逐档实测后定稿）**；设 0.0 = 完全失重（即加重力之前的手感）。
  0.02 出生后净下沉 ≈ 0.55 格/tick ≈ 11 格/秒，中期 ≈ 1.95、末期 ≈ 1.0 格/秒 —— 数值上不算"轻微"，
  但阻尼把轴向初速很快吃光，观感是"出膛后先沉、然后挂住"，用户认可的就是这个形态。
- 字段名**不能叫 `gravity`**：会遮蔽父类 `Particle#gravity`（原版 `Particle#tick` 用的字段；
  我们的 `tick` 整个自己实现、不用它，但遮蔽会让后来人看错）。
- **性能**：每颗每 tick 一次浮点减法。同屏 ~1000 颗 ⇒ 每 tick 1000 次加法，微秒级；
  **不触碰扫掠碰撞**（本来就每颗每 tick 一次、约 8~27 个方块的 `getBlockState`+`getCollisionShape`），
  所以重力的开销比现有每 tick 工作量低 4~5 个数量级。
- **唯一会真的推高 CPU 的间接路径**：重力 ⇒ 撞地变多 ⇒ 若把 `SPLIT_ON_IMPACT` 打开（现 false），
  子粒子数会涨。现关闭 ⇒ 无影响。
- **观感副作用**：撞地变多 ⇒ `IMPACT_GROWTH`(×2，上限 4) 触发更多、粒子整体更大；嫌大就降 `IMPACT_GROWTH`。

**取值表**（终端下沉速度 `= g × f/(1−f)`，`f = VERTICAL_FRICTION × (1 − SPEED_SLOW×life)`，
即 0.965×(1−0.28×life)；已有浮力的终端上浮 ≈ 0.022→0.004 格/tick 已扣除）：

| `exhaustGravity` | 出生后净下沉 | 中期（life 0.5） | 末期（life 0.9） | 观感 |
|---|---|---|---|---|
| 0.002 | 0.033 格/tick ≈ 0.7 格/秒 | 0.006 | 0.004 | 只是"不再往上飘" |
| 0.005 | 0.116 ≈ 2.3 格/秒 | 0.021 | 0.010 | 轻微下坠 |
| 0.010 | 0.254 ≈ 5 格/秒 | 0.046 | 0.023 | 明显"沉"，像重烟 |
| **0.02（现行默认）** | 0.551 ≈ 11 格/秒 | 0.098 | 0.052 | 出膛即沉、后段挂住（用户定稿） |

上限 0.1（配置范围 0.0~0.1）；再大就基本等于"喷完就掉"。

下坠是**前重后轻**的（阻尼让年轻粒子的终端速度被放大 27 倍、末期只有 2.6 倍），
所以看起来是"出膛后先坠、再渐渐挂住"—— 恰好是真实尾焰的形态。

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

### 3.8 ⭐ 斜喷穿墙（粒子漏到平面下方）—— 根因：`Particle#setLocationFromBoundingbox` 把 y 当**包围盒底边**

> 用户实测：喷口与平面**垂直（90°）时完全正常**（撞完沿面滑行）；**机体与平面成 30° 时全部粒子穿过去漏到下方**，
> 且"越与平面平行问题越大"。漏下去的粒子**已经变大、变慢**（说明确实撞到过东西）。

**根因（一行源码）**：`ExhaustParticle#move` 先用 `setBoundingBox(new AABB(nx±half, ny±half, nz±half))`
建了一个**以 ny 为中心**的对称盒，随后调用 `setLocationFromBoundingbox()`。而 1.21.1 的实现
（`Particle.java:223-228`，源码在 `build/moddev/artifacts/neoforge-21.1.235-sources.jar`）是：

```java
protected void setLocationFromBoundingbox() {
    AABB aabb = this.getBoundingBox();
    this.x = (aabb.minX + aabb.maxX) / 2.0;
    this.y = aabb.minY;                    // ← 位置 y 被当成包围盒"底边"（见 Particle#setPos）
    this.z = (aabb.minZ + aabb.maxZ) / 2.0;
}
```

原版把粒子位置 `y` 当**脚底**（`setPos` 里 `minY = y, maxY = y + bbHeight`），本粒子却把 `y` 当**中心**
（渲染 quad 与扫掠碰撞都用中心）。于是 **每 tick 位置被静默往下挪 `quadSize/2` 格，且这段下坠完全不在
扫掠的 displacement 里** ⇒ 不受任何碰撞检测约束的额外下坠。

这一条把四个现象全解释了：

| 现象 | 解释 |
|---|---|
| 每 tick 下坠量越来越大 | `quadSize = baseScale × (SIZE_START + life×GROWTH) × impactGrowth` 单调涨，`half` 跟着涨（出生 0.05，末期 ~0.5） |
| 90° 正常 | 垂直入射的反弹给粒子 ~0.3-0.45 格/tick 的上抛，压得住下沉 |
| 越平越漏 | 浅角度反弹的法向分量只有 ~0.05-0.09 格/tick，压不住下沉 ⇒ 净位移朝下 |
| 撞过变大的漏得更狠 | `IMPACT_GROWTH=2` 让 `quadSize`（⇒ 隐形下坠速度）**翻倍** |

**决定性证据**：抽样轨迹里 `pos` 和"上 tick 的 `mv`"逐 tick 对不上，差值正好 = `quadSize/2` 且随之增长
（例 #2460：`82.13 -1.40 → 80.68`，应到 80.73，差 0.05；下一 tick 0.06 → 0.07 → 0.08，
而 `quadSize` 每 tick 涨 0.017、`half` 涨 0.0085）。
**修法**（`ExhaustParticle#move`）：不再调 `setLocationFromBoundingbox()`，直接
`this.x = nx; this.y = ny; this.z = nz;`，包围盒围绕中心对称只用于视锥剔除。
（`ContrailParticle` 走的是 `setPos`，与原版约定自洽，**没有**这个问题。）

**旁证（临时埋点，日志 `run/logs/latest.log`，前缀 `[EXHDBG]` / `[EXHDBG-N]`）**

- 120 条"从空气进入实心方块"事件**全部落在 30° 那 3 秒内，90° 一条都没有**。
- 本 tick 扫掠结果：**MISS 76 条**；HIT 44 条的法线**全是 `(±1,0,0)`/`(0,0,±1)`，没有一条是 +Y**。
- 已变大的粒子（`grow=2.0/4.0`）占 50/120。
- 最典型一行：`t=16 (63.007) -> (62.913) grass_block 本tick=MISS qs=0.941 grow=2.0`
  —— 粒子中心只比地面高 **0.007**，而它的碰撞半径是 `0.941×0.2=0.188`：**已经在自己的碰撞壳层里**。

**次要缺陷（帮凶，也一起修了）**：`ExhaustCollision.slab` 在**起点已经落在膨胀壳层内**时
三轴的 `near` 全是负数，`entry`（初值 0）不会被 `near >= entry` 更新 ⇒ `normalAxis` 恒为 -1 ⇒ 返回 `null`，
被当成"没撞到"。于是**只要粒子沉进"半径厚"的壳层，就再也拿不到任何碰撞响应**（只有斜擦邻块侧面时拿到
`±X/±Z` 法线，而那不会消掉朝里的速度）。静默下坠 → 沉进壳层 → 碰撞彻底失效 → 穿过平面，
这两步串成了完整的漏气链路。（`IMPACT_GROWTH` 让半径翻倍，会加深壳层、加速这条链路。）

**修法**（`ExhaustCollision` + `ExhaustParticle`）

- `slab()` 增加 **①嵌入分支**：三轴都严格在膨胀盒内时不再返回 `null`，而是返回 `f=0` 的接触 +
  **推出深度**（`Hit.push`，普通命中为 0）。
- `depenetrationNormal()` 选面规则：**主判据只有"推出距离最小"（最小平移向量 MTV）**；
  运动方向只做 tie-break（深度打平时取"法线更偏向运动反侧"的面，防止卡在方块正中时被往下推）。
  ⚠️ **不能**把"法线与运动方向相反"当主判据（踩过）：粒子撞完是向上弹的，那时"与运动相反的面"
  恰好是方块**底面**，推出深度约一整格 ⇒ 粒子被**往下推穿方块**，所有角度都漏、比不修更糟。
- `ExhaustParticle#move`：`movement = hit.movement() + n × (hit.push() + CONTACT_EPSILON)`，真正把粒子挪出壳层；
  朝里的速度分量仍由 `scatter/slide` 每 tick 消掉（对外行为不变）。
- 物理体那条路径的推出距离要乘 `bodyScale`（它是在 plot 局部系量的）。
- 出生点保护（`SPAWN_GRACE_RADIUS`）不受影响：`scan` 里 `hit.fraction() < minFraction` 会把 f=0 的嵌入命中挡掉。

**调试埋点（验证完要删/关）**

- `ExhaustParticle.DEBUG_TRACE`：抽样（每 `DEBUG_SAMPLE_EVERY` 颗取 1）逐 tick 打
  `pos/vel/quadSize/impactGrowth/本tick扫掠结果`；另在"粒子中心从空气进入实心方块"时必打一行 `!! 穿墙`。
  ⚠️ 第一版漏了 `this.debugTrace &&` ⇒ 每颗都打，6000 行预算 0.7 秒烧光，等于没抽样。
- `ThrustNozzleBlockEntity.DEBUG_EXHAUST_LOG`：每秒一行喷口世界坐标、世界系排气方向、`clear`、颗粒数。
- 判读技巧：主世界方块的法线只可能是 ±X/±Y/±Z；**出现斜法线就说明这次命中来自被旋转的物理体**。

## 4. 已知边界 / 未做

- **推力不做遮挡判定**：喷口被方块堵死时物理推力照样输出（要加的话应在每个游戏 tick 算一次遮挡并缓存给 substep 复用）。
- **气压只影响推力，不影响粒子**：高空排气粒子看起来和地面一样浓（想同步就在 `spawnExhaust` 里把
  `MAX_PARTICLES_PER_TICK` 乘气压，未做）。
- 气压每 substep 求一次（`HashMap` 查维度数据 + 一次贝塞尔求值 + 一次 `Vector3d` 分配，常数级），
  多管时按管数线性增长，实测量级可忽略。
- **粒子可见距离 = `MAX_EXHAUST_DISTANCE` = 64 格**（§3.7）。原版对 `addParticle` 的硬限制是 32 格，
  已用 9 参数 `addAlwaysVisibleParticle(..., ignoreRange = true)` 绕过；但超过 64 格我们主动不生成（省扫掠碰撞）。
  远处的可见度还受"粒子本身很小很淡"影响（`EXHAUST_SCALE=0.3` ⇒ 出生仅 0.1 格），
  真嫌远处看不见就得放大 `EXHAUST_SCALE`/`ALPHA_MAX`，而不是再改生成逻辑。
- 排气粒子是纯客户端计算：各客户端看到的撞墙点/散射方向各自独立（原版粒子行为，未做服务端广播）。
- **重力只作用于排气粒子**（`exhaustGravity`，默认 0.02，§3.6.1），尾迹仍然完全无重力（它是冰晶云）。
  重力让"撞地"变频繁 ⇒ `IMPACT_GROWTH` 触发更多、粒子整体更大；嫌大就降 `IMPACT_GROWTH`（现 2.0）。
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
2. 排气粒子（`ccpe:exhaust`，§3.6）若要继续调手感：
   密度/寿命/尺寸/初速/重力/距离 = **配置项** `exhaustParticlesPerTick / exhaustLifetime / exhaustScale / exhaustSpeed / exhaustGravity / exhaustMaxDistance`（§11）；
   撞墙与视觉细节（`BOUNCE / TANGENT_KEEP / SPREAD_KEEP / ENERGY_KEEP / IMPACT_GROWTH`、
   `ALPHA_MAX / ALPHA_FALLOFF / SIZE_START / SIZE_GROWTH / SPEED_SLOW / COLLISION_COOLDOWN`）
   仍在 `ExhaustParticle` 顶部（代码常量，改完热重载即看）。
   `PARTICLES_BLOCKED_BY_OTHER_BODIES` 仍决定其它物理体是否挡粒子（现 true）。
3. 回到 `flip-autopilot.md`：用新喷管重做落地/倒挂调参（悬停油门 **43%**（气压 1.0），
   原来是按 14% 算的；控制律的增益/死区要重标定，且滚转单边差动只剩 6.4 rad/s² 的零余量，
   建议用双边反向差动）。
   **气压项（§2.1.1）让 14% 只在海平面成立**，高空油门要更大；嫌麻烦就让脚本用 `sensor` 的气压做前馈，
   或先把 `THRUST_SCALES_WITH_AIR_PRESSURE` 关掉再标控制律。
4. ~~高空尾迹（contrail）~~：**已实现并经用户多轮实测定稿**（§8 计划 → §9 实现 → §9.7~§9.10 四轮模型迭代）。
   参数已全部配置化（§11），玩家直接改 `ccpe-client.toml` 即可调手感。
5. 下次进游戏要确认的三件小事（其余都已确认）：
   ① 配置**界面**能看到 17 项粒子配置且标题/说明文案正常（"改 toml 即时生效"已由 `exhaustGravity` 实测确认）；
   ② 物品 tooltip 按住 SHIFT 能展开 summary + 3 组说明；
   ③ 空手/潜行空手右键喷口已经**完全没有反应**。
6. 若要继续美化尾迹，可考虑（都还没做）：把 `minecraft:generic_0..7` 换成专用白云贴图；
   或把尾迹做成带状 mesh 以实现"几分钟长"的真正尾迹（§8.6 提过，工作量大）。

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

## 7. 变更时间线（2026-08，排气 → 尾迹 → 染色 → 配置 → tooltip，含踩过的坑）

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
| 14 | 想要"1 RPM 对应 1 pN"的简单公式 | `THRUST_PER_RPM = 1.0`；连带：悬停 43%、4 管升限 y≈273、落地 25.4 m/s² | ✅ 已实测 |
| 15 | 离喷管约 1 区块就不出粒子（出力正常，Create 电扇同样） | 原版 `addParticleInternal` 离相机 >32 格（1024）直接丢粒子；**第一次错用 7 参数 `addAlwaysVisibleParticle`（force=false）无效**，改 9 参数 `ignoreRange=true` + 自设 64 格上限（§3.7） | ✅ 用户实测解决 |
| 16 | 想要"真实高空尾迹"预研 | 科普 + 需求定稿 + 实现计划（§8），未动代码 | 计划就绪 |
| 17 | 开始实现尾迹 | 静态化 `ambientTempAt`、新粒子类型 `ccpe:contrail` + `ContrailParticle`、`tickContrail()` 按位移发射（§9） | 编译通过，待进游戏验证 |
| 18 | 尾迹看不清 / 太短 / 太小 | 染色 冰晶白 → 冷青 `0.45/0.80/1.00`；`ALPHA0` 0.14 → 0.28；寿命 60/300 → **180/600**；`WIDTH0` 0.6 → **1.0**；`WIDTH_TAU` 100 → 200（§9.5） | 编译通过，待进游戏确认 |
| 19 | 用户把寿命调到 12000~18000 后：**分布很散** + **要等一段才显现** | 淡入改固定 6 tick（原为寿命的 5% ⇒ 45 s）；`TRAIL_MAX_DRIFT_RADIUS=2.0` 给随机游走加边界、`TRAIL_DRIFT` 0.2→0.05；`TRAIL_SPACING_S_FLOOR=0.35` 防止低 s 时间距拉到 12 格；新增 `MAX_LIVE_CONTRAIL=6000` 存活安全阀（`ContrailBudget`，§9.7） | 编译通过，待进游戏确认 |
| 20 | 云层在 Y200、要求峰值曲线 + Y160 就要看清 | s 从单调爬升改为峰值曲线（上升段 × 高空变稀段）+ `TRAIL_Y_MIN=160` 阶跃硬门；节点与实测表见 §9.8 | 编译通过，待进游戏确认 |
| 21 | 要粒子**随时间越来越散** | 扩散半径从常数改 `R0 + RATE·√age`（软边界 + 6 格封顶）；边界内朝外速度按 `(dist/半径)²` 衰减（§9.9） | 编译通过，待进游戏确认 |
| 22 | 改成「初速 = 飘荡距离 ÷ 寿命」，且飘荡距离随高度变化（云层×1、越低越散） | 删除随机游走/抖动/浮点边界；`spawnContrail` 反算恒定速度，新增 `trailTurbulence()`（200→1.0，160→2.5）与 `TRAIL_DRIFT_DISTANCE=2.0`（§9.10） | 编译通过，待进游戏确认 |
| 23 | 想手感染料给**尾迹**染色 + 护目镜显示 | `useItemOn`/`useWithoutItem` 染料交互、BE 存 `trailDye`（NBT + `getUpdateTag/handleUpdateTag/getUpdatePacket` 同步）、`IHaveGoggleInformation` tooltip，颜色走进 `ContrailParticleData`（§10） | ✅ 有效果，用户确认 |
| 24 | 性能参数要能配置调 | 17 个性能参数搬进 `ccpe-client.toml`（`Config` CLIENT 段）；BE 的常量换成 `Config.XXX.get()` 访问器（现查现用，改完即时生效），粒子里每 tick 用到的 alpha/tau 在构造时缓存（§11） | 编译通过，待进游戏确认 |
| 25 | 去掉「潜行空手右键清除染色」 | 删 `ThrustNozzleBlock#useWithoutItem`、goggle 的清除提示行、lang 的 `trail_dye_hint` / `trail_dye_cleared`（§10） | ✅ 编译通过 |
| 26 | 物品 tooltip 要说明用途与染色 | 照 `trailing_wheel_mount` 注册 `ItemDescription.Modifier`（FMLClientSetupEvent），7 条 lang（summary + 3 组 condition/behaviour），文案不带 `_` 避免高亮奇偶翻转（§12） | 编译通过，待进游戏确认 |
| 27 | **斜喷时粒子穿墙、漏到平面下方**（90° 正常，越平越糟） | 埋点两轮都修错方向（先以为只是 `slab` 缺"起点已重叠"分支），最后靠"pos 与上 tick 的 mv 逐 tick 对不上、差值 = quadSize/2"定位到真因：`move` 里调用 `setLocationFromBoundingbox()`，而它把 y 当包围盒**底边** ⇒ 每 tick 静默下坠 `quadSize/2` 且不被扫掠覆盖。改成直接写 `x/y/z`；同时保留 `slab` 的嵌入分支 + MTV 推出作为加固（§3.8） | 编译通过，待进游戏确认 |
| 28 | 想要排气"轻微受一点重力" | 新增配置 `exhaustGravity`，`ExhaustParticle` 构造时缓存进 `sinkAccel`、每 tick 一次减法；附取值表与性能结论（§3.6.1）。用户逐档实测后**默认值定为 0.02** | ✅ 用户确认效果符合预期 |
| 29 | 想要「扳手潜行右键快速拆除」 | `ThrustNozzleBlock` 加 `IWrenchable`：潜行 = 默认 `onSneakWrenched`（掉落直接进背包），普通右键显式返回 PASS（不旋转，朝向由风扇出风面唯一决定）（§15） | 编译通过，待进游戏确认 |
| 30 | **想要"产生吸力时也有粒子特效"** | 查实根因：负转速时排气方向翻到 `-FACING`，遮挡射线一出膛就撞到**喷管自己的碰撞箱**（`clear ≈ 0.08 < 0.2`）⇒ **一颗粒子都没有**。新建 `ccpe:intake` 进气粒子：前方锥形区域出生、`t²` 加速收拢到口面中心、缩小淡出；负转速时也**不出尾迹**；6 个参数进配置（§16） | ✅ 用户实测确认观感符合预期 |
| 31 | 想要**进气漏斗长度随转速变化**（256 RPM = 8.0 格） | 配置键 `intakeReach` → **`intakeFunnelLength`**（默认 8.0 = 满转速时的长度），`reach = max(1.0, 本值 × 转速/256)`；`INTAKE_MAX_CONE_RADIUS` 1.6 → 2.5（否则 8 格的漏斗看着像细吸管）（§16.9） | 编译通过，待进游戏确认 |
| 32 | **喷管没进创造物品栏**（一直漏做） | 本模组标签页是**显式 `output.accept(...)` 列表**，`registerBlocks` 只给 BlockItem、不给创造栏 ⇒ 在 `MyModCreativeModeTabs.displayItems` 末尾（"其他"分区、`trailing_wheel_mount` 之后）加一行（§17） | 编译通过，待进游戏确认 |

## 8. 下一步：高空尾迹（contrail）实现计划

> 状态：**已实现**（2026-08，代码见 §9；§8.7 的清单已全部落地，两处偏离见 §9.0）。
> ⚠️ 本节 §8.5 的「原地随机飘逸（随机游走）」方案**已被 §9.10 取代**成「匀速直线：速度 = 总飘荡距离 ÷ 寿命」；
> §8.4 的适宜度曲线也已被 §9.8 的**峰值曲线**取代。设计动机仍值得读，具体公式以上述两节和 §13 为准。
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
- ~~**原地随机飘逸**~~（⚠️ **已删除**，见 §9.10 的匀速直线模型；下面这几行仅作历史记录）：
  每 tick 抖动 `JITTER=0.0016` + 缓慢正弦 `SWIRL=0.0006`（φ=0.07/tick）+ 浮力，
  再乘 `DRIFT_DAMP=0.985` 并**把速度钳到 `TRAIL_DRIFT`**⇒ 一生最多漂约 6 格。
- **光照/渲染**：继承 `SimpleAnimatedParticle` 直接拿到 `PARTICLE_SHEET_TRANSLUCENT` 与全亮
  `getLightColor = 15728880`（天空 15 + 方块 15）—— 冰晶不该被暖色光源染黄。
  精灵图**出生时随机定死一张**（不调 `setSpriteFromAge`），避免 1000 颗同时跳帧。
- **必须用 9 参数 `addAlwaysVisibleParticle(..., true, ...)`**（§3.7 的老坑），尾迹比尾焰更需要远处可见。

### 9.5 常量总表（⚠️ v1 历史记录）

> **已被后来三次迭代覆盖**：适宜度曲线改成峰值（§9.8）、飘荡模型改成"总距离 ÷ 寿命"（§9.10）、
> 且下列带 ★ 的项**已全部搬进配置文件**（§11）。**当前生效值请看 §13.4 与 `config/ccpe-client.toml`**，
> 本表只用于理解 v1 的设计意图。

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

### 9.6 进游戏验证清单（⚠️ v1 历史记录；最终验证清单见 §13.5）

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
### 9.9 v4：粒子随时间**越来越散**（⚠️ 已被 §9.10 取代，软边界模型已删除，仅作踩坑记录）

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

### 11.1 新增配置项（17 项，默认值 = 用户当前实测值）

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
| `exhaustGravity` | 0.02 格/tick² | **排气重力**（0 = 失重 = 加重力之前的手感）。终端下沉速度 = 重力 × f/(1−f)，f = 0.965×(1−0.28×寿命进度) ⇒ 下坠前重后轻。0.02 是用户逐档实测（0.002/0.005/0.02）后定稿的值，公式与取值表见 §3.6.1 |
| `exhaustMaxDistance` | 64 格 | 排气发射距离上限 |

### 11.2 实现方式（为什么是访问器而不是缓存字段）

- `ThrustNozzleBlockEntity` 里原有 14 个常量 → 换成 `private static xxx()` **访问器**，函数体是
  `Config.XXX.get()`。调用点全部改成 `xxx()`（编译器逐个校验，漏一个都编不过）。
  **每次使用都现查配置** ⇒ 改完配置立刻生效，不需要重启；不存在"启动时缓存导致改了没反应"的老坑。
- `ContrailParticle` 用的 `contrailAlpha` / `contrailWidthTau` 是**每颗粒子每 tick** 都要读的两个值：
  在**构造时读一次**缓存进实例字段（`alpha0` / `widthTau`），避免 6000 颗 × 每 tick 查配置。
  副作用：改这两个值只影响**之后新生成**的粒子，老粒子保持旧值 —— 对视觉类参数是合理行为。
- `exhaustGravity` 同样在**粒子构造时读一次**缓存进 `ExhaustParticle#sinkAccel`：
  每 tick 只是 `yd -= sinkAccel` 一次浮点减法，**不动扫掠碰撞**（本来就每颗每 tick 一次），
  代价可忽略（同屏 1000 颗 ≈ 每 tick 1000 次加法，微秒级）。字段名故意不叫 `gravity`，
  以免遮蔽父类 `Particle#gravity`（原版 `Particle#tick` 用的，我们整个 tick 自己实现、不用它）。
- lang：每个配置项两条 key —— `ccpe.configuration.<路径>`（标题）+ `.tooltip`（说明），中英各 34 条。

#### 11.2.1 「现查配置」到底贵不贵？（结论：**不用自己加缓存层**）

NeoForge 的 `ModConfigSpec.ConfigValue#get()` **本身就是缓存的**
（`ModConfigSpec.java:1219-1225`，源码在 `build/moddev/artifacts/neoforge-21.1.235-sources.jar`）：

```java
public T get() {
    if (cachedValue == null) { cachedValue = getRaw(); }   // ← 命中缓存就是一次字段读
    return cachedValue;
}
```

`cachedValue` 只在**配置重载**时被 `clearCache()` 置空（1279-1281 行），所以：

| 说法 | 实际 |
|---|---|
| "现查 = 每次都要遍历 spec / 读文件" | 否。就是一次字段读 + 一次 null 判断，纳秒级、无锁 |
| "调用量大所以要缓存" | 量级：每颗粒子 4 次（初速/尺寸/寿命/重力）+ 每 tick 每管的尾迹参数十几次 ⇒ 满配 4 管约 **每 tick 200~500 次** `get()` ≈ 每帧几十纳秒到微秒，**比一次扫掠碰撞低 3~4 个数量级** |
| "缓存能省性能" | 省不到东西；反而要自己维护"何时刷新"，重蹈"启动时缓存 ⇒ 改了没反应"的坑（§11.2 第一条） |

所以现行策略是对的：**默认现查**（改配置立即生效），只有"每颗每 tick 都要用"的视觉参数
（`contrailAlpha`/`contrailWidthTau`/`sinkAccel`）才在构造时读一次缓存 —— 那不是为了性能，
而是为了让同一颗粒子一生的参数保持一致（否则一颗粒子在寿命中途被改配置会突变）。

真要再省（现阶段没必要）：正确做法不是"缓存到字段"，而是在 `tickClient()` 顶部**每 tick 批量读一次**
到局部变量再往下传 —— 既保持实时性又只读一次。当前调用点分散在 `spawnExhaust`/`spawnContrail` 里，
为省这点开销不值得重排代码。

### 11.3 仍然是代码常量的（有意保留）

这些不是性能旋钮，而是**物理/曲线定义**，改动属于改设计而非调性能，所以留在代码里（要的话可以再往配置搬）：
`TRAIL_Y_MIN`(160 硬门)、`TRAIL_T_START/FULL/FADE_*`（峰值曲线节点）、`TRAIL_S_CEILING`、`TRAIL_TURB_CLOUD_Y` /
`TRAIL_TURB_LOW_GAIN`、`CONTRAIL_OVERWORLD_ONLY`、`TRAIL_SPACING_S_FLOOR`、`TRAIL_DRIFT_DISTANCE_MIN/MAX`、
`TRAIL_START_OFFSET`、`TRAIL_MAX_TICK_MOVE`、`TRAIL_RED/GREEN/BLUE` 与 `TRAIL_DYE_BRIGHTNESS`、
`EXHAUST_SPREAD/SPEED_RANDOM_*/RED/GREEN/BLUE`、`PARTICLES_BLOCKED_BY_OTHER_BODIES`。

### 11.4 验证

- `gradlew classes` 通过；`zh_cn.json` / `en_us.json` 均为合法 JSON（各 296 键，粒子配置 34 条）。
- 进游戏看：Mods → CC Peripheral Extender → Config → 客户端分类，应能看到「尾迹 / 排气」两段共 17 项。
  也可以直接改 `config/ccpe-client.toml`，改完存档/重进即时生效（大部分项下一次发射就生效）。
## 12. 物品 tooltip（2026-08，参考 trailing_wheel_mount）

### 12.1 注册方式（与项目里已有 4 个方块完全一致）

```java
// CCPeripheralExtenderClient#onClientSetup(FMLClientSetupEvent)
Item thrustNozzleItem = MyModBlocks.thrust_nozzle.get().asItem();
TooltipModifier.REGISTRY.register(thrustNozzleItem,
        new ItemDescription.Modifier(thrustNozzleItem, FontHelper.Palette.STANDARD_CREATE));
```

- **必须在 `FMLClientSetupEvent` 里注册**，不能放 mod 构造器：那时 `DeferredHolder` 还没绑定
  （unbound value 直接崩）—— 这条坑 `fluid_port` 的注释里已经写过。
- 参考实现：`CCPeripheralExtenderClient` 里 `fluid_port` / `quick_fill_fuel_vault` /
  `quick_fill_fluid_tank` / `trailing_wheel_mount` 四处同款，本喷管是第 5 个。

### 12.2 lang 键（Create `ItemDescription` 的约定）

键前缀是**物品的 descriptionId**（= `block.ccpe.thrust_nozzle`，不是我们 goggle 用的 `tooltip.ccpe.*` 命名空间）：

```
block.ccpe.thrust_nozzle.tooltip.summary
block.ccpe.thrust_nozzle.tooltip.condition1 / behaviour1
block.ccpe.thrust_nozzle.tooltip.condition2 / behaviour2
block.ccpe.thrust_nozzle.tooltip.condition3 / behaviour3
```

- 条件循环 `for (i = 1; ; i++)`，**遇到不存在的 `conditionN` 就停** ⇒ 编号必须从 1 连续。
- 平时只显示「按住 SHIFT 查看描述」，按住 SHIFT 才展开 summary + 每组残 condition/behaviour。

### 12.3 内容（用户要求的 3 点）

| 键 | 中文 |
|---|---|
| summary | 装在 encased fan 的出风面上，把风扇的风压变成定向推力，比只用风扇本身有力得多。 |
| condition1 / behaviour1 | 装在前方出风面的 encased fan 上时 → 推力随风扇转速提高；高空气压低时会明显变小 |
| condition2 / behaviour2 | 在高空飞行时 → 喷口会拖出一条冰晶尾迹：约 Y160 以上才有，越靠近云层越浓 |
| condition3 / behaviour3 | 手持染料右键点击时 → 把尾迹粒子染成该染料的颜色 |

### 12.4 ⚠️ Create tooltip 的 `_` 高亮坑（写文案前必须知道）

`TooltipHelper#cutTextComponent` 用 `<u>_</u>` 把一句话切成段并交替上色，而且**每处理完一行会翻转一次
`currentlyHighlighted`**。结果是：只要一行里有下划线，后面所有行的奇偶颜色都会跟着翻 ——
稍微写歪就出现「普通文字比高亮还亮」的错觉。

⇒ 本项目这 7 条文案**全部写成不带下划线的单行**文本，颜色稳定为 palette.primary。
以后想加高亮：要么整条只用一对 `_`，**并且一次性检查所有行**，要么就别用。
## 13. 现状总览（交接用，2026-08 末更新）

> 这一节是给「下次接手的人（或未来的自己）」看的速查：不用读 §1~§12 的历史过程也能上手。

### 13.1 功能一览

| 能力 | 做法 | 状态 | 章节 |
|---|---|---|---|
| 定向推力 | 纯轴向恒定推力，点力打进 `sable:propulsion` 力组；1 RPM = 1 pN，乘气压 | ✅ 已实测校准 | §2.1 / §2.2 |
| 排气粒子 `ccpe:exhaust` | 自研粒子：世界坐标、扫掠碰撞（含"起点已嵌入"推出）+ 撞墙反弹滑行 + 撞地变大、随寿命越大/越慢/越淡、**可选轻微重力** | ✅ 已实测（含多角度不穿墙） | §3.6 / §3.6.1 / §3.8 |
| 高空尾迹 `ccpe:contrail` | 自研粒子：按**移动距离**发射、峰值适宜度曲线、匀速直线飘荡（速度 = 总距离 ÷ 寿命）、无碰撞无重力 | ✅ 已实测（4 轮迭代） | §8 / §9 / §9.7~§9.10 |
| 进气粒子 `ccpe:intake` | 自研粒子：负转速（吸力）时在喷口前方锥形区域出生，`t²` 加速收拢到口面中心 + 缩小淡出；无碰撞；**漏斗长度随转速缩放**（256 RPM = 8 格）；此时不出尾迹 | ✅ v1 已实测；v2 长度缩放待确认 | §16 / §16.9 |
| 染料染色 | 手持染料右键喷口 ⇒ 只染**尾迹**；服务端权威 + BE 数据同步；护目镜 tooltip 显示当前色 | ✅ 已实测 | §10 |
| 配置化 | 17 个参数进 `config/ccpe-client.toml`（`ConfigValue#get()` 本身带缓存，现查不贵，见 §11.2.1） | ✅ 改 toml 即时生效已实测；界面文案待看 | §11 |
| 物品 tooltip | Create `ItemDescription`，Shift 展开 summary + 3 组说明 | ⏳ 待入游戏确认 | §12 |
| 扳手拆除 | `IWrenchable`：**潜行右键 = 快速拆除**（掉落直接进背包），普通右键**不旋转** | ⏳ 待入游戏确认 | §15 |
| mixin | **零 mixin**：Create 的 `NozzleBlockEntity` 对物理体无效、Sable 的力逻辑在 mixin 里 ⇒ 自研方块 + BE 成本最低 | ✅ | §1 |

### 13.2 文件清单

**Java（`src/main/java/com/zzy205/myfirstmod/`）**

| 文件 | 职责 |
|---|---|
| `block/ThrustNozzleBlock.java` | `DirectionalBlock` + `EntityBlock` + `IWrenchable`：`FACING`、`canSurvive`（贴风扇出风面）、六向选择框、客户端 ticker、**染料右键**、**扳手潜行拆除/不旋转** |
| `block/ThrustNozzleBlockEntity.java` | 主体：`sable$physicsTick` 施力、`tickClient`（**按转速符号分成排气/进气两路**）/`tickContrail`/`tickIntake`/`spawnExhaust`/`spawnContrail`、适宜度曲线、染色状态 + NBT/同步、goggle tooltip、全部配置访问器 |
| `particle/MyModParticles.java` | 注册 `ccpe:exhaust` / `ccpe:contrail` / `ccpe:intake` |
| `particle/ExhaustParticleData.java` | 排气粒子参数 record（scale/lifetime/rgb/ownerId/gen） |
| `particle/ContrailParticleData.java` | 尾迹粒子参数 record（width/lifetime/rgb） |
| `particle/IntakeParticleData.java` | 进气粒子参数 record（scale/lifetime/rgb）——位移走 vx/vy/vz，不占额外字段 |
| `particle/ContrailBudget.java` | 尾迹存活计数（common 包、只有 int，供 `contrailMaxLive` 安全阀用） |
| `client/particle/ExhaustParticle.java` | 排气粒子：碰撞 + 撞墙散射 + 撞地变大 + 重力 `sinkAccel`（+ 默认关闭的分裂）。⚠️ `move()` **不能**调 `setLocationFromBoundingbox()`（§3.8） |
| `client/particle/ExhaustCollision.java` | 球-箱扫掠碰撞（主世界逐方块 + 其它物理体反投）；含**起点已嵌入**分支 + 推出深度 `Hit.push`（§3.8） |
| `client/particle/ContrailParticle.java` | 尾迹粒子：匀速直线、sqrt(t) 扩散、alpha ∝ 1/宽度、全亮渲染 |
| `client/particle/IntakeParticle.java` | 进气粒子：`t²` 参数插值收拢（不积分速度、不碰撞）、尺寸缩小、淡入淡出、全亮渲染 |
| `block/EngineCoreBlockEntity.java` | 提供 `public static float ambientTempAt(Level, float y)`（尾迹曲线复用引擎的高度→温度） |
| `Config.java` | CLIENT 段新增 17 项粒子配置 |
| `CCPeripheralExtenderClient.java` | 粒子 provider 注册、`ContrailParticle.onClientTick` 记账、物品 tooltip 注册 |
| `block/MyModBlocks.java` / `block/MyModBlockEntities.java` | `ccpe:thrust_nozzle` 方块与 BE 注册 |
| `item/MyModCreativeModeTabs.java` | 把 `thrust_nozzle` 加进本模组创造物品栏（**显式列表，不写就不出现**，§17） |

**资源 / 数据**

| 文件 | 内容 |
|---|---|
| `assets/ccpe/blockstates/thrust_nozzle.json`、`models/block|item/thrust_nozzle.json`、`textures/block/thrust_nozzle.png` | 模型（用户 Blockbench 制作）；blockstate 未旋转变体 = `facing=up` |
| `assets/ccpe/particles/exhaust.json`、`particles/contrail.json`、`particles/intake.json` | 精灵图列表，都是 `minecraft:generic_0..7` |
| `assets/ccpe/lang/{zh_cn,en_us}.json` | 方块名、物品 tooltip 7 条、goggle 4 条、配置 34 条 |
| `data/ccpe/loot_table/blocks/thrust_nozzle.json` | 挖掘掉落 |
| `data/ccpe/physics_block_properties/thrust_nozzle.json` | `sable:mass = 1.0`、`sable:volume = 0.5` |

### 13.3 玩家可见行为

| 操作 | 结果 |
|---|---|
| 贴在 `create:encased_fan` 出风面放置 | 生效；背后风扇被拆/转走 ⇒ 喷管掉落 |
| 风扇正转 | 机体沿 **-FACING** 受力（喷口朝下 = 上升/刹车）；反转 ⇒ 下压/吸附 |
| 高空飞行（Y ≥ 160） | 喷口后方拖出尾迹：Y 160 起出现，183~224 最浓最长，越接近 Y320 越稀 |
| 风扇**反转（负转速）** | 机体被吸向喷口朝向；喷口前方出现**进气粒子**（向口面收拢、缩小消失）；此时**没有排气、也没有尾迹** |
| 悬停不动 | **不出尾迹**（按移动距离发射），旧粒子自然淡掉 |
| 手持染料右键 | 尾迹染成该染料色（消耗 1 个，创造不消耗，同色不消耗）+ 音效 + 动作栏提示 |
| 空手 / 潜行空手右键 | **无任何反应**（清除入口已按用户要求删除） |
| **潜行右键扳手** | **快速拆除**：喷管直接进背包（不必捡），播 `WRENCH_REMOVE` 音效；物理体/普通世界都有效 |
| **普通右键扳手** | **不旋转**，什么都不发生（朝向只由背后电扇的出风面决定） |
| 戴 Create 护目镜看喷管 | `定向喷管` / `尾迹染色 <染料名> #RRGGBB`（未染色显示 `默认（未染色）`） |
| 物品栏按住 SHIFT | summary（装在 encased fan 出风面增加出力）+ 3 组：风扇/高空尾迹/染料染色 |
| 改 `config/ccpe-client.toml` | 尾迹与排气的寿命、密度、上限、尺寸、初速、**重力**等 17 项即时生效（粒子类参数只对之后新生成的粒子生效） |
| 任何角度喷向平面/墙面 | 粒子撞上后**沿面滑行**，不再穿墙漏到下方（§3.8，已多角度实测） |
| `exhaustGravity` = 0.02（默认） | 排气离开喷口后先下沉、随后渐渐挂住；设 0 → 回到完全失重的飘散手感 |

### 13.4 参数在哪儿（三层，按可改性排序）

1. **配置文件**（`config/ccpe-client.toml`，**23 项**，详见 §11.1 与 §16.4）：`contrailEnabled`、`contrailLifeMin/Max`、
   `contrailSpacing`、`contrailMaxPerTick`、`contrailMaxLive`、`contrailMaxDistance`、`contrailWidth`、
   `contrailWidthTau`、`contrailAlpha`、`contrailDriftDistance`、`exhaustParticlesPerTick`、`exhaustLifetime`、
   `exhaustScale`、`exhaustSpeed`、`exhaustGravity`、`exhaustMaxDistance`、`intakeEnabled`、
   `intakeParticlesPerTick`、`intakeFunnelLength`、`intakeSpeed`、`intakeScale`、`intakeMaxDistance`。
   BE 里通过 `Config.XXX.get()` 访问器**现查现用**（改完即时生效）。
2. **代码常量 = 物理/曲线定义**（`ThrustNozzleBlockEntity` 顶部，见 §11.3）：`TRAIL_Y_MIN`(160 硬门)、
   `TRAIL_T_START/FULL/FADE_START/FADE_END`、`TRAIL_S_CEILING`、`TRAIL_TURB_CLOUD_Y`、`TRAIL_TURB_LOW_GAIN`、
   `CONTRAIL_OVERWORLD_ONLY`、`TRAIL_SPACING_S_FLOOR`、`TRAIL_DRIFT_DISTANCE_MIN/MAX`、`TRAIL_START_OFFSET`、
   `TRAIL_MAX_TICK_MOVE`、`TRAIL_RED/GREEN/BLUE`、`TRAIL_DYE_BRIGHTNESS`、`NO_DYE`、`THRUST_PER_RPM`、
   `REVERSE_SIGN`、`THRUST_SCALES_WITH_AIR_PRESSURE`、`EXHAUST_SPREAD`、`EXHAUST_SPEED_RANDOM_MIN/MAX`、
   `PARTICLES_BLOCKED_BY_OTHER_BODIES`、`USE_EXHAUST_PARTICLE`、**`INTAKE_CONE_RATIO` /
   `INTAKE_MAX_CONE_RADIUS` / `INTAKE_MIN_DISTANCE` / `INTAKE_MIN_LIFETIME` / `INTAKE_MAX_LIFETIME` /
   `INTAKE_RED/GREEN/BLUE`**（§16.4）。
3. **粒子类内部视觉常量**：`ExhaustParticle`（`ALPHA_MAX`/`BOUNCE`/`TANGENT_KEEP`/`SPREAD_KEEP`/`ENERGY_KEEP`/
   `IMPACT_GROWTH`/`COLLISION_COOLDOWN`/`SIZE_*`/`SPEED_SLOW`…）、`ContrailParticle`（`FADE_IN_TICKS`/`FADE_OUT_START`）、
   `IntakeParticle`（`ALPHA_MAX`/`SIZE_START`/`SIZE_END`/`SIZE_FALLOFF`/`ACCEL`/`FADE_IN_TICKS`）。

### 13.5 验证状态

**已由用户进游戏实测确认**：推力方向与手感、气压项、排气粒子各形态、尾迹出现的高度与密度、
尾迹 32 格外可见、尾迹**越来越散**的手感、染料染色有效果、
**排气在任意喷口角度下都不再穿墙/漏到平面下方**（§3.8，0°/30°/90° 多轮实测）、
**排气轻微重力手感**（用户逐档试 0.002 / 0.005 / 0.02 后定稿 `exhaustGravity = 0.02`）、
**改 `ccpe-client.toml` 即时生效**（`exhaustGravity` 改完当次游戏内即看到变化）。

**还没进游戏确认的四项**：
1. 配置**界面**（Mods → … → Config）里 17 项的标题/说明文案是否正常；
2. 物品 tooltip：Shift 展开是否正常、中文不是原始 key、`condition1..3` 连续；
3. 潜行/空手右键喷口是否确实已无反应；
4. **扳手**：潜行右键能拆掉并且喷管直接进背包；普通右键不旋转（§15）。

### 13.6 已知边界 / 未做

- **推力不做遮挡判定**：喷口被堵死时物理推力照样输出。
- **尾迹/排气都是纯客户端计算**：各客户端看到的撞墙点、散射方向、染色粒子各自独立（原版粒子行为，未做服务端广播）。
- **没有合成配方**（`data/ccpe/recipe/thrust_nozzle.json` 缺失）；材料待用户定。
  > 注（2026-08 末补：写 §15 时发现）`src/main/resources/data/ccpe/recipe/thrust_nozzle.json` **文件已存在但未纳入 git**
  > （`git status` 里是 `??`），内容 = 3×`create:andesite_alloy` + 1×`minecraft:copper_ingot` 的 shapeless 外观的
  > shaped 配方（`pattern: " # " / " X " / "###"`）。若那是想用的配方，本条待办可关掉并 `git add`。
- **没有原版实体推挤**（旧 `create:nozzle` 会推物品/生物）。
- **排气撞地分裂仍关闭**（`SPLIT_ON_IMPACT=false`），代码/TAG/队列都还在，改回 true 即恢复。
- **蓝图/装配兼容未专门验证**：`canSurvive` 会在部署瞬间复核风扇。
- **染色只有 16 种染料色**，不支持任意 RGB；且**没有清除入口**（想恢复默认只能拆掉重放）。
- **不新增 CC 写入**：喷管不是外设，出力由风扇转速决定 ⇒ `flip-autopilot.md` 的写入预算不变。

### 13.7 踩过的坑速查（重踩最费时间的都在这里）

| 现象 | 根因 | 处置 | 章节 |
|---|---|---|---|
| 装了喷管后风扇本身不推了 | Sable 的 `isActive()` 要求风扇前方是空气，喷管占住了那一格 | 属预期：总推力 = 喷管这一路 | §1.4 |
| 物理体被瞬间打飞 | 复用了力向量/作用点（`applyImpulseAtPoint` 就地改写入参） | 每次新建 `Vector3d` | §2.1 |
| 粒子跟着车跑（像子物体） | 传了 plot 局部坐标，`AirParticleMixin` 每 tick 按当前姿态重投影 | 出生点/位移先 `projectToWorld` | §3.5 |
| 物理体挡得住粒子、静态方块挡不住 | 射线在 plot 系里打，主世界命中的是**世界系**坐标 | 射线改到世界系、命中点统一换算 | §3.4 |
| 离喷管 1 区块就没粒子 | 原版 `addParticleInternal` 离相机 >32 格直接丢弃 | 用 **9 参数** `addAlwaysVisibleParticle(ignoreRange=true)` | §3.7 |
| 粒子穿透地面/墙面 | `getCollisionShape` 返回**方块局部** AABB，漏加 `pos` 偏移 | 加偏移（`ExhaustCollision#slab` 直接收偏移） | §3.6 |
| **斜喷时全部粒子漏到平面下方**（越接近平行越糟） | `move()` 调了 `setLocationFromBoundingbox()`，它把 `y` 当包围盒**底边** ⇒ 每 tick 静默下坠 `quadSize/2`，且不在扫掠位移里 | 改成直接写 `this.x/y/z` | §3.8 |
| 推出方向把粒子往下推穿 | 把"法线与运动反向"当成选面**主判据**；撞后粒子在向上弹，于是选到方块底面 | 主判据改「最小平移」，运动方向只做 tie-break | §3.8 |
| 尾迹要等很久才出现 | 淡入写成「寿命的 5%」，寿命 18000 时 = 45 秒 | 淡入改**固定 tick** | §9.7 |
| 粒子分布很散 | 随机游走**无界** + 低适宜度时间距被拉到 12 格 | 改匀速直线模型（距离 ÷ 寿命）+ 间距给下限 | §9.7 / §9.10 |
| 尾迹长到某处不再延长 | 撞到 `contrailMaxLive` 存活上限 | 属预期；调配置或降寿命 | §9.7 / §11 |
| 染色不同步 / tooltip 停在旧色 | `getUpdatePacket()` 默认返回 `null` | 三个同步方法都要 override | §10.5 |
| 物品 tooltip 颜色错乱 | Create 用 `_` 分段且**每行翻转**高亮状态 | 文案不写 `_`，全部单行 | §12.4 |
| 注册 tooltip 时崩（unbound value） | 在 mod 构造器里取 `DeferredHolder` | 移到 `FMLClientSetupEvent` | §12.1 |

### 13.8 下一步（按优先级）

0. ~~斜喷穿墙~~、~~排气轻微重力~~：**已完成并经用户实测确认**（§3.8 / §3.6.1 / §14）。
1. 进游戏确认 §13.5 那三项。
2. 定合成配方材料并补 `data/ccpe/recipe/`。
3. 回到 `memo/flip-autopilot.md`：用新喷管重标落地/倒挂控制律（悬停油门 43% 只在海平面成立，§2.2；
   滚转单边差动只剩 6.4 rad/s² 余量 ⇒ 建议双边反向差动）。
4. 可选美化：尾迹换专用白云贴图；或改带状 mesh 做「几分钟长」的真正尾迹（工作量大，§8.6）。

## 14. 本轮工作记录（2026-08 末：斜喷穿墙修复 + 排气轻微重力）

> 技术结论已分别落在 **§3.8**（穿墙根因与修法）、**§3.6.1**（重力）、**§11.2.1**（配置读取开销）。
> 本节记录**过程与验收状态**，方便回看"当时是怎么查的、为什么前两次修错了"。

### 14.1 起因

用户实测两条互相关联的异常：

1. 喷口**垂直于地面**时排气粒子表现正常（撞墙后沿面滑行），但**机体与平面成 30° 时全部粒子穿墙、漏到平面下方**，
   且"越与平面平行问题越严重"；漏下去的粒子**已经变大、变慢**（说明确实撞到过东西）。
2. 后来提出：希望排气**轻微受一点重力**。

### 14.2 排查过程（三轮，前两轮都修错了方向 —— 值得记住的不是结论而是排除路径）

| 轮次 | 假设 | 证据 | 结果 |
|---|---|---|---|
| 1 | 扫掠碰撞 `slab` 缺"起点已重叠"分支 ⇒ 粒子在壳层内收不到响应 | 埋点日志：120 条穿墙事件全部落在 30° 那 3 秒、90° 零条；HIT 的法线**全是 ±X/±Z**、没有 +Y；撞过的粒子占一半 | 假设成立但不是**触发点**（粒子怎么会进壳层没解释） |
| 2 | 补"起点已嵌入"分支 + 推出；选面用"法线与运动反向" | 用户实测：**所有角度全漏，比之前更糟** | 修错：撞后粒子在**向上**弹，"与运动反向的面"是方块**底面** ⇒ 推出深度约一整格 = 往下推穿 |
| 3 | 改成"最小平移 + 运动方向仅做 tie-break" | 用户实测：**仍然全漏** | 仍旧不对 ⇒ 说明还有别的位移来源 |

**转折点**：不再猜代码，改成核对日志里 `pos` 与上一 tick 的 `mv`。发现两者逐 tick 对不上，
差值恰好 = `quadSize/2` 且随寿命增长（例 #2460：`82.13 - 1.40` 应落 `80.73`，实际 `80.68`，差 0.05；
下一 tick 0.06 → 0.07 → 0.08，而 `half` 每 tick 涨 0.0085）。

顺带解释了两个"看不懂"的现象：

- 为什么第 1、2 轮加的"穿墙检测"报 0 条 —— 因为粒子上一 tick 就已经沉过表面，检测条件（起点在空气里）不成立；
- 为什么"撞过变大的粒子漏得更狠" —— `IMPACT_GROWTH` 让 `quadSize` 翻倍，隐形下坠速度跟着翻倍。

### 14.3 根因与修法（详见 §3.8）

`ExhaustParticle#move()` 建的是**以 y 为中心**的对称包围盒，随后调用 `Particle#setLocationFromBoundingbox()`；
而 1.21.1 里那个方法是 `this.y = aabb.minY`（原版把位置 `y` 当**包围盒底边**，见 `Particle#setPos`）。
⇒ **每 tick 位置被静默下移 `quadSize/2`，且这段位移不在扫掠的 `movement` 里**，等于一个不受碰撞约束的隐形下坠。

角度相关性因此完全说得通：90° 入射的反弹给粒子 ~0.3-0.45 格/tick 上抛，压得住下沉；
浅角度反弹的法向分量只有 ~0.05-0.09 格/tick，压不住 ⇒ 净位移朝下 ⇒ 沉穿平面。

**修法**：不再调 `setLocationFromBoundingbox()`，直接写 `this.x/y/z`；包围盒仅用于视锥剔除。
同时保留加固（现在才真正有意义）：`slab()` 的"起点已嵌入"分支 + `Hit.push` 推出 + 最小平移选面。

### 14.4 排气轻微重力（详见 §3.6.1）

- 新增配置 `exhaustGravity`（`ccpe-client.toml`，范围 0.0~0.1，**默认 0.02**）。
- 实现：`ExhaustParticle` 构造时读一次缓存进 `sinkAccel`，每 tick 与浮力合并成一条
  `yd += BUOYANCY + life×BUOYANCY_GROWTH - sinkAccel`。
- 用户逐档实测（0.002 / 0.005 / 0.02）后定稿 0.02：出生后净下沉 ≈0.55 格/tick（≈11 格/秒），
  中期 ≈1.95、末期 ≈1.0 格/秒。
- 附带结论：**不需要给配置读取加缓存层**，NeoForge 的 `ConfigValue#get()` 本身带缓存（§11.2.1）。

### 14.5 本轮改动的文件

| 文件 | 改动 |
|---|---|
| `client/particle/ExhaustCollision.java` | `Hit` 增 `push` 字段；`slab()` 增"起点已嵌入"分支；新增 `depenetrationNormal()`（最小平移 + 运动方向 tie-break）；`scan()` 在 fraction 打平时取更小 push；物理体路径的 push 乘 `bodyScale` |
| `client/particle/ExhaustParticle.java` | `move()` 改为直接写位置（**修掉 y 静默下坠**）；新增 `sinkAccel`（重力）并在 `tick()` 施加；调试埋点 `DEBUG_TRACE` 已改回默认 `false` |
| `block/ThrustNozzleBlockEntity.java` | 调试开关 `DEBUG_EXHAUST_LOG` 默认 `false`（代码保留，将来复查可直接打开） |
| `Config.java` | 新增 `EXHAUST_GRAVITY`（默认 0.02） |
| `assets/ccpe/lang/{zh_cn,en_us}.json` | 各增 2 条 `exhaustGravity` 的标题与 tooltip |

### 14.6 验收状态

- `gradlew classes` / `processResources` 通过；两个 lang 文件均为合法 JSON（各 296 键）。
- **用户实测确认**：任何角度都不再穿墙（判据不只是"看着不漏"，日志里 `pos` 与上一 tick `mv` 已完全吻合）；
  `exhaustGravity = 0.02` 的手感符合预期；改 toml 即时生效。

### 14.7 遗留

- 两个调试开关（`ExhaustParticle.DEBUG_TRACE`、`ThrustNozzleBlockEntity.DEBUG_EXHAUST_LOG`）现为 `false`，
  **代码与判读说明都保留在 §3.8 的"调试埋点"小节**，再遇到粒子位置异常可直接打开。
- 重力让撞地更频繁 ⇒ `IMPACT_GROWTH` 触发更多、粒子整体偏大；若后续觉得太大，调 `IMPACT_GROWTH`（现 2.0）。
- 排气粒子仍是纯客户端计算，撞墙点/散射方向各客户端独立（未做服务端广播）。

## 15. 扳手快速拆除（2026-08 新增）

> 需求：给喷管加「`create:wrench` 潜行右键快速拆除」。**只改了 1 个文件**
> （`block/ThrustNozzleBlock.java`），无资源/数据/配置改动，`gradlew classes` 通过。

### 15.1 做法（就三行有效代码）

```java
public class ThrustNozzleBlock extends DirectionalBlock implements EntityBlock, IWrenchable {
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;   // 普通右键不旋转
    }
}
```

- **潜行右键**走 `IWrenchable#onSneakWrenched` **默认实现**（不覆写）。Create 的
  `WrenchItem#useOn`（`api/create-.../equipment/wrench/WrenchItem.java:45-63`）判定链：
  `player == null || !player.mayBuild()` ⇒ 交回原版；`block instanceof IWrenchable` 且
  `player.isShiftKeyDown()` ⇒ `onSneakWrenched`；否则 `onWrenched`。
- 默认 `onSneakWrenched`（`IWrenchable.java:48-73`）依次做：
  1. 非 `ServerLevel` ⇒ 直接返回 `SUCCESS`（客户端只吃这次交互，不做任何事）；
  2. post `BlockEvent.BreakEvent`（**能被领地/保护插件拦下**，且会走原版「能否破坏」判定链）；
  3. 非创造模式：`Block.getDrops(state, serverLevel, pos, be, player, tool)` 每个掉落
     `player.getInventory().placeItemBackInInventory(...)` ⇒ **直接进背包，不用满地捡**，这就是"快速"；
  4. `state.spawnAfterBreak(...)` + `world.destroyBlock(pos, false)` + `AllSoundEvents.WRENCH_REMOVE` 音效。
- **掉落表已存在**（`data/ccpe/loot_table/blocks/thrust_nozzle.json`），所以 `getDrops` 返回 1 个喷管，行为正确。
- **物理体里也有效**：喷管所在 plot 的 `getLevel()` 就是真正的 `ServerLevel`（memo §3.5），
  所以 `level instanceof ServerLevel` 成立。而且掉落走 `placeItemBackInInventory`（不生成物品实体），
  绕开了"在 plot 里掉物品"的坐标问题。
- **和染料右键不冲突**：手持扳手时 `WrenchItem` 直接 override 了 `Item#useOn`，根本不会走到方块的
  `useItemOn`；染料判定只在 `stack.getItem() instanceof DyeItem` 时生效。

### 15.2 为什么普通右键显式返回 PASS（不旋转）

`IWrenchable#getRotatedBlockState` 认的是 `DirectionalKineticBlock.FACING`，它和本方块用的
`DirectionalBlock.FACING` 是**同一个 `BlockStateProperties.FACING` 实例**（`DirectionalKineticBlock.java:16`），
所以默认旋转其实是"能转"的 —— 但喷管的 `FACING` 语义是「背后那格必须是朝向为 `FACING` 的电扇」
（`canSurvive`），转 90° 后几乎必然不合法 ⇒ 默认实现会先旋转、`canSurvive` 失败再返回 `PASS`
（`IWrenchable.java:31-34`），等于"什么都没发生"。

即便如此仍显式返回 `PASS`，原因有两个：

1. **保证手感一致**：若某天玩家凑出"侧边也有个朝向合适的电扇"的几何，默认实现会真的把喷管转过去，
   变成一个意料之外的朝向；
2. **不动方块状态就不需要额外校验**：`onWrenched` 默认实现会走 `KineticBlockEntity.switchToBlockState`
   （会改 state + 触发同步），对本方块没有意义。

### 15.3 进游戏验证清单

1. 手持 `create:wrench` **潜行右键**喷管 ⇒ 喷管消失、`WRENCH_REMOVE` 音效、**物品直接出现在背包**（地面无掉落物）。
2. 同上，但**背包满**时 ⇒ 掉落到地面（`placeItemBackInInventory` 满了会自然掉落，原版行为）。
3. **普通右键**（不潜行）扳手 ⇒ **没有反应**（不旋转、不出音效、不掉落）。
4. 装在**物理体（Sable 子次元）**里的喷管 ⇒ 潜行右键扳手同样能拆、同样进背包。
5. 创造模式潜行右键 ⇒ 直接消失、**不进背包**（默认实现只在非创造时发掉落，与 Create 其余方块一致）。
6. 拆掉后立刻看背后电扇 ⇒ 出力归零 / 恢复风扇自身推力（§1.4 的 `blocked` 判定解除）。

## 16. 进气粒子 `ccpe:intake`（吸力 = 负转速，2026-08 末新增）

> 需求原话：「喷嘴在产生吸力的时候也有粒子特效」。**方案已由用户三选一定稿**（见 §16.1）。
> 本轮改了 1 个新建粒子类型 + 1 个新建粒子类 + 1 个新建 record + BE 的一条分支，
> `gradlew classes` 通过；**待进游戏确认观感**。

### 16.1 用户定稿（开工前问过）

| 议题 | 结论 |
|---|---|
| 吸力粒子的形态 | **A. 进气式**：粒子在喷口**前方**几格空中出现 → 向口面收拢 → 在口面消失（像吸尘器/涡扇进气） |
| 实现路线 | **新建专用粒子 `ccpe:intake`**，不复用 `ExhaustParticle`（那条链路的每条曲线都是"往外炸"的） |
| 负转速时的尾迹 | **不出**（尾迹是"排气水汽在高空结的冰晶"，吸力时没有排气；而且旧行为会在机身内部 3 格外出生） |

### 16.2 根因：负转速下**一颗粒子都没有**（不是"不好看"）

`tickClient` 里旧逻辑只有一条链路：

```
flowSign  = (REVERSE_SIGN ? -1 : 1) × sign(转速)      // 负转速 ⇒ -1
flowDir   = FACING × flowSign                          // 负转速 ⇒ 指向喷管背后（电扇侧）
mouth     = center + FACING × 0.55                     // 起点仍在【前方】0.55 格
clear     = clearDistance(mouth → flowDir)             // 沿 -FACING 打射线
```

射线从 `mouth`（口面外 0.55 格）朝 `-FACING` 打，**第一步就打穿喷管自己**：本方块碰撞箱最远到方块边界的
`15/16 = 0.9375`，与 `mouth` 只差 `0.1125` 格，减掉 `clearDistance` 里 `-0.03` 的余量后
`clear ≈ 0.08 < MIN_PARTICLE_TRAVEL(0.2)` ⇒ **直接 return，零粒子**。

所以「吸力时没有特效」是"缺一条链路"，不是参数问题。顺带结论：**旧的 `flowSign` 反向逻辑在负转速下
从来没生效过**（它生成不了任何粒子），现在的排气链路只在正转速下有意义。

### 16.3 实现

**分支（`ThrustNozzleBlockEntity#tickClient`）**：把 `flowSign` 拆成两个概念 ——
`thrustSign`（物理：`受力 = -FACING × thrustSign`，>0 = 喷出推、<0 = 吸向喷口）与 `flowSign`
（`thrustSign` 再叠 `INVERT_PARTICLE_FLOW`，只影响排气视觉）。然后：

```java
if (thrustSign < 0) {                    // 吸力
    contrailAnchorWorld = null;          // 尾迹锚点清空（用户定稿：吸力时不出尾迹）
    contrailAccum = 0;
    tickIntake(level, own, mouth, axis, throttle);
    return;                              // 不出排气
}
tickContrail(...); ...spawnExhaust(...); // 正转速：原样
```

**发射（`tickIntake`）**

```
锥形区域：沿 +FACING 方向、距离 ∈ [min(0.7, 0.5·Dmax), Dmax]
           Dmax = min(reach, clear)，reach = max(1.0, intakeFunnelLength × 油门)   // §16.9：随转速缩放
           截面半径 = min(2.5, 0.45 × 距离) × sqrt(random)   // 越远越宽 ⇒ 漏斗形
寿命     = clamp(距离 / (intakeSpeed × (0.6 + 0.4×油门)), 6, 40)
位移     = 口面中心 − 出生点      // 走 vx/vy/vz 传（同 Create AirParticleData 的约定）
```

- **遮挡**：从口面沿 `+FACING` 打一条射线（`clearDistance(level, null, mouthWorld, dir, reach+0.1)`，
  `own = null` ⇒ `projectToWorld` 退化为恒等）。前方被堵死（`clear < MIN_PARTICLE_TRAVEL`）就不生成 ——
  "没有可吸的空气"。语义与排气一致：只挡实体碰撞形状。
- **世界系**：口面与轴先 `projectToWorld` 投到世界系，正交基也在世界系里建，所以粒子不会被机体拖着走（§3.5 的老坑）。
- **必须用 9 参数** `addAlwaysVisibleParticle(..., ignoreRange = true)`（§3.7 的老坑，别用 7 参数那个）。
- 密度/速度都随**油门**（`转速/256`）缩放：转速越高，吸得越密、越快；**漏斗长度也随油门缩放**（§16.9）。

**粒子（`client.particle.IntakeParticle`）**：`SimpleAnimatedParticle` 子类，<b>不积分速度</b>，
直接按参数插值（所以不会像排气那样有"每 tick 悄悄挪一点"的隐患，见 §14 的教训）：

```
位置(t) = 出生点 + 位移 × t²                        // t = age/lifetime；t=1 时精确落在口面中心
quadSize = baseScale × (1.0 → 0.30)                // 越靠近口面越小
alpha    = 0.55 × 淡入(固定 2 tick) × (1−t)^0.7     // 到口面时归零，不留硬切白点
```

- 参数来自 `IntakeParticleData(scale, lifetime, r, g, b)`；`r/g/b` 与排气同一族冷白（0.86/0.90/0.96），
  **方向靠运动表达，不靠颜色**。
- `hasPhysics = false`、`gravity = 0`、`friction = 1`：不碰撞（空气被卷进来不该被车身弹开）。
- `getLightColor` 直接返回 `0xF000F0`（全亮）：进气粒子常在车底/阴影里，吃 lightmap 会变成一团深灰。
- 精灵图出生时随机定死一张（同 `ContrailParticle`），不逐帧播放。

### 16.4 新增配置（6 项，`ccpe-client.toml`）

| 配置键 | 默认 | 作用 |
|---|---|---|
| `intakeEnabled` | true | 进气粒子总开关（关 = 负转速完全没有粒子） |
| `intakeParticlesPerTick` | 8 | **性能主旋钮**：满 256 RPM 时每 tick 颗数，按 `转速/256` 缩放 |
| `intakeFunnelLength` | **8.0 格** | 进气漏斗长度，**满 256 RPM 时的值**，实际按 `转速/256` 线性缩放（下限 1.0 格，`INTAKE_MIN_REACH`） |
| `intakeSpeed` | 0.5 格/tick | 进气速度：**寿命 = 距离 ÷ 本值**，调大 = 吸得更快更短命 |
| `intakeScale` | 0.28 格 | 出生尺寸（寿命内缩到 0.3 倍） |
| `intakeMaxDistance` | 64 格 | 发射距离上限（0 = 不限） |

代码常量（曲线定义，留在 `ThrustNozzleBlockEntity` 顶部）：`INTAKE_CONE_RATIO 0.45`、
`INTAKE_MAX_CONE_RADIUS 2.5`、`INTAKE_MIN_DISTANCE 0.7`、`INTAKE_MIN_REACH 1.0`（漏斗长度缩放下限）、
`INTAKE_MIN/MAX_LIFETIME 6/40`、`INTAKE_RED/GREEN/BLUE`。粒子内视觉常量：`ALPHA_MAX 0.55`、
`SIZE_START 1.0` / `SIZE_END 0.30`、`SIZE_FALLOFF 0.6`、`ACCEL 2.0`、`FADE_IN_TICKS 2`。

### 16.5 本轮改动的文件

| 文件 | 改动 |
|---|---|
| `particle/IntakeParticleData.java` | **新建** record `(scale, lifetime, r, g, b)` + CODEC/STREAM_CODEC |
| `particle/MyModParticles.java` | 注册 `ccpe:intake`（`overrideLimiter = false`，靠调用点传 `ignoreRange`） |
| `client/particle/IntakeParticle.java` | **新建**：`t²` 参数插值收拢 + 尺寸缩小 + 淡入淡出 + 全亮 |
| `CCPeripheralExtenderClient.java` | `registerSpriteSet(MyModParticles.INTAKE, IntakeParticle.Provider::new)` |
| `assets/ccpe/particles/intake.json` | 精灵图 `minecraft:generic_0..7` |
| `block/ThrustNozzleBlockEntity.java` | `tickClient` 拆出 `thrustSign` 并按符号分支；新增 `tickIntake()` 与 6 个配置访问器 + 6 个常量 |
| `Config.java` | CLIENT 段新增 6 项 `intake*` |
| `assets/ccpe/lang/{zh_cn,en_us}.json` | 各 +12 条（6 项 × 标题/tooltip），两文件均 308 键 |

### 16.6 性能

- 同屏粒子数 ≈ `intakeParticlesPerTick × 油门 × 平均寿命`。默认值下大概 `8 × 10 ≈ 80 颗/管`
  （排气约 160 颗/管），且**零碰撞扫掠**（不像排气每颗每 tick 要扫 8~27 个方块）⇒ 比排气链路更轻。
- 每 tick 固定成本：每管 **1 条射线**（遮挡）+ 每颗一次 `addAlwaysVisibleParticle`。

### 16.7 进游戏验证清单（待用户回报）

1. 把风扇**反转**（负转速），机体应被吸向喷口朝向；同时喷口**前方**出现向内收拢的粒子，
   靠近口面时**变小、变淡、消失**（不是从机身里冒出来）。
2. 正转速 ⇒ 仍是原来的排气 + 尾迹，**没有任何变化**（进气链路不参与）。
3. 高空（Y≥160）用负转速 ⇒ **没有尾迹**（用户定稿）；切回正转速 ⇒ 尾迹恢复。
4. 用方块/墙把喷口**前方**堵死 ⇒ 进气粒子消失（射线挡住 = 没有可吸的空气）。
5. 在车底/阴影/岩浆旁看 ⇒ 粒子是冷白，不该被光照染黄（全亮渲染）。
6. 远离 40~60 格回望 ⇒ 进气粒子仍可见（9 参数 API 绕过 32 格限制）。
7. 调 `config/ccpe-client.toml` 的 `intakeFunnelLength` / `intakeSpeed` / `intakeParticlesPerTick` ⇒ 即时生效
   （新生成的粒子立刻按新值走）。
8. **漏斗长度随转速**（§16.9）：满 256 RPM ⇒ 吸得最远（约 8 格）；把风扇降到 ~128 RPM ⇒ 长度减半（约 4 格）、
   粒子也更稀；极低速 ⇒ 至少还有 1 格的短吸气。

**待用户回报的三个手感问题**：①漏斗长度/宽度是否合适（`intakeFunnelLength` / `INTAKE_CONE_RATIO`）；
②收拢速度（`intakeSpeed`）与粒子大小（`intakeScale`）是否合适；③要不要给进气粒子**单独换个颜色**
（现在与排气同为冷白，靠运动区分）。

### 16.8 遗留 / 未做

- 进气粒子与排气一样是**纯客户端**计算（各客户端独立，未做服务端广播）。
- **不碰撞**：粒子会直接穿过挡在侧面的方块（只挡"正前方射线"那一条）。要严格对齐"空气流动"
  得给进气粒子加扫掠，但它本来就该被吸进管道里，加碰撞反而更容易露馅。
- 不做螺旋/涡旋（现在只是直线收拢 + 锥形分布）。想加涡旋得让粒子知道口面位置（现在只传位移向量），
  即多传一个 `offset` 字段或改用"起点 + 轴 + 距离"的表达。
- 染色（§10）**不影响**进气粒子，也不影响排气 —— 只染尾迹。

### 16.9 v2：漏斗长度随转速缩放（2026-08 末，用户要求）

> 需求原话：「funnel length 与转速有关，256 转速设为 8.0」。用户已确认 v1 观感符合预期，本轮只动长度。

```
reach = max(INTAKE_MIN_REACH(1.0), intakeFunnelLength × min(转速/256, 1))
```

- **配置键改了名**：`intakeReach` → **`intakeFunnelLength`**，语义从"固定的漏斗长度"变成
  "**满转速（256 RPM）时**的漏斗长度"，默认 **3.0 → 8.0**。
  ⚠️ 改名不只是为了对齐用户用词：老玩家的 `ccpe-client.toml` 里已经写死了 `intakeReach = 3.0`，
  只改默认值对他们**不生效**（NeoForge 不会覆盖已存在的键）；换个键名才能让 8.0 立刻生效
  （旧的 `intakeReach` 行留在文件里也无害，属于未知键）。
- **下限 1.0 格**（`INTAKE_MIN_REACH`）：纯比例缩放在低速时会缩到几乎为零（8 RPM ⇒ 0.25 格），
  粒子贴着口面冒出来；给个下限让"低速但仍在吸"也有个形态。不想要下限就设成 0。
- **顺带抬了锥的宽度上限**：`INTAKE_MAX_CONE_RADIUS` 1.6 → **2.5**。八格长的漏斗如果照旧只有 1.6 格半径，
  半角只有 11°，观感像一根细吸管；抬到 2.5 后约 17°。这个上限只在**距离 > 5.5 格**时才起作用
  （`0.45 × 距离`），所以短漏斗的观感不变。嫌太胖就调回去。
- **一个顺带的好性质**：粒子数 = 密度 × 寿命，而寿命 = 距离 ÷ 速度 —— 长度与寿命同比缩放，
  所以「每单位长度的粒子密度」与转速无关，漏斗变长**不会变稀**（无需同步调 `intakeParticlesPerTick`）。
- 实测口径（`intakeFunnelLength = 8.0`、`intakeSpeed = 0.5`、`intakeParticlesPerTick = 8`）：

| 转速 | 油门 | 漏斗长度 | 进气速度 | 寿命（最远粒子） | 单管同屏（约） |
|---|---|---|---|---|---|
| 256 | 1.00 | **8.0 格** | 0.50 格/tick | 16 tick | 128 颗 |
| 192 | 0.75 | 6.0 | 0.45 | 13 | 78 |
| 128 | 0.50 | 4.0 | 0.40 | 10 | 40 |
| 64 | 0.25 | 2.0 | 0.35 | 6（夹取） | 12 |
| 20 | 0.08 | 1.0（下限；不设下限则只有 0.63 格） | 0.32 | 6（夹取） | 6 |
| 8 | 0.03 | — | — | — | **0**（`round(8×0.03) = 0`，密度先归零） |

注意最后一行的口径：密度 `round(intakeParticlesPerTick × 油门)` 会先归零，所以极低速（< 16 RPM）是
**完全没有粒子**，漏斗长度下限只对 16~32 RPM 这段有意义（那里每 tick 才有 1 颗）。

## 17. 创造物品栏（2026-08 末补，一直漏做的一步）

**为什么之前没出现**：本模组的创造标签页是**显式列表**，不是"自动收集本模组所有物品"：

```java
// item/MyModCreativeModeTabs.java
MY_MOD_TAB.register("my_mod_tab", () -> CreativeModeTab.builder()
        .displayItems((parameters, output) -> {
            output.accept(MyModBlocks.static_port);
            ...                       // ← 每个物品都要手动 accept 一行
        })
```

而 `MyModBlocks.registerBlocks(name, block)` 只做两件事：`BLOCKS.register(...)` + `MyModItems.registerBlockItems(...)`
（= 注册 BlockItem）。**它不碰创造标签页** ⇒ 漏写一行就等于"物品存在、能用 `/give` 拿到、但物品栏里看不到"。
（§2 早期写成"自动带创造栏"，是错的，已改。）

**修法**：一行。

```java
output.accept(MyModBlocks.thrust_nozzle); // 定向喷管
```

位置：**"其他"分区末尾**（`trailing_wheel_mount` 之后）。放在"其他"而不是"模块化发动机"，是因为
它不属于 `engine_core` 那套冷却/燃烧模块的组合，而是像从动轮悬架一样"装在机体上的物理部件"。
想挪到别处只要把这一行剪到对应分区即可（标签页按 accept 顺序、按分区注释排布）。

> 顺带提醒：`RegistrateBlocks` 那套（航行灯）默认会自动挂到某个标签页，本模组为了避开
> "`AbstractRegistrate` 往搜索标签重复 accept 导致服务器启动崩溃"的坑，把 `defaultCreativeTab` 置成了 `null`
> ⇒ **航行灯也是手动 accept 的**（`MyModCreativeModeTabs.java:32-34`）。新加方块一律记得补上这一行。











