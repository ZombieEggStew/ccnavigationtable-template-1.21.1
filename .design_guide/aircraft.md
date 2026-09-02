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
BlockEntitySubLevelPropellerActor.java（references\sable-main\common\src\main\java\dev\ryanhcode\sable\api\block\propeller\BlockEntitySubLevelPropellerActor.java）：推力在螺旋桨方块中心、沿螺旋桨朝向施加。陀螺仪螺旋桨轴承还能把推力方向在 ±12° 锥角内偏转（GyroscopicPropellerBearingBlockEntity.java）。

4. 重力施加在重心上（DiagramEntity.java 第 186 行）→ 重力不产生力矩。

5. 游戏内可视化工具：Simulated 的 Contraption Diagram（图纸） 可以显示重心图标 + 升力/阻力/推力/重力的力箭头（DiagramScreen.java，LIFT=浅蓝、PROPULSION=蓝、GRAVITY=绿）。这是你调试飞机的核心工具。

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

# 重心 / 升力中心 / 推力线的位置规则
由公式 τ = r × F 直接推出（r = 力施加点到重心的向量）：

| 关系 | 规则 | 后果 |
|---|---|---|
| 升力中心 vs 重心（纵向）|	升力中心应在重心略偏后（现实：重心在气动中心之前 → 稳定）|	重心太靠后 → 一抬头就继续翻（不稳定）；太靠前 → 低头压不住
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
1. 纵向稳定：重心在机翼升力中心之前（重心偏前）。太靠前则低头，靠平尾配平。
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
5. 降低静稳定裕度：重心从"偏前"向升力中心后移（现实叫放宽静稳定）→ 俯仰更灵敏；但别越过临界，留一点裕度让平尾配平兜底，否则一抬头就发散救不回来。
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