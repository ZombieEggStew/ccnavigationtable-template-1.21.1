# 📝 WORK RECORD — 工作记录 / 实施总结

> 各功能的实施总结与设计要点。踩坑与注意事项见 `memo/ATTENTION.md`。

## 引擎 P7 奖励驱动重构（2026-08 实施）

- **油耗 = 杆值 × 经济系数 × 过冷惩罚**；自动富油只降温不进油耗（m_eff = 杆×autoRichness 只进 heatFactor 与 eco 窗口）。高空不拉稀 = ×1.0 无惩罚（原 ×1.25 效率税移除）。
- **经济系数 = 双因素 AND 门控 + 时间解锁进度**：|T−155|≤10 ∧ |m_eff−1|≤0.05 持续达标 → 15s 缓慢解锁 ×0.75（`ECO_MIN=0.75`，省 25%）；离开窗口 6s 流失；混合比不对无奖励无惩罚。
- **温度阈值全部引擎固定**：经济目标 `ENGINE_T_OPT=155`、过冷 `ENGINE_MIN_WORK_TEMP=100`、过热 200——均不随燃料/油门（真实 = 引擎设计点/节温器恒定，如塞斯纳 172 CHT 工作带固定；油门只决定实际温度与冷却压力）。
- **过冷惩罚**：cold = 1 + 0.3×max(0, 100−T)/100（引擎固定阈值 100°C，20°C ≈×1.24，只乘油耗）——小油门暖机玩法。
- **Goggle tooltip 改每 tick 同步（20Hz）**：P7 新增字段（经济解锁进度/过冷/最佳温度）持续变化，差量发包下显示滞后；`reActivateSource` 仍只在运行态/容量变化时置位（不每 tick 触发网络重激活）。**P7+ 优化（方案 A+B+D）**：改回差量门控 + 1Hz 心跳（见 `engine-module.md` §16.2），稳态包量 1/20 以下。
- **Goggle 行微调**：状态行新增「过冷」态（BLUE，cold>1.01，仅流体）；经济行改为显示**最终油耗系数**（杆值×经济×过冷，服务端同步 `FuelFactor`）；**移除「最佳温度」行**（引擎固定 155 已不需要提示）——解锁进度/混合比/过冷对油耗的影响一目了然。
- **状态档位（流体引擎）**：过冷(<~97) / 正常(97~145) / **高效(145~165 经济带，AQUA)** / 正常(165~180) / 即将过热(180~200) / 过热(≥200)；**过热滞回解锁 T≤180°C**（原 160）。
- **Goggle 行再精简（流体全量）**：效率行改「油门」（=效率/定距桨单杆）；**移除混合比/高空实际行**；**新增「发热系数 ×」行**（服务端同步 `HeatFactor` = heatFactor(杆×自动富油)，稀>1 热 / 富油<1 凉）——油耗与发热两个最终系数并排，混合比/自动富油影响一目了然。
- **气道门控修订（P7）**：经济系数/油耗/过冷**不再以整合气道为门控**（无气道同样生效；过冷惩罚、经济区无气道可达）；Goggle 油耗行不再显示「未装整合气道」；**整合气道只门控 `setMixture`（混合比拉稀权）与 `setCooling`（冷却效率/风门）** Lua 方法。
- **燃料体系分家（P7）**：**蒸汽引擎 = 纯原版燃料解析**（`EngineFuels.vanillaBucketBurnTicks`：桶物品原版熔炉燃烧时长，熔岩桶 20000tick；添加蒸汽燃料 = 按原版为熔炉加燃料）；**流体燃烧室 = 纯 datapack**（`engine_fuel/*.json` 唯一途径）；已删除 `burn_ticks_per_bucket` 字段（lava.json 同步清理），消耗速率仍随油门（定距桨）。
- **后续计划**：① tooltip 差量/批量同步优化（温度 ≥SYNC_TEMP_DELTA 才发包 + 趋势外推，恢复 1/20 带宽）；② 过稀失火（m_eff<0.8 概率掉出力 / <0.6 熄火）；③ 进游戏调参（ECO_MIN/δ/COLD_K/ENGINE_T_OPT/ENGINE_MIN_WORK_TEMP/解锁流失速率）。
- 详见 `memo/engine-module.md`（最终版：核心玩法节 2、混合比/经济节 7.6、Lua 节 10、Goggle 节 11、数据文件节 13、玩家操作节 14）。

## 惯性导航系统（my_aero_sensor，INS，2026-08 实施）

- 新方块 `ccpe:ins`「惯性导航系统」：物理体姿态指示器（滚转/俯仰/偏航指北），视觉照抄 `simulated:gimbal_sensor` 的重力摆动画，进游戏验证通过。
- **部件层级（外→内）：test(Y 偏航指北) → gimbal(Z 滚转) → compass(X 俯仰)**，四元数 `Y / Y·Z / Y·Z·X` 各自独立实例——比 simulated（needle(Y) 最内）更进一步；动画三处逆变换（重力/指北/外壳角速度）必须与渲染层级一致。
- 简化：无红石、无 blockstate 旋转（base 恒单位）、限位固定 90°、无滚轮/护目镜 tooltip；空手右键/扳手触发 `randomNudge()` 扰动。
- 转动部件 `PIVOT_DROP` 3.5px 下移（Visual 与 Renderer 两处）；模型以方块中心 (8,8,8) 为原点。
- 服务端逻辑：INS 注册进 `BodySensorRegistry`（ATTITUDE 传感器，onLoad/setRemoved/每 20 tick 复核 UUID），`ccpe.sensor_system.getAngles()` 已实现——返回 {pitch, roll, yaw}（度：pitch 正=抬头 / roll 正=右翼下压 / yaw 0=局部 −Z 指北、正=右转 −180..180），**门控 = 机体（含约束链）上有 ≥1 INS**；姿态由 SensorSystemAPI 每 tick 直接算自机体姿态（gimbal_sensor 重力投影 + 世界北水平方位），BE 的 `XAngle/ZAngle` 兼容保留。`getPosition()` 已实现——最后放置的 INS 的世界坐标 {x, y, z}（`SableCompat.projectOutOfSubLevel` 投影，门控同 getAngles）。`getOrientation()`/`getAngularVelocity()` 已实现——机体四元数 {x,y,z,w} 与机体局部系角速率 {x,y,z} rad/s（绕机体自身 X/Y/Z 轴；世界系刚体角速度经同一 tick 姿态四元数逆旋转得到，姿态恒等时=世界系；门控同 getAngles）。`getBodyPosition()` 已实现——物理体原点世界坐标（`SableCompat.getSubLevelWorldPos`，门控同 getAngles）。另新增不门控物理数据（只要在物理体上就有值）：`getPhysicsCenterOfMassRel()`（重心相对电脑，机体局部系 plot 帧差值）、`getPhysicsMass()`/`getPhysicsChainMass()`（kg）、`getPhysicsGravityForce()`/`getPhysicsChainGravityForce()`（pN = 质量×11）。**待进游戏验证**。详见 `memo/my_aero_sensor.md`。

## FMC 附着方块应力网络（getStressRemaining / getStressCapacity，2026-08 实施）

- `ccpe.sensor_system.getStressRemaining()` 已实现——最后放置的 FMC（含 AIC）的附着面方块所在 Create 应力网络的**剩余应力**（su = 总容量 − 当前总应力，过载时为负）；`getStressCapacity()` 已实现——网络**总容量**（su）。两方法门控相同，不可读时返回 nil。
- **门控 = 机体（含约束链）上有 ≥1 FMC 且附着面方块是 Create 动力方块（KineticBlockEntity）**；附着面 = FMC 的 FACE/FACING 支撑方向 / AIC 的 FACING 背面。
- 读数：Create 6.x 的 `KineticBlockEntity.stress/capacity` 无公开 getter（protected，仅子类可见）→ 新建 `compat/create/CreateStressReadout.java` 反射读这两个稳定缓存字段；`FmcBlock` 抽出公共静态 `supportDirectionOf(BlockState)`。**待进游戏验证**（FMC 贴在动力方块上读剩余应力；已实测原表形式输出正常，改名后待复测）。

## 屏幕渲染重构（方案三：格子模型，2026-08-22 实施）

- 文本层改为定长格子数组（char[] + 前景/背景色，LCD 帧缓冲语义），体积固定不再增长；`write` 从光标处逐格覆盖，背景色不被覆盖（fill 与 write 可叠加「色块 + 文字」）。
- Lua API：新增 `setGrid/getGrid`、`fill`、`fillField(col,row,width,count,colour,align?)`（定宽填充，区域内其余清透明）、`draw(batch)`（cells+shapes 两段式，原子替换）、`drawCells(batch)`/`drawShapes(batch)`（单层替换，另一层保持不变）、`writeField(col,row,width,text,align?)`（定宽字段，区域内未写部分自动清空、背景保留）；`setCursorPos` 改格子坐标（1 起）；`write` 去掉 z 参数；`setTextScale` 保留为 setGrid 别名（可传可选高宽比参数）。
- 移除 monitor 背景平面绘制通道：`MonitorBlockEntity.monitorDisplayText`、`MonitorPeripheral` 背景平面 API 全部删除，内容只能在 screen 模块上绘制。
- 渲染：`ScreenTextRenderer` 每格背景 quad（fill 填充）+ 字形 quad（`RenderType.textPolygonOffset`，polygonOffset 防 z-fighting）；图形层保持自由定位与 z，深度 `zBase - 1/2048 - z/2048`。
- 同步：`SyncGridPayload` NBT 改 gzip 压缩（`NbtIo.writeCompressed/readCompressed`）；`ScreenText.save` 用 int[] 定长数组紧凑编码。
- 旧存档屏幕文本（自由定位格式，无 "cols" 字段）加载时清空重置（破坏性变更，已确认接受）。
- 已删除：`setFillPadding`（LED 内缩，用户要求移除）；`fill` 背景格曾短暂移除后又按用户要求恢复。

## 测试 Monitor 变换方案（facing + offset + yaw + pitch）

- **模型层级**：底座（固定）→ bearing（偏航支架）→ case（头部，含屏幕/棋盘）。
- **变换顺序**（PoseStack 后调为内层、先作用于顶点）：`facing(方块中心,Y) → offset(前后平移,Z) → yaw(颈部,Y) → pitch(铰链,X)`；facing 作用于整体；offset/yaw 作用于 bearing+case；pitch 只作用于 case。
- **枢轴静态常量（定义在 `PitchMonitorTestBlock`，单位=模型像素，微调只改这里）**：
  - `HINGE_Y=9`、`HINGE_Z=8`（俯仰铰链，case 侧轴承中心）
  - `NECK_X=8`、`NECK_Z=8`（偏航颈部，bearing 水平中心）
  - 铰链与颈部同 z=8，构成单点万向（gimbal），yaw 时铰链不画弧。
- **offset 语义**：正值向前（朝屏幕 -Z），相对 facing 前后平移 case+bearing；单位 1/16 方块（1px）。
- **yaw 语义**：相对 facing（yaw=0 即当前朝向）。
- **命中检测**：世界/plot → 模型空间逆序 `facing逆 → offset逆 → yaw逆 → pitch逆`（正向的逆序、旋转取负、平移取反）。
- **万向锁**：不处理，模组介绍中提示玩家避免 pitch=±90° 相关表现。
