# controlDesk × Create 坐垫联动 + 可安装控件 方案

> 记录 controlDesk 的坐垫联动、可安装控件、配置存储与 Create 蓝图兼容的**设计方案与已确认决策**。
> 参考来源：aeroworks（`references/aeroworks-decompiled/.../content/controls/` 模块/socket 系统）、本项目 Monitor 模块系统、Create 坐垫（`SeatBlock`/`SeatEntity`）、本项目 RedstoneTransceiver 蓝图兼容案例（`create-schematic-nbt.md`）。

## 需求一句话

controlDesk **默认没有控件**（脚踏板/操纵杆），玩家需要**手动安装控件**；玩家坐在 controlDesk 操作侧（facing 方向相邻格）的 Create 坐垫上**自动进入操作模式**（无需再对 controlDesk 手动交互）→ 按键驱动已安装控件（Q/E 踩踏板、WASD 推操纵杆，按键可配置、踏板两种触发模式）→ 控件状态通过 **CC 外设/Lua API** 暴露。

## Create 坐垫机制（查证结果）

来源：`references/Create-mc1.21.1-dev/.../contraptions/actors/seat/SeatBlock.java`

- 坐垫是普通方块 `SeatBlock`（16 色，Create 内部类，非 API）；右键 → 服务端 `sitDown()` 创建 `SeatEntity`，位置固定在坐垫方块中心 `(x+0.5, y, z+0.5)`，`player.startRiding(seat, true)`
- 判定「坐在某坐垫上」：`player.getVehicle() instanceof SeatEntity seat && seat.blockPosition().equals(seatPos)`
- 判定「某格是坐垫」：`create:seats` 方块 tag（`AllBlockTags.SEATS`），不写死类/颜色
- **实测**：坐垫上按 WASD/空格无影响；按潜行会离开坐垫（该键行为必须保留）

## 核心判定（已定案：现查，零持久化）

坐垫格 = `pos.relative(facing)`（固定可算，**不需要**扫描/保存/监听/NBT）

- 判定①坐垫存在：`level.getBlockState(seatPos).is(create:seats)`
- 判定②玩家在操作：骑乘实体是 `SeatEntity` 且 `blockPosition()` 等于 seatPos
- 「操作模式」= ①+②同时成立；客户端、服务端各自独立现查，天然无陈旧状态

## 控件安装系统（新 idea，参考 aeroworks）

- controlDesk 默认无控件；提供控件物品（左踏板/右踏板/操纵杆 item），玩家对 controlDesk 相应安装位使用物品完成安装
- 参考 aeroworks：`ConsoleSocket`（BE + socket 索引 + subPath 定位嵌套模块）/ `ModuleHolder` / `MountedModule`——模块挂载在 BE（服务端权威），配置全部走 C2S payload
- **本项目已有同类链路可复用**：Monitor 模块（`PlaceModulePayload` → `GridState` 记录 → `MonitorRenderer` 渲染 + `ModuleConfigScreen` 配置）——控件安装/卸载/渲染仿照此模式
- 已安装控件存 BE NBT（服务端权威），可被 Create 蓝图保存

## 按键与交互（已确认决策）

- **配置界面**：对准相应元件（已安装的控件）蹲下+右键打开（对齐 Monitor 惯例）
- **按键可配置**：KeyMapping 注册（左踏板/右踏板/操纵杆 W/A/S/D），玩家设定的按键**覆盖已有按键**——自定义 KeyConflictContext（坐垫操作模式激活我们的键、原版 Q 丢物品/E 物品栏/WASD 移动失效；离开坐垫恢复）。实现前先验证 NeoForge `KeyMapping.setKeyConflictContext` 行为
- **潜行键不覆盖**（Create 坐垫按潜行=下车，必须保留）
- **默认按键**：Q=左踏板、E=右踏板、WASD=操纵杆（W 前推 / S 后拉 / A 左摆 / D 右摆）
- **触发模式**：踏板两种（按住式=按住踩/松开抬、切换式=按一下踩住/再按抬起），可配置；操纵杆固定按住式（不适用切换式）
- 按键状态按边沿/持续发送 payload

## 服务端状态（权威）

- BE 新增：`leftPedalDown` / `rightPedalDown` / 操纵杆轴状态（浮点 x,y）；**配置需要持久化**（见下），操作运行状态按需持久化
- payload 处理器：**校验玩家确实坐在关联坐垫上**才更新 BE 状态（防作弊/异常）
- 状态变更 → 同步客户端（`getUpdatePacket` 模式，参考 `MonitorBlockEntity` / `RedstoneTransceiverBlockEntity`）

## 动画（项目第一个动态渲染）

- **踏板：踩下 = 前后平移（不是旋转！）**，Visual（Flywheel）与 BER 两条路径都要支持
- **操纵杆：WASD 方向倾斜、松开回中，最大摆动 30°**
- 客户端缓存目标状态 + 上一状态插值（参考 Monitor `animProgress` 模式）

## CC 外设

- 按项目现有模式接入（参考 `TransmissionPeripheralBlockEntity` 的外设实例 / `MonitorPeripheral` 的 IPeripheral 实现）
- Lua API 初稿：`isLeftPedalDown()` / `isRightPedalDown()` / `getJoystickX()` / `getJoystickY()`（**轴浮点 -1..1**）

## 配置存储与 Create 蓝图兼容

- 需求：配置可保存、可分享、可批量制作 → **必须兼容 Create 蓝图**；配置（已安装控件 + 控件配置，含触发模式/按键绑定）**存 BE NBT**（服务端权威），客户端从 BE 读配置生成运行时按键映射
- 参考项目实际案例 `RedstoneTransceiver`（详见 `create-schematic-nbt.md`，都是踩过的坑）：
  1. 蓝图保存路径走 `saveAdditional`（**不走** `writeSafe`）——运行时字段要在 `saveAdditional` 层排除，光实现 `writeSafe` 没用
  2. BE **必须实现 `getUpdatePacket()`**（quill 保存读的是客户端 BE，否则存出旧配置）
  3. 配置变更后 `sendBlockUpdated` + 先落盘再保存蓝图（自动存档 ~30s 间隔，会回滚未落盘配置）

## 实施顺序（每步可独立验证）

1. 控件安装系统：控件物品 + 安装/卸载 payload + BE 存储 + 蓝图兼容（`getUpdatePacket`/NBT 字段）
2. 判定工具 + 服务端校验骨架（无 UI 效果，可断点验证）
3. 客户端按键监听 + payload 链路（重点验证按键冲突 KeyConflictContext 方案）
4. BE 状态 + 服务端权威更新 + 同步
5. 动画（踏板平移、操纵杆 30° 倾斜）
6. 配置 GUI（自绘背景/控件，蹲下右键对准控件打开；按键重绑定 + 触发模式）
7. CC 外设 + Lua API（Lua 侧验证信号）

## 待确认 / 风险清单

| # | 问题 | 影响 |
|---|---|---|
| 1 | 按键冲突方案需进游戏验证（NeoForge KeyConflictContext 对原版键的实际效果） | 按键 |
| 2 | 按键绑定存 BE 后，多个玩家对同一 controlDesk 的按键习惯冲突如何处理（配置跟随机器 vs 跟随玩家） | 配置 |
| 3 | 控件安装位布局：controlDesk 上哪些位置可装踏板/操纵杆（socket 槽位定义） | 安装系统 |
| 4 | 配置 GUI 的贴图/控件由用户绘制（进行中） | GUI |
| 5 | 踏板平移行程/操纵杆 30° 的具体动画参数 | 动画 |
