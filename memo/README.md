# memo 索引（唯一目录入口）

> 48 份文档，按主题分 7 个目录（2026-11 整理）。**先读这份索引，再只读需要的那 1~2 份** —— 别整目录浏览。
> 大文件（`flight/flip-autopilot.md` 320 KB、`flight/starship15-catch.md` 126 KB）**只许按关键词/§N 定向检索**，禁止整篇读。

## 按任务找文档（阅读顺序）

| 任务 | 先读 | 再读 |
|---|---|---|
| 定位或修改 Java 源码 | `project/code-map.md` | 对应功能文档 |
| 查外部依赖 API（Sable / Flywheel / Catnip / Create / CC:T / JEI） | `project/api-code-map.md` | 再进 `api/` 搜源码 |
| 改任何代码前核对已知坑 | `project/ATTENTION.md` | 功能文档里的「坑」小节 |
| **星舰 CC:T 飞行迭代（每一轮）** | `flight/starship15-ROUND.md` + `flight/starship15-constants.md` | 需要原始推导时才 grep `flight/starship15-catch.md` §N |
| **四喷口飞行器迭代（每一轮）** | `flight/four-nozzle-plan.md` + `flight/four-nozzle-constants.md` | 两发时代的数据/推导在 `archive/two-nozzle-*.md`（只作历史，不当本机常数） |
| 换人 / 换会话 / 大阶段收尾 | `flight/four-nozzle-handover.md`（四喷口）、`flight/starship15-handover.md`（星舰）、`archive/two-nozzle-handover.md`（两发历史） | — |
| **写/改任何 CC:T 飞行程序（新载具开工）** | `flight/flight-program-standard.md` | `flight/four-nozzle-plan.md` |
| 写 CC:T 飞行控制环（通用经验） | `flight/flip-autopilot.md`（按关键词检索） | `propulsion/advanced-thrust-nozzle.md`、`peripherals/my_aero_sensor.md` |
| 添加控制台控件 / 表面 Monitor 模块 | `cockpit/control-desk-grid-slot.md` | `cockpit/control-desk-seat.md` |
| 动屏幕渲染 | `cockpit/record_screen_text.md`、`cockpit/record_screen_module.md` | `cockpit/screen-render-rework.md` |
| 喷管出力/粒子/频道/GUI | `propulsion/thrust-nozzle.md`（母文档） | `propulsion/advanced-thrust-nozzle.md` |

## project/ —— 项目元信息与入口（5）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `code-map.md` | **Java 代码地图**：按修改目标定位表、模块职责表（注册/方块与 BE/Monitor/GUI/网络/频道/CC 兼容/物品）、两条核心数据流、已知边界 | 定位或改 `src/main/java` 时**第一步**读它，不要先搜源码 |
| `api-code-map.md` | **api/ 依赖源码包级地图**：Sable Companion / Flywheel / Catnip 三件套重点说明、import 前缀→路径对照、项目内使用文件清单 | 要精确包路径/类位置/签名时先查它 |
| `ATTENTION.md` | **踩坑与注意事项合集**：Sable 坐标、变换互逆、枢轴、GridState re-key、屏幕破坏性变更、引擎门控、INS `randomNudge` | 改任何代码前先核对对应模块小节 |
| `neoforge-debugging.md` | F5 启动事实：`-Dfml.modFolders=ccnavigationtable%%D:/.../build/classes/java/main` + preLaunchTask | 调 VS Code 启动配置时 |
| `FIX-ME.md` | 未处理问题便签：Monitor 两套同步机制（`SyncGridPayload` vs `sendBlockUpdated`）边界、`PeripheralExtenderScreen` 轮询写法建议 | 接手待办或排查同步问题时 |

## flight/ —— CC:T 飞行与自动驾驶（10）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `flight-program-standard.md` | **全局程序规范 + 共享常数快照**：终端只准 ASCII / ≤15 行 / TAG 约定、`say()` 双写 `status_*.txt` + `/log/*.csv`、主机侧读取路径与 digest 纪律、程序骨架、20 Hz 一写块、7 道安全门、通用黑名单、G=11/d=0.09/K_NOZ=524.8/锥角 15°/TWR 与平飞调度公式、新机必测清单、回报模板 | **写/改任何 CC:T 飞行或测量程序前**先读它；新载具开工只需它 + 本机设计文档 |
| `starship15-ROUND.md` | **本轮工作卡（≤60 行，每轮整篇覆写）**：目标 / 只读清单 / 上轮结论 / 改动清单 / 回报模板 / 硬规矩 | 星舰每一轮开工的**唯一入口** |
| `starship15-constants.md` | **可信常数 + 已否决假设黑名单（唯一真相源）**：G=11、推力模型、α=1.0/单位D、pairA→X(负)、20 Hz 写预算、三条控制律、**27 条黑名单**、程序版本表（`flip` **v44 定稿** / `catch` **v1.6 定稿**）、工具 | 写/改星舰控制环、判断某个数能不能信时 |
| `starship15-handover.md` | **一页纸交接（2026-11-19 13:52 ★ 任务完成：夹取时序收官，`flip` v44 / `catch` v1.6，用户判定"完美"）**：现状表、可信常数摘要、四条控制律、程序清单、硬件频道、世界几何（投放点 (9.5,306.5,+66.9)）、工具与磁盘纪律、坑清单、未解决项、下一步计划 | 换人 / 换会话 / 大阶段收尾通读一次 |
| `starship15-catch.md` | **归档总账（126 KB）**：§11~§16 标定飞行、§19/§20 力矩通道、§21 姿态环、§22 世界几何、§23 横向环、§24~§41 完整 V、**§42~§49 任务链闭环、§50 v21~v30、§51 v31 双模型、§52 v31 真机、§53 v32 撞塔、§54 v33/v34 连接器、§55 v34 抬升 bug、§56 v35 PASS、§57 v36 PASS、§58 v37 PASS、§59 末端"停住+上下晃"被否决与 v38 计划** | 需要某结论的原始推导/日志证据时，grep §N 定向读 ≤80 行 |
| `flip-autopilot.md` | **飞车总账（320 KB，按关键词检索）**：落地剖面 `A_REF/KV/FF_BAND`、四元数姿态环与 `COS_MIN` 门控、**写预算与 20 Hz 不变量**、吸附/翻滚/横向制导实测常量 | 写任何 CC:T 飞行/落地控制环时的通用经验库 |
| `flight-recorder-and-handling.md` | `FlightDataRecorder` 用法（配置、CSV 列、`flight_logs/` 落盘）+ 飞机可操纵性旧任务（phugoid、气动模型事实） | 要用 `run/flight_logs/*.csv` 交叉验证时（原名 flight.md，已改名） |
| `four-nozzle-plan.md` | **四喷口矩形飞行器本轮工作卡 + 控制分配设计**：四台 `getForcePoint` 力臂 → 俯仰/滚转（推力差）、偏航（切向锥角）、平移（共模锥角）的分配公式；4 输入 3 方程的最小范数配平；P0~P5 测量阶段与判据；与两发的本质区别 | 四喷口每一轮开工的**唯一入口** |
| `four-nozzle-constants.md` | **四喷口可信常数与黑名单（唯一真相源）**：ch0..3 与 inv 配置、四角力臂/质量/TWR（已实测）、P1 残余、四发新增 9 条黑名单、程序版本表 | 写/改四喷口控制环、判断某个数能不能信时 |
| `four-nozzle-handover.md` | **四喷口一页纸交接（2026-11-21 16:15）**：现状（P0/P1 PASS、P2 v2 待上机）、必带常数、控制通道、程序清单、场地与安全、按「下次还会再踩」排序的坑、未解决项、下一步、数据索引 | 换人 / 换会话 / 阶段收尾通读一次 |

## propulsion/ —— 喷管与引擎（3）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `thrust-nozzle.md` | 定向喷管 `ccpe:thrust_nozzle` **母文档**：Sable 点力推力器、1 pN/RPM 标定、REVERSE_SIGN、碰撞盒、排气粒子与遮挡 | 改喷管出力/符号/粒子；它是矢量喷管的母文档 |
| `advanced-thrust-nozzle.md` | **矢量喷管 `ccpe:advanced_thrust_nozzle`（定稿）**：枢轴 `(8,5,8)px`、15° 锥角、活塞杆 = body 子件、BER 渲染链、物理体频道、Lua `setDirection/setThrustScale/setInverted/getForcePoint`（施力点→力矩）、**2 pN/RPM** | 改矢量喷管的倾斜/活塞/频道/GUI/Lua，或为别的方块做「物理体频道 + 外设」 |
| `engine-module.md` | 引擎模块 `aero_engine`（P0~P10）：engine_core 组网、零缓存抽储罐、燃烧室、冷却过热约束、末段 Lua 控制 | 改引擎组网/燃料/冷却/`ss.getEngine()` 行为 |

## peripherals/ —— 传感器、频道、通信与机械件（9）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `my_aero_sensor.md` | INS `ccpe:ins` 实现记录：部件层级 test(Y)→gimbal(Z)→compass(X)、逆变换链一致性、`randomNudge` 坑、`PIVOT_DROP`、Lua 读数现状 | 改 INS 动画/层级/渲染/扰动 |
| `my_sensor_system.md` | `ccpe.sensor_system` 方案 + 实施补充：FMC 应力读数、静压孔/皮托管所有者、门控规则 | 改传感器 Lua API / 门控 / 应力读数 |
| `short-range-link.md` | 短程信号链接器：**物理体作用域频道注册表**、链上共享加载、`ccpe.link`、频道唯一性 | 任何「频道 = 同物理体地址」的机制都建在它上 |
| `cc-rednet-communication.md` | **两台电脑通信**：rednet API 速查、频道常量、message 可序列化约束、worker/caller 脚本对、坑清单 | 要 A 让 B 执行动作时（星舰火箭↔夹具就用这套） |
| `my_bearing.md` | `aero_bearing` 航空轴承：轴向应力输入 + `RotaryConstraint` 物理驱动、锁定模式、`transmission_peripheral` 对齐 | 改轴承物理/锁定/传动 |
| `servo_bearing.md` | `servo_bearing`：Create contraption 实体、运动学、角度刚性、无惯性；与 `aero_bearing` 的分工 | 要精确角度控制、不要物理反馈时 |
| `servo-mode.md` | 传动外设**舵机模式**：服务器权威 ±180° 定位、段式状态机 + re-aim + flicker 门控 | 改舵机角度定位/段推进/flicker |
| `wheel-axle-design.md` | 从动轮悬架 `trailing_wheel` 设计与实现 | 改悬架/从动轮 |
| `ballast-weight-block.md` | 可调配重 `ccpe:ballast` 可行性方案 | 改配重/质量预算 |

## cockpit/ —— 控制台、Monitor、屏幕与 GUI（9）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `control-desk-grid-slot.md` | **controlDesk 桌顶棋盘网格自由放置系统**：joystick_2 / throttle / monitor_2 / throttle_2 接入、占地与唯一合法位、预览盒、命中、拆除、**添加新模块 checklist** | 加/改 controlDesk 控件模块**先读它** |
| `control-desk-seat.md` | controlDesk × Create 坐垫联动、输入租约、各控件输入字段与动画 | 改坐垫输入/控件动画 |
| `knob-interaction.md` | Knob 旋转交互数据流（拖拽、角度同步、音效） | 改旋钮交互 |
| `gui-infrastructure.md` | Monitor GUI 基础设施要点 | 改现有控件实现细节 |
| `monitor-slab.md` | `monitor_slab` 板式 Monitor：地板/贴墙/贴天花板三形态、网格/命中/放置/渲染/拆除/频道/Lua、遗留问题修复记录 | 改板式监视器 |
| `monitor-state-isolation.md` | 多 Monitor 客户端状态隔离规范（按 `BlockPos` 隔离、Outliner key） | 改交互状态/动画缓存 |
| `screen-render-rework.md` | 屏幕渲染重构需求分析与方案决策 + 实施状态（格子模型） | 改屏幕数据模型/同步/渲染平面 |
| `record_screen_module.md` | 可变尺寸屏幕模块实现记录 | 改屏幕模块数据/渲染/拆装 |
| `record_screen_text.md` | 屏幕字符/矩形渲染：位图字体、UV 环绕、镜像与 `textPolygonOffset` 坑 | 改屏幕文本/图形渲染 |

## rendering/ —— 渲染、兼容与注册（5）

| 文件 | 内容 | 何时读 |
|---|---|---|
| `buffer-source-render-lifecycle.md` | `SuperByteBuffer`「Not building!」崩溃经验、BufferSource 生命周期 | BER/Flywheel 渲染崩溃时先读 |
| `complementary-act-coloured-light.md` | ComplementaryReimagined ACT 彩色光兼容（航行灯补丁） | 改自发光/彩色光兼容 |
| `pitot-selection-box.md` | VoxelShaper 选择框旋转坑（单位混用/基准朝向/Catnip 与原版 X 反向）+ 新增选择框 checklist | 新增或修 6 向 FACING 方块的选择框/碰撞框 |
| `registrate-datagen.md` | Registrate + datagen 新增方块完整流程 + 三坑（lang 覆盖、`.tab(SEARCH)` 崩服、datagen 最小化 mod） | 新增方块/跑 runData/排查创造标签崩溃 |
| `create-schematic-nbt.md` | Create 蓝图 NBT 经验（保存/部署时配置丢失） | 排查 PeripheralExtender / RedstoneTransceiver 在蓝图中的行为 |

## archive/ —— 历史记录（6）

| 文件 | 内容 |
|---|---|
| `9.24.md` | 2024-09-24 日工作记录：controlDesk throttle 穿模修复、Monitor 天花板悬挂（hanging）、monitor_slab 新方块首个方案 |
| `two-nozzle-vehicle.md` | 两喷口（串列）设计基线：坐标系、锥角=桨盘、秩 5 与"锁死轴"推导、`τ_roll` 恒等式、**§12 实测修订** —— 只作历史 |
| `two-nozzle-ROUND.md` | 两喷口本轮工作卡（2026-11-21 收尾状态）：三轴姿态已成立、`m2hover` v4 待上机 |
| `two-nozzle-constants.md` | 两喷口可信常数 + 21 条黑名单：m=11.00、b_f=2.05/b_r=1.95/h=1.32、r\*=0.9562、α/D=−31.7、写预算、门控表 —— **黑名单仍适用于四发** |
| `two-nozzle-evidence.md` | 两喷口归档总账：探针输出、七次实机原始数据与推导 |
| `two-nozzle-handover.md` | 两喷口一页纸交接：程序清单、频道、场地、按「下次还会再踩」排序的坑、数据索引 |

## 维护规则

1. **新文档必须落到某个主题目录**，并在本索引对应表格加一行；不允许平铺在 `memo/` 根下。
2. 文件名**英文短横线、无空格、无中文**（中文路径在 PowerShell / 工具链里有编码坑）。日期型记录进 `archive/`。
3. 单份超过 ~100 KB 的文档（`flip-autopilot.md`）只作检索用，禁止整篇读；`starship15-catch.md` 同理。
4. 移动或改名后必须跑一次引用校验：`grep -rn "memo/" --include=*.md`，逐条确认目标文件存在（含 `.agents/skills/*/SKILL.md`）。
5. 同一结论只在一处维护：手法/常数写各自的 `*-constants.md`（星舰 `starship15-constants.md`、四喷口 `four-nozzle-constants.md`），进度写各自的 `*-ROUND.md` / `four-nozzle-plan.md`，历史证据留各自的归档总账（`starship15-catch.md` / `archive/two-nozzle-evidence.md`）。
