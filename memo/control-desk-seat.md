# controlDesk × Create 坐垫联动 + 可安装控件 方案

> 记录 controlDesk 的坐垫联动、可安装控件、配置存储与 Create 蓝图兼容的**设计方案与已确认决策**。
> **阶段一（控件安装系统）✅ 已实现；阶段二（模块设置菜单 + 按键绑定 UI）✅ 已实现**；坐垫联动 / 按键驱动 / 动画 / CC 外设 / 配置保存待实施。
> 参考来源：aeroworks（`references/aeroworks-decompiled/.../content/controls/` 模块/socket 系统 + ModuleScreen 按键捕获）、本项目 Monitor 模块系统、Create 坐垫（`SeatBlock`/`SeatEntity`）、本项目 RedstoneTransceiver 蓝图兼容案例（`create-schematic-nbt.md`）。

## 需求一句话

controlDesk **默认没有控件**（脚踏板/操纵杆），玩家需要**手动安装控件**（已实现）；玩家坐在**任意坐垫**上（该坐垫东南西北紧邻 1 格内存在至少一个 controlDesk）→ 自动进入操作模式（无需手动交互）→ 按键**广播驱动四邻所有联动的 controlDesk** 的已安装控件（Q/E 踩踏板、WASD 推操纵杆，按键可配置、踏板两种触发模式）→ 控件状态通过 **CC 外设/Lua API** 暴露。

## Create 坐垫机制（查证结果）

来源：`references/Create-mc1.21.1-dev/.../contraptions/actors/seat/SeatBlock.java`

- 坐垫是普通方块 `SeatBlock`（16 色，Create 内部类，非 API）；右键 → 服务端 `sitDown()` 创建 `SeatEntity`，位置固定在坐垫方块中心 `(x+0.5, y, z+0.5)`，`player.startRiding(seat, true)`
- 判定「坐在某坐垫上」：`player.getVehicle() instanceof SeatEntity seat && seat.blockPosition().equals(seatPos)`
- 判定「某格是坐垫」：`create:seats` 方块 tag（`AllBlockTags.SEATS`），不写死类/颜色
- **实测**：坐垫上按 WASD/空格无影响；按潜行会离开坐垫（该键行为必须保留）

## 核心判定（已定案：现查，零持久化）

**以坐垫为中心**（不依赖 controlDesk 的朝向）：

- 判定①联动存在：坐垫四邻（N/E/S/W 紧邻 1 格）内存在至少一个 controlDesk——这些 controlDesk **全部进入联动**，最多 4 个
- 判定②玩家在操作：`player.getVehicle() instanceof SeatEntity seat && seat.blockPosition().equals(seatPos)`
- 「操作模式」= ①+②同时成立；客户端、服务端各自独立现查，天然无陈旧状态

**联动目标 = 坐垫四邻的所有 controlDesk（广播）**：玩家按键时，四邻每个 controlDesk 的对应控件一起响应；没安装对应控件的 controlDesk 自动忽略。不需要选定目标。

## 总体数据流

```mermaid
flowchart LR
    P[玩家坐上坐垫] --> C[客户端判定: 坐垫四邻有 controlDesk + 玩家骑乘]
    C --> K[按键监听 KeyMapping]
    K -->|按键状态| Pay[SeatInputPayload 客户端→服务端: 坐垫pos + 控件输入]
    Pay --> S[服务端校验: 玩家确实坐在该坐垫 + 坐垫四邻确实有这些 controlDesk]
    S --> BE[四邻每个 ControlDeskBlockEntity 状态: 左右踏板/操纵杆]
    BE -->|状态同步| A[客户端动画: 踏板平移/操纵杆倾斜]
    BE --> API[CC 外设 IPeripheral → Lua API]
```

## 控件安装系统（✅ 已实现）

### 实现细节

- **控件物品**：`item/MyModItems.java` 注册 `CONTROL_PEDAL`（"pedal"）/ `CONTROL_JOYSTICK`（"joystick"），已入创造模式物品栏；物品栏模型 parent 到 `models/block/pedal/pedal_item`、`models/block/joystick/joystick_item`（用户绘制）
- **BE 存储**（`ControlDeskBlockEntity`）：`ControlType` 枚举（PEDAL 一对 / JOYSTICK）+ `install`/`remove`/`isInstalled`；NBT 字段 `PedalInstalled`/`JoystickInstalled`；实现 `saveAdditional`/`loadAdditional`/`getUpdateTag`/`getUpdatePacket`/`writeSafe`（`PartialSafeNBT`）→ Create 蓝图兼容三件套（见下）
- **交互**（`ControlDeskBlock`）：
  - 手持控件物品右键 → 服务端安装（非创造消耗 1 个；已装提示 `gui.ccpe.control_desk.already_installed`）
  - 扳手蹲下右键 → 按点击位置拆除对应的**单个**模块并掉落物品（`onSneakWrenched`）；点击不在安装位时不拆；光桌（无模块）走 `IWrenchable` 默认拆方块
  - `getDrops` 覆写 → 方块被破坏（任何方式）时已装控件随掉落
- **安装预览**（`client/ControlDeskPlacementOverlay`，已注册）：手持控件物品 + 准星指向 controlDesk（原版 `mc.hitResult`）→ Catnip Outliner 在安装位显示预览框，绿=可装 / 红=已装；每 tick 重新 show，离开/换物品自动消失
  - 安装位 AABB = `ControlDeskBlock.installBounds(type, facing, pos)`（北向基准 shape + `VoxelShaper` 随 FACING 旋转；PEDAL 显示左右两个框）；调整位置改 `ControlDeskBlock` 顶部的 `*_SHAPE` 常量
- **渲染**：`ControlDeskVisual`（Flywheel）+ `ControlDeskRenderer`（BER 回退）按 BE 安装状态叠加，渲染顺序 **底座 → 本体**：
  - PEDAL → `pedal_base`（一个模型含左右双底座）+ `pedal`（左）+ `pedal_right`（右）
  - JOYSTICK → `joystick_base` + `joystick`
  - PartialModel 定义在 `MyModPartialModels`（`CONTROL_DESK_PEDAL*`/`CONTROL_DESK_JOYSTICK*`）
- **选择框/碰撞箱**：`ControlDeskBlock.SHAPE` 单块 `[0,0,8]~[16,8,16]`（对应底座模型），`getShape` 同时承担选择框与碰撞箱，安装控件不改变

### 踩坑经验（重要）

1. **Flywheel `TransformedInstance` 每帧必须 `setIdentityTransform()`**：`translate` 是**累加语义**，不重置会导致模型每帧漂移出视野，表现为「安装后不渲染」。Create 惯例（`BlazeBurnerVisual` 等）每帧 `setIdentityTransform().translate(...)...setChanged()` 链式设置。
2. **beginFrame 里动态 `createInstance()`/`delete()` TransformedInstance 是官方支持**（`BlazeBurnerVisual` 火焰/护目镜/帽子/杆子的先例），可以按状态动态增删实例。
3. **Flywheel `PartialModel` 自动注册**（`PartialModelEventHandler` 在 `ModelEvent.RegisterAdditional` 遍历 `PartialModel.ALL` 注册、`BakingCompleted` 填充），**无需手动注册**；移动模型文件路径不影响加载，模型缺失会烘焙成 missing（渲染为空）。
4. **控件模型拆分**：本体与底座分开建模（`pedal`/`pedal_right`/`pedal_base`、`joystick`/`joystick_base`），渲染需**分别挂 PartialModel**，别漏底座。

## 模块设置菜单（✅ 已实现）

### 打开方式

- **扳手普通右键（不蹲下）** 或 **空手蹲下右键**，准星命中已安装模块（安装位 AABB）→ 打开对应控件设置菜单（操纵杆 `JoystickModuleScreen` / 脚踏板 `PedalModuleScreen`）
- **扳手蹲下右键** → 拆除命中的模块（服务端 `onSneakWrenched`，掉落物品）；客户端 overlay 不拦截此组合（不打开菜单，让右键事件传到服务端）
- 实现分层（对齐 Monitor 模式，Block 双端加载不引用 Screen）：
  - `ControlDeskBlock.onWrenched`：命中已装模块 → 消费右键（**不旋转**）；未命中 → `IWrenchable.super`（保留扳手旋转）
  - `ControlDeskBlock.useItemOn`：空手蹲下命中 → 消费右键
  - `client/ControlDeskPlacementOverlay`：右键**边沿**检测（`useDown && !lastUseDown` 防连发）+（扳手 或 空手蹲下）→ 按命中类型打开 `JoystickModuleScreen` / `PedalModuleScreen`

### 控件设置菜单（JoystickModuleScreen / PedalModuleScreen）

- 两屏幕均继承 `AbstractMonitorScreen`；背景复用 MonitorModuleScreen（`MyUIElements.BACKGROUND` 192×169 + 标题控件名）；`ControlDeskPlacementOverlay` 按命中控件类型分发
- `JoystickModuleScreen`（操纵杆）布局（自上而下）：① 前后键位绑定条（W/S，默认 w/s）② 前后轴设置条 `DoubleScrollValueBar`（左=回正时间 icon RECOVER 默认 20 tick 范围 0..100；右=档位模式 ToggleButton icon=INDEX 默认 4 档范围 1..8）③ 左右键位绑定条（A/D，默认 a/d）④ 左右轴设置条（同上结构）；`PedalModuleScreen`（脚踏板）：双按键绑定条 左踏板/右踏板（PEDAL_LEFT_UP / PEDAL_RIGHT_UP）
- **操纵杆配置已全部持久化**：BE NBT（两轴回正时间 `JoystickReturnTime`/`JoystickReturnTimeYaw` + 两轴档位模式 `GearModePitch`/`GearCountPitch`/`GearModeYaw`/`GearCountYaw` + 四向按键 `JoystickKeyUp/Down/Left/Right`，旧存档缺失字段时保持默认）+ `saveAdditional`/`loadAdditional`/`writeSafe`/`getUpdateTag` 四路径 + `getUpdatePacket` 同步；屏幕打开时读客户端 BE 初始化、`onClose` 经 `ControlDeskConfigPayload`（pos + 两轴回正时间 + 两轴档位开关/档位数 + 4 键，共 11 字段）→ 服务端 setter（`setJoystickReturnTime`/`setJoystickReturnTimeYaw`/`setGearConfig`/`setJoystickKeys`）

### DoubleInputBar（双按键绑定条，`foundation/gui/widget/`）

- 左右两个按键槽位（命中区 `HIT_X_1=45`/`HIT_X_2=123`/`HIT_W=47`），各带图标 + 按键名显示（槽位内居中）
- **按键捕获（参考 aeroworks ModuleScreen）**：左键点击槽位进入捕获 → 键盘键 `InputConstants.getKey(keyCode, scanCode).getName()` / 鼠标键 `Type.MOUSE.getOrCreate(button).getName()` 均可绑定 → ESC(256) 取消；右键点击槽位清除绑定
- 显示：未绑定 →「未绑定」（`bind_unbound`）；捕获中 → `> 内容(仅内容下划线) <` 居中，颜色不变
- 音效：进入捕获/清除 `UI_BUTTON_CLICK`（aeroworks playUiClick 风格）、改键成功 `NOTE_BLOCK_HAT`（ScrollValueBar 风格）
- tooltip「左键绑定 右键清除」（`bind_tip`）；完成回调 `onBindCaptured(side, keyName)`（side 0=左 1=右，空串=清除）

## 按键与交互（部分实现，坐垫驱动待实施）

- **配置界面**：✅ 已实现（扳手右键 / 空手蹲下右键打开模块菜单，`DoubleInputBar` 按键捕获）；按键保存到 BE 待接入
- **按键可配置**：KeyMapping 注册（左踏板/右踏板/操纵杆 W/A/S/D），玩家设定的按键**覆盖已有按键**——自定义 KeyConflictContext（坐垫操作模式激活我们的键、原版 Q 丢物品/E 物品栏/WASD 移动失效；离开坐垫恢复）。实现前先验证 NeoForge `KeyMapping.setKeyConflictContext` 行为
- **潜行键不覆盖**（Create 坐垫按潜行=下车，必须保留）
- **默认按键**：Q=左踏板、E=右踏板、WASD=操纵杆（W 前推 / S 后拉 / A 左摆 / D 右摆）
- **按键目标**：**广播**给坐垫四邻所有联动的 controlDesk（没装对应控件的自动忽略）
- **触发模式**：踏板两种（按住式=按住踩/松开抬、切换式=按一下踩住/再按抬起），可配置；操纵杆固定按住式（不适用切换式）
- 按键状态按边沿/持续发送 payload

## 服务端状态（待实施）

- 每个联动的 controlDesk BE 各自维护：`leftPedalDown` / `rightPedalDown` / 操纵杆轴状态（浮点 x,y）；**配置需要持久化**（见下），操作运行状态按需持久化
- payload 处理器：**校验玩家确实坐在该坐垫上 + 该坐垫四邻确实存在这些 controlDesk** 才更新对应 BE 状态（防作弊/异常）
- 状态变更 → 同步客户端（`getUpdatePacket` 模式，已就绪）

## 动画（待实施，项目第一个动态渲染）

- **踏板：踩下 = 前后平移（不是旋转！）**，Visual（Flywheel）与 BER 两条路径都要支持
- **操纵杆：WASD 方向倾斜、松开回中，最大摆动 30°**
- 客户端缓存目标状态 + 上一状态插值（参考 Monitor `animProgress` 模式）

## CC 外设（待实施）

- 按项目现有模式接入（参考 `TransmissionPeripheralBlockEntity` 的外设实例 / `MonitorPeripheral` 的 IPeripheral 实现）
- Lua API 初稿：`isLeftPedalDown()` / `isRightPedalDown()` / `getJoystickX()` / `getJoystickY()`（**轴浮点 -1..1**）

## 配置存储与 Create 蓝图兼容

- 需求：配置可保存、可分享、可批量制作 → **必须兼容 Create 蓝图**；配置（已安装控件 + 控件配置，含触发模式/按键绑定）**存 BE NBT**（服务端权威），客户端从 BE 读配置生成运行时按键映射
- 参考项目实际案例 `RedstoneTransceiver`（详见 `create-schematic-nbt.md`，都是踩过的坑）：
  1. 蓝图保存路径走 `saveAdditional`（**不走** `writeSafe`）——运行时字段要在 `saveAdditional` 层排除，光实现 `writeSafe` 没用
  2. BE **必须实现 `getUpdatePacket()`**（quill 保存读的是客户端 BE，否则存出旧配置）
  3. 配置变更后 `sendBlockUpdated` + 先落盘再保存蓝图（自动存档 ~30s 间隔，会回滚未落盘配置）
- **阶段一已按此实现**：BE 的控件安装状态 NBT 持久化 + `getUpdatePacket` + `writeSafe` 全部就位
- **操纵杆配置已全部持久化**：两轴回正时间 + 两轴档位模式（开关 + 档位数，默认关 / 4 档）+ 四向按键（默认 w/s/a/d）存 BE NBT 四路径全覆盖；`JoystickModuleScreen` 打开时读客户端 BE 初始化、`onClose` 经 `ControlDeskConfigPayload`（11 字段）→ 服务端 setter（`notifyChange` 同步）
- **待接入**：脚踏板按键绑定配置 + 触发模式（`onBindCaptured` → BE NBT）

## 实施顺序

1. ✅ 控件安装系统：控件物品 + 安装/卸载交互 + BE 存储 + 蓝图兼容 + 安装预览 + 叠加渲染（含踩坑经验）
2. ⏳ 判定工具 + 服务端校验骨架（坐垫四邻联动 + 玩家骑乘判定，无 UI 效果，可断点验证）
3. ⏳ 客户端按键监听 + payload 链路（重点验证按键冲突 KeyConflictContext 方案）
4. ⏳ BE 状态 + 服务端权威更新 + 广播同步
5. ⏳ 动画（踏板平移、操纵杆 30° 倾斜）
6. 🔶 配置 GUI：✅ 菜单背景 + 双按键绑定条 + 双滚轮条（回正/档位）+ 操纵杆全部配置持久化 已完成；⏳ 脚踏板按键绑定/触发模式配置 + 其余控件
7. ⏳ CC 外设 + Lua API（Lua 侧验证信号）

## 待确认 / 风险清单

| # | 问题 | 影响 |
|---|---|---|
| 1 | 按键冲突方案需进游戏验证（NeoForge KeyConflictContext 对原版键的实际效果） | 按键 |
| 2 | 按键绑定存 BE 后，多个玩家对同一 controlDesk 的按键习惯冲突如何处理（配置跟随机器 vs 跟随玩家） | 配置 |
| 3 | 按键配置保存链路（`DoubleInputBar.onBindCaptured` → `ControlDeskBlockEntity` NBT + `getUpdatePacket`/`writeSafe` 蓝图兼容）待实施 | 配置 |
| 4 | 踏板平移行程/操纵杆 30° 的具体动画参数 | 动画 |
| 5 | 广播语义：坐垫四邻多个 controlDesk 同时响应时，各自控件安装情况不同（未安装的忽略）——需确认无额外要求 | 联动 |
| 6 | 安装位固定为北侧 z0..8 三块（左踏板 x11..16 / 操纵杆 x5..11 / 右踏板 x0..5），`ControlDeskBlock` 的 `*_SHAPE` 常量可调——确认当前位置满意 | 安装位 |
| 7 | 触发模式（按住式/切换式）配置 UI 待做 | 配置 GUI |
