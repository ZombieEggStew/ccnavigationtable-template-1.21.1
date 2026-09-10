# 这个 mod 的物理模型（源码依据）
先明确这个 mod 是怎么算力的，这决定了"位置关系"为何重要：

1. 重心（COM）怎么算
MassTracker.java（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\physics\mass\MassTracker.java）：
每个实心方块有自己的质量（默认 1.0，可被数据包覆盖，见 PhysicsBlockPropertyHelper.getMass）
重心 = 所有方块质量的加权平均位置，惯性张量也由质量分布算出
→ 你想挪重心，就加/换重方块（比如把机械方块、原木堆在机头或机尾）

2. 升力/阻力是"点力"，施加在每块帆自己身上
BlockSubLevelLiftProvider.java（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\block\BlockSubLevelLiftProvider.java）：

每块帆在自己方块中心施力；升力方向 = 帆面法线；大小 ∝ 该位置的局部气流速度
局部气流速度 = 整体线速度 + 角速度 × (方块位置 − 重心) → 远离重心的帆会"感受到"旋转，自动产生气动阻尼
力矩计算（ForceTotal.java）：力矩 τ = (施加点 − 重心) × 力 ← 这就是全部规则的根源

3. 推力施加在螺旋桨方块中心
BlockEntitySubLevelPropellerActor.java（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\block\propeller\BlockEntitySubLevelPropellerActor.java）：推力在螺旋桨方块中心、沿螺旋桨朝向施加。陀螺仪螺旋桨轴承还能把推力方向在 ±12° 锥角内偏转（GyroscopicPropellerBearingBlockEntity.java）。（推力大小怎么算见下文"螺旋桨推力模型"一节）

4. 重力施加在重心上（DiagramEntity.java 第 186 行）→ 重力不产生力矩。

5. 通用阻力（universal drag）：Rapier 给每个 sublevel 刚体的恒定线/角速度阻尼（默认 d=0.09），直接衰减刚体速度、不经过力组 → 图纸和记录器都看不到。等效力 F = −m·d·v，在巡航速度下是最大的阻力项（详见下文"通用阻力"一节）。

6. 游戏内可视化工具：Simulated 的 Contraption Diagram（图纸） 可以显示重心图标 + 升力/阻力/推力/重力的力箭头（DiagramScreen.java，LIFT=浅蓝、PROPULSION=蓝、GRAVITY=绿）。这是你调试飞机的核心工具。⚠ 图纸只画力组，**通用阻力不在其中**——用图纸算净力时必须补上 F = −m·d·v。

---

# 对称帆（symmetric sail）的阻力：大小和方向怎么算
Simulated 的 SymmetricSailBlock 本身不算力——它只是 Sable `BlockSubLevelLiftProvider` 接口的实现，计算全在 Sable 的默认方法里（Simulated 运行时依赖 sable 2.0.4-37）：

- 参数来源：SymmetricSailBlock.java（references\Simulated-Project-main\simulated\common\src\main\java\dev\simulated_team\simulated\content\blocks\symmetric_sail\SymmetricSailBlock.java）
- 计算公式：BlockSubLevelLiftProvider.java 的 `sable$contributeLiftAndDrag()` 默认方法（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\block\BlockSubLevelLiftProvider.java）
- 调用时机：ServerSubLevel.prePhysicsTick()，每个物理子步对整机/contraption 上每一块帆各算一次，结果记入该子步的 linearImpulse / angularImpulse

对称帆覆写的参数 vs Create 普通帆（Sable 默认值，SailBlockMixin 注入）：

| 参数 | 对称帆 | 普通帆（默认） | 含义 |
|---|---|---|---|
| sable$getLiftScalar() | 0 | 0.475 | 升力系数 k3：对称帆不产生升力 |
| sable$getParallelDragScalar() | 1.75 | 0.75 | 法向阻力系数 k1（垂直帆面的阻力） |
| sable$getDirectionlessDragScalar() | 0.06888202261（未覆写） | 0.06888202261 | 无方向阻力系数 k2（线性阻尼） |
| sable$getNormal() | AXIS 正方向 | FACING 反方向 | 帆面法线 n |

每个物理子步 Δt、每块帆的算法：

1. 法线 n = 该帆 AXIS 的正方向；帆在 contraption 里时先经 localPose 旋转到子层级坐标系
2. 帆方块中心处的局部气流速度 v = R⁻¹( 整机线速度 V + 角速度 ω × (方块中心 − 子层级原点) )——与 n 同一个坐标系
3. 该处气压 P = DimensionPhysicsData.getAirPressure(...) = 维度 basePressure × 按高度(y)的压力曲线
4. 法向阻力（对称帆的主力）：大小 = |n·v| · 1.75 · P · Δt
   方向：沿法线 n、符号跟随 (n·v) → 施加到机体时取负，效果永远是"抵消速度在帆面法线上的分量"
   → n·v = 0（气流顺着帆面，即帆面与运动方向平行）时此项为 0，帆"没有效果"；转出角度后才有阻力
5. 无方向阻力（阻尼）：与 v 反向，大小 = |v| · 0.06888 · P · Δt（恒抵消线速度）
6. 升力 = 0 → 对称帆只产生阻力（这正是它名字的由来）
7. 施力点 = 帆方块中心（pos+0.5），力矩 = (施力点 − 重心) × 力 → 帆离重心越远，同样阻力产生的力矩越大

对设计的直接含义：

- 阻力方向沿帆面法线而不是沿速度反方向 → 帆只"吃掉"速度中垂直于帆面的分量。帆面与气流平行时几乎无阻力，偏转出角度阻力才出现——舵面/安定面因此能产生控制力矩
- 1.75 是普通帆法向系数(0.75)的 2.3 倍，且升力为 0 → 对称帆是纯阻力/阻尼面：尾翼越大、离重心越远，俯仰/偏航阻尼越强、飞机越"稳"，但也会更迟钝
- 整个阻力受 P 缩放：改维度 dimension_physics 数据包的气压/高度曲线，会整体缩放所有帆的阻力（和普通帆的升力）
- 对称帆 = "风向标式 α 弹簧"：法向阻力 (n·v) 是奇函数，抬头/低头扰动产生方向相反的恢复力——只要放在重心后方，**光靠对称帆尾翼就能提供双向静稳定**，不需要"升力中心在重心之后"；刚度 ∝ k1·P·V·力臂（高空/低速时刚度自动变弱，见"游戏气动 ≠ 现实气动"一节）

---

# Create 普通帆（regular sail）的升力与阻力：大小和方向怎么算
"普通帆" = Create 的 SailBlock（Simulated ponder 里的 Regular Sail；Simulated 自己只有对称帆方块）。它的升力/阻力与对称帆共用同一个 Sable 公式，区别只是由 Create 兼容 mixin 注入接口、参数全用默认值（升力开启、法向阻力较弱）：

- 注入代码：SailBlockMixin.java（references\sable-main\neoforge\src\main\java\dev\ryanhcode\sable\neoforge\mixin\compatibility\create\sails_providing_lift\SailBlockMixin.java，已注册于 sable-neoforge.mixins.json）——只覆写法线 sable$getNormal() = FACING 反方向，其余全默认
- 计算公式：与对称帆同一个 `sable$contributeLiftAndDrag()`（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\block\BlockSubLevelLiftProvider.java）
- 调用时机：同上，ServerSubLevel.prePhysicsTick() 每个物理子步每块帆一次

| 参数 | Create 普通帆（全默认） | 对称帆（Simulated） | 含义 |
|---|---|---|---|
| 法线 n（sable$getNormal()） | FACING 反方向 | AXIS 正方向 | 升力/法向阻力的轴向 |
| 升力系数 k3（sable$getLiftScalar()） | 0.475 | 0（覆写） | 普通帆才产生升力 |
| 法向阻力系数 k1（sable$getParallelDragScalar()） | 0.75 | 1.75（覆写） | 垂直帆面的阻力 |
| 无方向阻力系数 k2（sable$getDirectionlessDragScalar()） | 0.06888202261 | 0.06888202261 | 线性阻尼 |

每个物理子步 Δt、每块帆的算法（n、v、P 的定义与对称帆一节相同）：

1. 法向阻力（k1 = 0.75）：F_par = n·(n·v)·0.75·P·Δt，施加到机体取负 → 抵消速度在法线上的分量；大小 = |n·v|·0.75·P·Δt
2. 无方向阻力（k2）：与 v 反向，大小 = |v|·0.06888·P·Δt
3. 升力（普通帆核心输出，k3 = 0.475）：
   - 先从速度里扣掉已被法向阻力吃掉的部分：TEMP = v − F_par矢量
   - 大小 = |TEMP|·0.475·P·Δt（≈ 随该处局部气流速度增长）
   - 方向 = 恒沿 n（FACING 反方向），没有 (n·v) 那样的符号翻转——帆永远被往 n 那一侧推
4. 施力点 = 帆方块中心（pos+0.5），力矩 = (施力点 − 重心) × 力 → 与对称帆完全相同的"点力"模型

为什么默认值长这样（接口注释）：三系数需满足 k2 ≥ (−k1+√(k1²+k3²))/2 才不产生速度指数增长；默认 k2 = (−0.75+√(0.75²+0.475²))/2 = 0.06888202261，恰好取等号 = "升力 0.475 + 刚好压住发散的最小阻尼"。

对设计的直接含义：

- 普通帆 = "升力为主"的升力面（对称帆 = 纯阻力面）。升力方向固定在帆自身坐标系（n 侧），随机体一起转动，不会像真实翼型那样自动反向：机体倒扣时 n 朝下 → 升力也朝下（压向地面）；只有姿态摆正、n 侧朝上时才是可靠升力
- 升力/阻力大小都 ∝ 该处局部气流速度 |v|（线速度 + 角速度×力臂）→ 帆离重心越远，同样的机体运动产生更大的力与力矩（阻尼、配平、控制都靠这个）
- 全部力受 P（维度气压 basePressure × 高度曲线）整体缩放

---

# 通用阻力（universal drag）：Rapier 刚体的速度阻尼（图纸/记录器都看不到的力）

> 发现经过：图纸显示推力 ~400、帆阻力 ~112，若按"只有这两个力"推断净推力 +288 应持续加速，但 flight_overworld_00b1000b_19.csv 巡航段空速稳在 62、dv/dt ≈ +0.014 m/s²——缺失的向后力就是通用阻力，约 255，比帆阻力还大近一倍。

- 参数来源：`DimensionPhysics.universalDrag`，**默认 0.09**（`DimensionPhysics.java` 的 `DEFAULT_UNIVERSAL_DRAG`）；可被维度数据包 `data/<命名空间>/dimension_physics/*.json` 的 `"universal_drag"` 字段覆盖
- 施加位置（与帆力完全不同的通道）：
  `SubLevelPhysicsSystem.initialize()`（第 168–172 行）→ `pipeline.init(gravity, universalDrag)` → Rapier 引擎在**每个 sublevel 刚体创建时**调用 `set_linear_damping(0.09)` + `set_angular_damping(0.09)`（`sable_rapier\src\main\rust\rapier\src\lib.rs` 第 677–678 行；rope 同样，`rope.rs` 第 235–236 行）
- 它**直接衰减刚体速度，不经过力组**（QueuedForceGroup）→ 图纸（DiagramEntity 读力组）和飞行记录器 CSV 都看不到。这就是"图纸净力 ≠ 实际净力"的根本原因
- 公式（Rapier 线性阻尼，每物理子步 Δt）：
  - 线速度：v ← v / (1 + d·Δt)
  - 角速度：ω ← ω / (1 + d·Δt)
  - 连续近似：dv/dt = −d·v → **等效力 F = −m·d·v**（与速度成正比、与**质量成正比**、**不乘气压 P**）
- 定量实例（flight_overworld_00b1000b_19.csv 巡航段；图纸标度 = 冲量×60，即该游戏 substepsPerTick=3）：
  - m ≈ 45.25（整链）、v ≈ 62.6 → F = m·d·v = 45.25 × 0.09 × 62.6 ≈ **255**（图纸单位 ≈ 牛顿）
  - 平衡账：推力 ≈ 407，帆阻力 ≈ −137，通用阻力 ≈ −255 → 净 ≈ +8 ≈ 0；实测 dv/dt = +0.014 m/s² 稳速成立
  - 通用阻力是巡航时**最大的阻力项**（约为帆阻力的 2 倍）
- 对设计的直接含义：
  - **极速公式**（简化，忽略气流削减）：平衡时 T(推力) = v·(0.09·m + 帆阻力系数·P) → 极速 ∝ 推力 / (0.09·m + c·P)；完整模型（含推力气流削减系数与总动力方块数阻力）见下文"巡航方程组"一节
  - **减重直接提速**：通用阻力 ∝ m，同一推力下轻飞机极速更高、爬升更快（比调帆更直接）
  - **不乘 P**：帆阻力、推力都 ∝ P，通用阻力只 ∝ m·v → 高空时推力/帆阻力同衰而通用阻力不衰 → 高空极速上限下降，与"高空需更快空速才够升力"叠加，加剧高空掉速掉高（见 memo\.current_mission.md §13）
  - 角速度阻尼 0.09 同样恒定存在 → 姿态天然被阻尼（"稳重"）；做 phugoid/姿态阻尼分析时它已在物理里，别把它当成"无阻尼基线"

---

# 螺旋桨推力模型（Propeller）：大小怎么算（含气流削减系数）

Create 螺旋桨轴承（Propeller Bearing）的桨盘上装动力方块（风帆 / 对称风帆 / 羊毛方块）产生推力。参数来自 aeronautics 配置（`aeronautics > server > Physics`）；以下公式是 `getPropellerRPM` 转速工具的来源：

- 参数来源：Propeller Bearing Thrust **T**（默认 0.2）、Propeller Bearing Airflow **A**（默认 0.05）；推力 ∝ airflow scaling × air pressure（`BlockEntityPropeller`）
- 配置缓存：进游戏（服务器启动）与放置/加载 FMC/AIC 时刷新一次（`SensorSystemAPI.refreshAeroConfig`，不逐 tick 读）
- 相关工具：`getPropellerRPM(F, P, V, θ?)`（本模型的转速反解）、`getMaxAltitude(...)`（见下节）

**N** 个螺旋桨、每个桨盘 **S** 个动力方块、转速 **R** 时的总推力模型：

```
F = P · S^1.5 · N · T · R · (1 − v·sinθ / (S^0.5 · R · A))
```

反解（达到推力 F 所需转速，即 `getPropellerRPM` 公式）：

```
R = F / (P · S^1.5 · N · T) + v·sinθ / (S^0.5 · A)
```

- **P**：气压（随高度指数衰减，与帆力同源缩放）；**v**：空速；**θ**：气流角
- **气流削减系数 (1 − v·sinθ/(S^0.5·R·A))**：桨盘穿过自己推的空气，有效推力随空速下降。**θ 按机型取用**：
  - **固定翼飞机平飞：θ = 90**——气流穿过桨盘，削减全额生效（sin 90° = 1）
  - **直升机垂直起降：θ = 0**——无削减
- **桨面最大有效速度 = S^0.5·R·A**：空速超过该值时推力 ≤ 0（桨变刹车）→ 要飞得快，桨盘动力方块 S 必须足够大
- 平飞（θ=90）形式：`F = P·S^1.5·N·T·R·(1 − v/(S^0.5·R·A))`

对设计的直接含义：

- 推力 ∝ P·R，但受气流削减 → 高速时推力随 v 下降；极速与"最高高度"由 推力 = 阻力 决定（见下节）
- θ=90 时所需转速比直觉高：R = F/(P·S^1.5·N·T) + v/(S^0.5·A)——第二项是维持平飞速度的"气流代价"，速度越快代价越大
- 推力 ∝ S^1.5：加桨盘动力方块对推力的增益大于线性（但同时也计入总阻力 N_s，见下节）

---

# 巡航方程组与最高稳态高度（getMaxAltitude）

把三类力合起来，平飞稳态巡航（θ=90、气流与帆面法向垂直故法向阻力=0）有两个方程、两个未知量 (v, P)：

```
(1) 升力 = 重力:   k3·P·v·N_w                          = m·g
(2) 推力 = 阻力:   P·S^1.5·N_p·T·R·(1 − v/(S^0.5·R·A))  = k2·P·v·N_s + m·d·v
```

- **N_w** = 升力帆（普通帆）数；**N_s** = 总动力方块数 = 风帆 + 对称风帆 + 螺旋桨动力方块（N_p×S）——所有动力方块都参与无方向阻力 k2·P·v
- 系数：k3=0.475、k2=0.06888、d=0.09（通用阻力，不乘 P）、g=11、T/A 见上节

**关键消元（不需要矩阵/行列式）**：方程组对 (v, P) 是**双线性**的（每个方程里 v 与 P 只以乘积出现）。令升力乘积 x = P·v：

```
(1) → x = m·g / (k3·N_w)          （与高度无关，直接钉死）
(2) 代入 v = x/P → 对 P 一元二次：a·P² + b·P + c = 0
    a = S^1.5·N_p·T·R
    b = −(S·N_p·T·x/A + k2·x·N_s)       （S^1.5/S^0.5 = S、R 消去）
    c = −m·d·x
正根：P* = (−b + √(b²−4ac)) / (2a)，v* = x/P*，高度 h* = 气压曲线反解(P*)（二分）
```

- c<0 恒定 ⇒ 判别式恒正、**恰一正根**；根处推力=阻力>0 ⇒ 气流削减系数自动为正
- 高度反解：P(h) 是分段 Hermite、不可解析求逆 → 数值二分（与 `getAltitudeFromPressure` 同款）
- 对应工具：`getMaxAltitude(m, wingSails, symmetricSails, propellerCount, sailsPerPropeller, maxRpm)` → `{velocity, altitude}`（FMC 门控，纯数学）

**互补视角（正向链更简单）**：给定高度求所需转速是**显式公式**：

```
R(h) = [k2·x·N_s + m·d·x/P(h)] / (P(h)·S^1.5·N_p·T) + (x/P(h)) / (S^0.5·A)
```

P(h) 随高度下降 → 两项都上升 → **R(h) 随高度单调上升** → R_max 对应的 h 就是最高稳态高度；也可以直接对 h 二分 R(h) = R_max（比解二次方程更直接）。

对设计的直接含义：

- **最高高度由转速上限决定**：低于该高度，每个高度对应唯一所需转速（R(h) 单调）——"该高度需要多少油门"可以预先算出
- **可行性检查**：P* 必须 ≤ 大气底最大气压（≈1.5，否则贴地也升力不足=飞不起来）；h* 受大气顶（主世界 320m，P=0 无空气）限制
- R=256 只是转速上限，实际还受**应力网络容量**约束（`getStressRemaining`）——满转速推不动就是应力不足
- 设计计算用途：改帆数/桨盘 S/质量，立即看最高高度变化，比进游戏试飞快

---

# ⚠ 先说清楚：游戏气动 ≠ 现实气动（位置规则都从这里推，不是航空学搬运）
下面所有"位置规则"都由上文两节的游戏公式推出。三条根本差异：

1. **力的方向是"机体固定"的，不随气流方向翻转**
   普通帆升力恒沿帆面法线 +n；对称帆阻力沿 ±n；无方向阻尼沿 −v。真实翼型升力垂直于气流、随攻角连续改向——游戏里没有这个行为。→ 帆的摆向决定力的方向：倒扣时机翼升力朝下压；想要哪个方向的力就摆到那个方向。

2. **力的大小 ∝ 局部气流速度（线性），不是 v²**
   每块帆 F ≈ k · P · Δt · v，没有动压 q = ½ρv²。→ 低速气动力线性衰减；速度为零就没有任何气动力（低速段要靠推力矢量/反作用轮）。

3. **攻角（α）效应只来自 (n·v) 项，且只有对称帆/法向阻力是"奇函数"**
   普通帆升力大小 = |v − n(n·v)·c|（c = k1·P·Δt 很小）——展开后随 (n·v)² **减小**：气流顺帆面（n·v=0）时升力最大，正对气流时反而变小。对 ±α 是**偶函数**。
   对称帆法向阻力 = (n·v) 线性项——对 ±α 是**奇函数**：抬头/低头扰动产生方向相反、大小对称的恢复力。

→ 推论（与现实的对应关系）：
- 现实"重心在气动中心之前 → 静稳定"依赖机翼升力随 α **增长**。游戏里普通帆升力对 ±α 对称**减小**，机翼放在重心后只对一侧恢复、另一侧反而发散——**这条现实规则不能照搬**。
- 游戏里真正提供**双向静稳定**的是奇函数面（对称帆的 (n·v) 力）放在重心后方——它像风向标/α 弹簧，把机体拉回与气流对齐。规则见下节表格。

---

# 重心 / 升力中心 / 推力线的位置规则
由公式 τ = r × F 直接推出（r = 力施加点到重心的向量）：

| 关系 | 规则 | 后果 |
|---|---|---|
| α 敏感面（尾翼）vs 重心（纵向）|	纵向静稳定来自"α 敏感面"(n·v) 力：对称帆尾翼放重心后方即双向恢复（抬头/低头都被拉回）。重心越前 → 尾翼力臂越长 → α 弹簧越硬 → 越稳（定性同现实，机制不同）|	普通帆机翼（升力面）的纵向位置不提供刚度（升力对 ±α 对称、偶函数，放后只"半稳"），只影响配平杠杆；光有机翼无法双向静稳定，尾翼对称帆才是关键；机翼力臂不足会吃掉静稳定
| 升力中心 vs 重心（垂直）|	升力中心（机翼）应在重心之上 |	重心低 = 摆锤效应 → 滚转/俯仰天然稳定；机翼低于重心 → 容易翻滚
| 推力线 vs 重心（垂直）|	推力线最好通过重心或略低于重心 |	推力线过高 → 加油门时下沉，油门难控；过低 → 加油门抬头
| 推力线 vs 重心（纵向）|	前后无所谓，但推力线在重心正上方/正下方才产生纯俯仰力矩；偏左/偏右会产生偏航力矩	| 对称布局时左右推力要平衡

关键结论：让重心尽量低、让机翼（升力面）尽量高、推力线穿重心——这是一架好飞飞机的第一步。

---

# 控制面（俯仰/滚转/偏航）怎么布置
重要事实：这个 mod 没有传统意义上的"副翼/升降舵/方向舵"方块。可用的控制手段是（源码里能看到官方用法）：

1. 对称帆 + 旋转轴承 = mod 的"舵面"（官方 ponder 教程 SymmetricSailScenes.java 明确演示：尾部对称帆 + 轴承旋转 30° 就是"rudder"方向舵，用方向盘控制）。对称帆 sable$getLiftScalar()=0、parallelDragScalar=1.75——只产生阻力不产生升力，偏转后阻力方向改变 → 产生控制力矩（阻力怎么算见上文"对称帆的阻力：大小和方向怎么算"一节）。

2. 陀螺仪螺旋桨轴承：矢量推力，±12° 偏转 → 俯仰/偏航控制。

3. 反作用轮（Create 飞轮）：ReactionWheelManager.java，通过角动量变化直接给三轴姿态力矩。
4. 差速螺旋桨：左右两螺旋桨转速不同 → 偏航力矩。

4. 对应布置建议（现实原理同样适用，核心是力臂越大力矩越大）：

1. 方向舵（偏航）：尾部垂直装对称帆 + 垂直轴旋转轴承。离重心越远越好（力臂大），但别超出机身尾部太多（阻力/阻尼过大反而迟钝）。
2. 升降舵（俯仰）：尾部水平装对称帆 + 水平轴旋转轴承（平尾）。同样放尾部、远离重心。
3. 副翼（滚转）：左右翼尖各装对称帆，各自用沿机身前后方向（纵轴）的旋转轴承，做差动偏转（左尖上偏、右尖下偏）→ 滚转力矩。副翼放翼尖最有效（力臂 = 半翼展）。
4. 所有控制面左右必须对称，否则会产生持续偏航/滚转力矩。

--- 

# 稳定性设计清单（现实 + mod 通用）
1. 纵向稳定：α 敏感面（对称帆平尾）放在重心后方（力臂大 = α 弹簧硬、双向恢复；规则见上表"位置规则"节）。机翼（普通帆）位置只管配平不管稳定。
2. 方向稳定：垂直尾翼（侧向面积）放在重心后方，侧滑时产生恢复力矩。
3. 上反角：左右机翼略微上翘，侧滑时产生滚转恢复力矩（mod 里把帆摆出一点角度即可）。
4. 重心低：重物放机身下部/座舱下，升力面在上 → 摆锤稳定，救飞机最容易。
5. 气动阻尼：机翼/尾翼离重心远 → 角速度引起的局部气流大 → 阻力强 → 姿态震荡衰减快（飞机"稳重"）。机身做得越长越稳。
6. 推力：推力线过重心（螺旋桨装在与重心同高/略低处）；油门响应要可控，别把螺旋桨装在重心上方太高处。


上单翼 high-wing 好飞 摆锤稳定，适合新手 （塞斯纳 172、很多运输机）
中单翼 mid-wing
下单翼 low-wing 敏捷 灵敏但爱翻滚 （Bf 109、喷火、P-51）

---

# 机动型设计清单（与"稳定性清单"对照：可操纵 > 安定）
目标：响应快、动作干脆、全速域可控。代价是安定性下降——两条清单本质是同一根轴的两端，机动飞机就是把上面那套"稳定化设计"反向调。核心机制回顾：控制力矩 τ = r×F（r = 力臂），角加速度 = τ / 转动惯量 I；气动力大小 ∝ 该处局部气流速度。

1. 控制权威：舵面 = 对称帆 + 旋转轴承。面积大、离重心远（力臂大）、偏角尽量大 → 力矩才够。低速段气动力 ∝ 气流速度会失效，必须配"不靠气流"的控制：矢量推力（陀螺仪螺旋桨轴承 ±12°）、反作用轮、差速螺旋桨——全速域可控才是高可操纵。
2. 三轴分工、严格对称：垂尾方向舵（偏航）/ 平尾升降舵（俯仰）/ 翼尖差动副翼（滚转，力臂 = 半翼展）。左右不对称 = 永久耦合的偏航/滚转力矩，直接毁掉操纵。
3. 质量贴重心、机身紧凑：角加速度 = τ / I。重物远离重心、机身加长 → 惯量 I 与阻尼同时涨 → 响应钝。短机身 + 大舵面通常比长机身 + 小舵面灵。
4. 力臂别无限加长：控制力矩随力臂线性涨，转动惯量随长度平方涨——加长到一定程度只剩"更稳、更钝"，与机动性冲突。
5. 降低静稳定裕度：重心从"偏前"向升力中心后移（机制：重心后移 → 尾翼力臂缩短 → α 弹簧变软）→ 俯仰更灵敏；但别越过临界，留一点裕度让平尾配平兜底，否则一抬头就发散救不回来。
6. 砍掉过度稳定化设计：无/小上反角、重心别放太低、下单翼——上反角和摆锤效应都在对抗滚转/俯仰指令，每一点稳定余量都吃掉一分机动性。
7. 阻尼调到"收敛不振荡"：对称帆天然是阻尼面（离重心越远阻尼越强）。阻尼太小 → 动作后姿态来回晃；太大 → 发闷迟钝。可操纵 = 阻尼刚好压住震荡、又不吞掉控制力矩。
8. 推力线穿重心（或略低）：加减油门不产生俯仰力矩、不改变配平 → 机动中油门随意收放，姿态不抖。
9. 推重比留足：爬升、改出俯冲、拉大迎角都靠多余推力；低速/失速段再由推力矢量接管控制。
10. 记住普通帆升力方向固定在机体上（恒沿帆的 n 侧，随机体转）：倒扣时机翼升力变成"往下压"——设计特技动作前先想清楚姿态与升力方向的关系。

验证：用 Contraption Diagram（图纸）检查重心/升力中心/力箭头，再按动作清单实测：滚转 360° 是否流畅干脆、急转弯是否掉头/侧滑、失速后能否改出、倒飞是否还能控制。



// 1. 外环：角度环 (通常运行在 100-200Hz)
angle_error = target_angle - current_angle; // 计算角度误差
target_angular_rate = Kp_angle * angle_error; // 外环输出 = 目标角速度

// 对目标角速度进行限幅，防止指令过于激进
target_angular_rate = constrain(target_angular_rate, -max_rate, max_rate);

// 2. 内环：角速度环 (通常运行在 500Hz-1kHz)
rate_error = target_angular_rate - current_angular_rate; // 计算角速度误差
// 内环进行完整的PID计算，输出最终的电机控制量
control_output = Kp_rate * rate_error + Ki_rate * integral(rate_error) + Kd_rate * derivative(rate_error);

// 3. 将控制量输出到执行器（电机/舵机）
set_actuator(control_output);





// 飞控配置
const float DT = 0.05;          // 20Hz 采样间隔
const float CUTOFF_FREQ = 6.0f; // 截止频率 6Hz (黄金值)

// 计算滤波系数 alpha (只需计算一次，放在初始化函数里)
float alpha = 1.0f / (1.0f + 1.0f / (2.0f * 3.14159f * CUTOFF_FREQ * DT));

// ---- 每帧飞控循环 (20Hz) ----
float current_error = 0.0f - current_roll_angle; // 假设目标滚转角为0

// 1. 计算原始微分 (角度变化率)
float raw_derivative = (current_error - last_error) / DT;

// 2. 应用截止频率公式进行低通滤波
float filtered_derivative = alpha * raw_derivative + (1.0f - alpha) * last_filtered_derivative;

// 3. PD 控制输出
float output = Kp * current_error + Kd * filtered_derivative;

// 4. 更新历史值
last_error = current_error;
last_filtered_derivative = filtered_derivative;