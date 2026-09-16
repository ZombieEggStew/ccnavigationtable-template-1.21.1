# 🚨 ATTENTION — 踩坑与注意事项

> 只记录踩过的坑与要注意的地方（不记实施总结）。改相关代码前先核对对应小节。
> 各功能的实施总结见 `memo/WORK RECORD.md`。

## 通用

- **Sable 子次元坐标不一致**：`blockEntity.getBlockPos()` 是 plot（局部）坐标，玩家视线是 world 坐标，必须用 `SableCompat.toLocalPosition/toLocalDirection`（即 `Pose3dc.transformPositionInverse/transformNormalInverse`）把射线投回 plot 再求交，否则悬停高亮错位。Sable 的选择框描边已由 Sable 自己的 mixin 处理，不用额外适配。
- **渲染与检测必须严格互逆**：正向（PoseStack）与逆向（射线求交）的变换顺序、枢轴常量必须一致；集中在 `PitchMonitorTransform` 单一来源，避免各写各的导致镜像/错位。
- **旋转顺序不可交换**：三维旋转不满足交换律，facing/yaw/pitch 顺序不能乱；PoseStack 后调用的 `mulPose` 是内层（先作用于顶点）。
- **单位约定**：枢轴常量存像素（1/16 块），PoseStack 里用 `/16f` 转块；`createPitchedCaseShape` 直接用像素算。
- **万向锁**：不处理，模组介绍中提示玩家避免 pitch=±90° 相关表现。

## Monitor / GridState

- 测试 monitor 的 `getShape` 不要每帧动态重算（`VoxelShaper.forHorizontal` + 12 切片 `Shapes.or` 很贵，会造成视角对准时掉帧）；已改为按 (facing,pitch) 懒缓存，碰撞 `getCollisionShape` 只用底座。
- `GridState.trySetId` 改 ID 时必须同步 re-key：`modules`、`grid[][]`、`pressedModules`、`knobAngles`、`moduleConfigs`（新增字段时别漏）。
- section 实例由 `ModuleConfigSections` 工厂**每次新建**，不要做成单例（内部持有控件引用）。
- 屏幕(`name == "screen"`)和未注册类型都走 `ModuleConfigSection.Empty`，不进注册表。
- `MonitorBlockEntity` 必须重写 `getUpdatePacket()`（默认返回 null），否则 `sendBlockUpdated` 不会推 BE 数据到客户端。
- 扳手右键模块：交互检测需排除 `holdingWrench`，否则拆卸前会先触发一次按下。
- 模块 ID 命名空间已包含屏幕，`GridState.getOccupiedIds()` 返回二者并集，菜单滚轮跳过时要用它。
- tooltip 显示开关存于模块配置的 `showTooltip`；旧存档无此字段时按开启处理。

## 屏幕渲染

- 旧存档屏幕文本（自由定位格式，无 "cols" 字段）加载时清空重置（**破坏性变更**，已确认接受）。
- **已删除**：`setFillPadding`（LED 内缩，用户要求移除）；monitor 背景平面绘制通道（`MonitorBlockEntity.monitorDisplayText`、`MonitorPeripheral` 背景平面 API）——内容只能在 screen 模块上绘制。
- `fill` 背景格曾短暂移除后又按用户要求恢复。
- 测试 monitor 的 `my_monitor_case.json` 没有 `"render_type": "minecraft:cutout"`（静态 monitor 的 `my_monitor.json` 有），case 前脸会不透明、挡住 z=5 的 screen 面板；棋盘画在 z=3.99 前面不受影响。若要 screen 面板透出，需给 case 模型加 cutout。

## Monitor 变换（facing + offset + yaw + pitch）

- **变换顺序**（PoseStack 后调为内层、先作用于顶点）：`facing(方块中心,Y) → offset(前后平移,Z) → yaw(颈部,Y) → pitch(铰链,X)`；facing 作用于整体；offset/yaw 作用于 bearing+case；pitch 只作用于 case。
- **枢轴静态常量（定义在 `PitchMonitorTestBlock`，单位=模型像素，微调只改这里）**：`HINGE_Y=9`、`HINGE_Z=8`（俯仰铰链，case 侧轴承中心）；`NECK_X=8`、`NECK_Z=8`（偏航颈部，bearing 水平中心）；铰链与颈部同 z=8，构成单点万向（gimbal），yaw 时铰链不画弧。
- **offset 语义**：正值向前（朝屏幕 -Z），相对 facing 前后平移 case+bearing；单位 1/16 方块（1px）。
- **yaw 语义**：相对 facing（yaw=0 即当前朝向）。
- **命中检测**：世界/plot → 模型空间逆序 `facing逆 → offset逆 → yaw逆 → pitch逆`（正向的逆序、旋转取负、平移取反）。

## 引擎（P7）

- **气道门控修订（P7 → 后续再修订）**：经济系数/油耗/过冷**不以气道为门控**（无气道同样生效；过冷惩罚、经济区无气道可达）；Goggle 油耗行不显示「未装气道」。**现行门控**：冷却气道（`cooling_air_duct`，原整合气道改名）= 纯散热模块，风门值（`setCooling`）**无门控**、只在装风道时被 K_DUCT 分量消费；`setMixture` **无门控**（纯存值，仅流体引擎消费；非流体引擎存了不生效）。
- **燃料体系分家**：蒸汽引擎 = 纯原版燃料解析（`EngineFuels.vanillaBucketBurnTicks`：桶物品原版熔炉燃烧时长，熔岩桶 20000tick；添加蒸汽燃料 = 按原版为熔炉加燃料）；流体燃烧室 = 纯 datapack（`engine_fuel/*.json` 唯一途径）；**已删除 `burn_ticks_per_bucket` 字段**（lava.json 同步清理），消耗速率仍随油门（定距桨）。
- **自动富油只降温不进油耗**（m_eff = 杆×autoRichness 只进 heatFactor 与 eco 窗口）。高空不拉稀 = ×1.0 无惩罚（原 ×1.25 效率税移除）。
- **温度阈值全部引擎固定**：经济目标 `ENGINE_T_OPT=155`、过冷 `ENGINE_MIN_WORK_TEMP=100`、过热 200——均不随燃料/油门（真实 = 引擎设计点/节温器恒定；油门只决定实际温度与冷却压力）。
- **Goggle tooltip 改每 tick 同步（20Hz）**：P7 新增字段持续变化，差量发包下显示滞后；`reActivateSource` 仍只在运行态/容量变化时置位（不每 tick 触发网络重激活）。**P7+ 已优化（方案 A+B+D）**：事件差量 + 温度 ≥1°C 门控 + 慢字段 1Hz 心跳；经济/过冷/油耗系数客户端现算（`economyFactor()/coldFactor()/fuelFactor()`），蒸汽倒计时客户端线性外推（`steamBurnTicksDisplay()`，运行中才外推）；`setChanged` 仍每 tick 保温度持久化。
- **过热滞回解锁 T≤180°C**（原 160）。

## 惯性导航系统（ccpe:ins）

- **randomNudge 坑**：不能 `eulerAngles.z = random×2π`（瞬移大角度）也不能归零（指北大角度瞬间跳回）；只保留 z 角 + 给 ±0.15 rad/tick 随机角速度。
- **部件层级（外→内）：test(Y 偏航指北) → gimbal(Z 滚转) → compass(X 俯仰)**；动画三处逆变换（重力/指北/外壳角速度）必须与渲染层级一致。
- 转动部件 `PIVOT_DROP` 3.5px 下移（**Visual 与 Renderer 两处**）；模型以方块中心 (8,8,8) 为原点。

## FMC 应力网络

- **Create 6.x 的 `KineticBlockEntity.stress/capacity` 无公开 getter**（protected，仅子类可见）→ 新建 `compat/create/CreateStressReadout.java` 反射读这两个稳定缓存字段；`FmcBlock` 抽出公共静态 `supportDirectionOf(BlockState)`。
- 门控 = 机体（含约束链）上有 ≥1 FMC 且附着面方块是 Create 动力方块（KineticBlockEntity）；附着面 = FMC 的 FACE/FACING 支撑方向 / AIC 的 FACING 背面。
