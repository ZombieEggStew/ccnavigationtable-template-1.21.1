# monitor_slab 表面 Monitor 网格 + 模块安装方案

> 需求（9.25）：复刻 monitor / monitor_2 的网格模型，能像 monitor 那样安装小零件（button_1 / toggle_switch / knob / screen）。
> 阶段目标：**先简单跑通一遍流程验证可行性（仅地板放置 + BER + 无 Lua）**，之后再添加贴墙/贴天花板变体的模块放置、Lua 等。
> 状态：**基本流程已跑通（用户 9.25 进游戏确认）**：模块/屏幕安装交互、跟随 FACING 旋转、扳手语义全部可用；剩余校准点见「验证结果」。参考来源：monitor_2 完整接入记录见 `memo/control-desk-grid-slot.md`（MonitorGridHost 三件套复用、命中自遮挡坑、px 单位移植坑）。

## 已确认设计（用户拍板）

| 项 | 决定 |
|---|---|
| 网格尺寸 | **14×14**（顶面 16×16 px，四周各内缩 1px → 网格起点 (1,1)px，格=1px），`GridState(14, 14)` |
| 支持的放置形态 | **仅地板（FLOOR）**：面板恒朝上，命中 = 水平面 y=slab 顶面（后续再扩贴墙/贴天花板） |
| 渲染路径 | **只做 BER**：slab 本体继续 blockstate 静态模型，BER 只在顶面叠加模块/屏幕/装饰（后续再考虑 Flywheel） |
| Lua | **本次不做**：BE 实现 `MonitorGridHost` 后，以后只需一行 capability 注册（MonitorPeripheral） |

## 架构选型

走 **Monitor 的「独立方块」模式**（不是 controlDesk 模式）：monitor_slab 是独立方块，照 `MonitorBlockEntity` 这条线 + 复用 **monitor_2 的接入方法**。

**为什么好做**：
1. 服务端/网络/Lua 三件套全部免费：`MonitorGridHost` 接口 + 8 个 payload（`MonitorPacketHandlers.findHost` 按 `instanceof MonitorGridHost` 分发）+ `ModuleHandle` 系列 Lua handle 都已参数化，monitor_2 已验证「新宿主零复制接入」。
2. slab 比 monitor_2 简单：无 22.5° 倾斜、无 yaw/pitch/offset → 命中退化为「射线 vs 轴对齐水平平面」，渲染退化为「顶面平铺」。
3. 渲染类全部共享：`Screen9GridRenderer`（9 宫格/文字）、`ModuleSurfaceRenderer`（旋钮角度/按钮标签）、`ModuleRenderBehavior`（按钮/钮子/旋钮动画）、`MonitorPreloadedModels`（模块模型）。

## 面板几何（北向基准模型空间 px，单一来源放 `MonitorSlabBlockEntity`）

- slab 模型 = 16×16×8 单盒（`models/block/monitor_slab/item.json`），地板放置 y0..8，**顶面 = 世界 y8/16 = pos.y + 0.5**。
- 面板 = 整个顶面（x0..16 / z0..16），网格四周内缩 1px：
  - 网格起点 (1, 1)px；格 (gx, gy) 占 px x∈[1+gx, 2+gx]、z∈[1+gy, 2+gy]。
  - 网格 x 轴 → 模型 x；网格 y 轴 → 模型 z。
- 模块摊平（初始旋转）：button 前脸 −Z → 绕 X **+90°**（−Z→+Y）；toggle/knob 原生平躺（前脸 +Y）不转。竖直锚点按类型：button = 面板+1px（本地 z 0.625..1 沿 −Y 延伸、背面贴面板），toggle/knob = 面板（本地底 y=0）。
- 屏幕 9 宫格/文字复用 `Screen9GridRenderer`：`ScreenPlane` 加默认 `horizontal()=false`，水平面时整体「平移到面板锚点 + 绕 X 旋转」再走现有 XY 绘制（Monitor/monitor_2 默认 false 零影响）。

## 改动文件清单

| # | 文件 | 内容 | 参照 |
|---|---|---|---|
| 1 | `block/MonitorSlabBlockEntity.java`（新） | `implements MonitorGridHost`：懒加载 `GridState(14,14)` + 面板几何常量 + NBT 四路径 + `slabChanged()`=sendBlockUpdated+SyncGridPayload + 客户端注册表维护 | `ControlDeskBlockEntity` monitor_2 段 |
| 2 | `block/MonitorSlabBlock.java`（改） | 加 `EntityBlock`（newBlockEntity/getTicker），父类换 `BaseEntityBlock`（FACE/FACING 本来就自己 add） | `MonitorBlock` |
| 3 | `block/MyModBlockEntities.java`（改） | 注册 `monitor_slab_entity` 绑定 `monitor_slab` | 现有条目 |
| 4 | `client/MonitorSlabHitDetector.java`（新） | 射线 vs 水平面板平面 + 落点在面板内 + 背面剔除 + 遮挡检测**排除自身方块**（monitor_2 自遮挡坑） | `Monitor2HitDetector` |
| 5 | `client/MonitorSlabGridOverlay.java`（新） | 网格线 + 放置预览 + 右键放/拆模块 + 按钮/钮子/旋钮 + 屏幕两点放置 + 扳手拆除 + 打开 `MonitorModuleScreen` + tooltip；payload 原样复用 | `Monitor2GridOverlay` |
| 6 | `client/MonitorSlabClientRegistry.java`（新） | slab 已加载坐标集合 | `MonitorClientRegistry` |
| 7 | `block/MonitorSlabRenderer.java`（新） | BER：顶面叠加模块（ModuleRenderBehavior + MonitorPreloadedModels）+ 9 宫格/文字（Screen9GridRenderer + 顶面 ScreenPlane）+ 装饰（ModuleSurfaceRenderer，KnobDisplaySource.SLAB） | `MonitorRenderer` + `ControlDeskRenderer` monitor_2 段 |
| 8 | `block/Screen9GridRenderer.java`（改） | `ScreenPlane` 加 `horizontal()`；水平面渲染支持 | 本次唯一动共享类的改动 |
| 9 | `CCPeripheralExtenderClient.java`（改） | 注册 MonitorSlabRenderer + overlay 事件 | monitor_2 注册段 |

## 免费获得（零新代码）

8 个 payload 分发、`MonitorModuleScreen` 配置菜单、模块/屏幕共享渲染类、网格状态服务端权威。

## 已知坑（照抄时规避）

1. `getUpdatePacket()` 不能恢复默认实现（code-map 已知边界），NBT 走四路径（saveAdditional/loadAdditional 带 contains 守卫/writeSafe/getUpdateTag，蓝图兼容）。
2. 命中/渲染共用同一组面板常量（渲染与检测严格互逆铁律）。
3. 遮挡检测排除 slab 自身（monitor_2 踩过：面板与碰撞体重叠会被判自遮挡，`ClipContext.COLLIDER` 命中自身方块要放行）。
4. 模块初始旋转符号（+90° X）与水平面 9 宫格旋转方向必须一致；水平面 `ScreenPlane.z()` 需带上凸出偏移，文字 zBase 0.7px 下沉在凸出范围内。
5. 单位：slab 渲染用块单位（对齐 Screen9GridRenderer 现状），**不要**照抄 monitor_2 早期 px 直用（那套已重构为块单位）。
6. 右键冲突：手持模块物品时 overlay 拦截右键走 payload，照抄 `Monitor2GridOverlay`。

## 实施顺序

① BE + 方块 + 注册（gradlew classes 验证）→ ② 客户端注册表 + 命中 → ③ overlay 交互 → ④ BER 渲染 → ⑤ 注册接线 → ⑥ 进游戏验证。

## 验证清单（进游戏）

- [ ] 地板放置 slab，手持模块物品（button/toggle/knob/screen）指向顶面 → 显示 14×14 网格
- [ ] 右键放置模块，位置正确、模型摊在顶面上、凸出方向朝上
- [ ] 按钮按压（含灯带/标签）、钮子切换、旋钮拖拽（角度文字）
- [ ] 屏幕两点放置 → 9 宫格 + 文字渲染在顶面
- [ ] 扳手蹲下右键拆除；方块破坏掉落
- [ ] 存档重进（NBT 四路径）、多 slab 状态隔离
- [ ] 拆除/放置与 MonitorModuleScreen 配置打开正常

## 后续阶段（本次不做）

- 贴墙 / 贴天花板变体的模块放置（命中平面方向按 FACE 变化，几何已预留）
- Lua 外设（MonitorPeripheral capability 注册，约 20 行；届时 slab 频道可经 `pe.getPeripheral(ch)` 寻址）
- Flywheel Visual（性能优化）
- Sable physics_block_properties、合成配方

---

## 实施记录（9.27，配置菜单与信号系统）

### 方案（用户拍板）

- **配置菜单**：右键菜单抄 `ControlDeskConfigScreen`，但**只保留第一行频道滚轮条**（slab 表面模块配置已走 `MonitorModuleScreen`，不需要已安装控件列表）。
- **信号系统**：slab 频道走**全局频道系统**（`GlobalChannelRegistry`，与显示器/传感器共享命名空间，**不是** controlDesk 的物理体作用域空间）；自动分配（-1 → 最小空闲）、冲突顺延、跳过已占用频道的逻辑全部照 `MonitorBlockEntity` / `MonitorMenuScreen`。
- **打开方式（用户拍板，完全照 Control Desk）**：
  - 扳手右键（不蹲下）命中 slab **任意位置（含侧面）** → 打开配置菜单；**先判定右键的是不是表面内容（模块/屏幕）**——是 → 打开对应模块配置菜单（`MonitorModuleScreen`），否 → 打开 `MonitorSlabConfigScreen`。扳手右键**不再旋转 FACING**（`onWrenched` 一律消费，对齐 `ControlDeskBlock`）；空手蹲下右键同样打开。
  - 扳手蹲下右键拆除：**不判定点击面**（去掉 isPanelHit 侧面判断），点击落在表面内容（模块/屏幕）→ 放行 overlay 拆单个；非内容且已装内容 → 禁止整拆提示；光板 → 整拆（BE 数据存物品）。

### 实际落地

| 文件 | 说明 |
|---|---|
| `block/MonitorSlabBlockEntity.java`（改） | 全局频道：`channel`/`occupiedChannels` 字段 + onLoad 注册（-1 自动分配，对齐 Monitor）+ setRemoved 注销 + `getChannel`/`setChannel`（<0 忽略，register 冲突顺延）+ `refreshOccupiedChannels`；NBT 四路径加 `Channel`/`OccupiedChannels`（writeSafe 只存 Channel） |
| `compat/cc/GlobalChannelRegistry.java`（改） | `broadcastRefresh` 加 `MonitorSlabBlockEntity` 分支（slab 占用变化广播给全部全局频道设备，反之亦然） |
| `network/MonitorSlabChannelPayload.java`（新） | client→server 保存 slab 全局频道；处理器加在 `MonitorPacketHandlers`（Monitor 家族） |
| `screen/MonitorSlabConfigScreen.java`（新） | 抄 ControlDeskConfigScreen：192×169 背景 + 频道滚轮条（跳过已占用）+ 完成按钮；无物理体判断（全局频道恒可用）；关闭时发 payload |
| `block/MonitorSlabBlock.java`（改） | `onWrenched` 一律消费（永不旋转）；`useItemOn` 空手蹲下右键消费（服务端也消费）；`onSneakWrenched` 把 `isPanelHit` 改为 **`isSurfaceContentHit`**（点击落点→网格格→查 grid 占用，模块/屏幕都算，不判定侧面）：非内容且光板才整拆 |
| `client/MonitorSlabGridOverlay.java`（改） | **菜单打开移到 `onClientTick`**（抄 ControlDeskPlacementOverlay，基于 `mc.hitResult` → 支持侧面命中）：命中 slab 任意位置 +（扳手普通右键 或 空手蹲下右键）→ 先查独立命中检测是否面板内容（模块/屏幕），是 → 留给 `onRenderLevel` 的 `MonitorModuleScreen`（已验证逻辑），否 → `MonitorSlabConfigScreen`；面板空格由 `onRenderLevel` 兜底打开 |
| lang | 加 `gui.ccpe.monitor_slab.channel_title` |

### 验证清单（进游戏）

- [ ] 扳手右键顶面/侧面（光板/已装内容）→ 打开配置菜单；扳手右键不再旋转 FACING
- [ ] 扳手右键模块 → 打开模块配置菜单（优先于 slab 配置菜单）；扳手右键屏幕 → 屏幕配置菜单
- [ ] 空手蹲下右键（任意位置）→ 打开配置菜单
- [ ] 扳手蹲下右键模块 → 拆单个模块；扳手蹲下右键侧面/空格（光板）→ 整拆；已装内容非内容点击 → 提示先拆模块
- [ ] 频道滚轮滚动跳过已占用频道（与 Monitor / 传感器共用命名空间，互相可见占用）
- [ ] 关闭菜单保存频道；服务端自动分配（新 slab 从 0 起跳过占用）
- [ ] 多 slab 同频道冲突顺延、存档重进频道保留（NBT 四路径）

---

## 实施记录（9.25，基本流程已进游戏验证通过）

### 实际落地（与方案差异）

1. **模块朝向（关键修正）**：核对 blockbench 模型 JSON 后确认——
   - button_1 底座/头部 = XY 竖贴片，**前脸 −Z**（本地 z 0.625..1），摊平用**绕 X +90°**（−Z→+Y）；
   - toggle_switch / knob 底座 = 原生平躺（前脸 +Y、本地底 y=0），**不旋转**。
   - ⚠️ `ControlDeskRenderer.renderDeskTopModules` 的「button 绕 X −90°」是**错误朝向**（正面朝下）+ toggle offsetZ 当水平位移平移 1px——用户已确认这两点，且桌顶小模块功能被 `DESK_TOP_MODULES_ENABLED=false` 禁用、从未真正验证。**不要照抄它**。
2. **模块锚点映射**（monitor 竖面帧 → 顶面）：offsetX→世界 X、offsetY→世界 Z；**offsetZ（屏幕法线微调）在顶面不映射到高度**（用户确认 toggle/knob 会浮起 1px，`py += offsetZ()` 已去掉）；竖直锚点按类型：button = 面板+1px（本地 z 0.625..1 沿 −Y 延伸、背面贴面板），toggle/knob = 面板（本地底 y=0）。
3. **9 宫格水平面**：`Screen9GridRenderer.ScreenPlane` 加 `horizontal()`（默认 false，Monitor/monitor_2 零影响）；水平时「translate(originX, z, originY) + 绕 X +90°」后走现有 XY 绘制（本地 −Z 正面 → 世界 +Y 朝上）；文字 zBase 符号翻转（水平面本地 +Z → 世界 −Y，文字需在面板上方）。
4. **扳手语义**（对齐 Monitor 底座语义 + controlDesk 防误拆）：
   - 顶面命中 → 放行给 overlay 拆单个模块/屏幕；
   - 侧面/底面命中且已装内容 → **禁止整拆**，提示「gui.ccpe.monitor_slab.remove_blocked」（对齐 `desk_remove_blocked`）；
   - 光板 → 整块拆除（BE 数据存进物品，模块不丢）；
   - 顶面命中且已装内容 → 扳手右键**禁止旋转**（放行给 overlay 配置菜单）。

### 验证结果（9.25 用户进游戏确认，基本流程跑通）

**✅ 已验证**：
- [x] 模块/屏幕放置与位置正确（button 正面朝上、toggle/knob 平躺；按钮与屏幕模块位置正常）
- [x] toggle/knob **下沉 1px**（浮起 1px → 去掉 `py += offsetZ()`，offsetZ 在顶面不映射到高度）
- [x] **模块朝向跟随 slab FACING**（正方形面板 + 14×14 网格 90° 旋转不变，命中/放置无需旋转；`facingYRotation` = blockstate y 旋转的负值：north 0/east −90/south −180/west −270）
- [x] **屏幕 9 宫格/文字内容跟随 FACING**（`inPlaneRotationDeg()` + `applyInPlaneRotation` 绕屏幕区域中心转内容、位置不动；`facingInPlaneDeg` = +y）
- [x] **模块旋转枢轴修正**（button/toggle 偏移且随方向不同、knob 正常 → 根因：模型原点不在几何中心——knob 圆盘原点=圆心，button/toggle 原点在角上（足迹中心 = 本地 (0.5,0.5)）；修复：facing 旋转绕**模块足迹中心**（`modulePivotX/Z`：button/toggle = 0.5/16，knob = 0））
- [x] 网格线可见（y=面板 0px 偏移，未 z-fight；若日后出现再把 `GRID_LINE_OFFSET` 提到 0.01）
- [x] 扳手语义：顶面拆单模块/屏幕；已装内容整拆被禁止并提示「请先拆除表面已安装的模块，再拆除板式监视器」；光板整拆保数据；已装内容时顶面扳手右键禁止旋转
- [x] 屏幕两点放置、扳手拆除、配置菜单（MonitorModuleScreen）打开

**⏳ 待后续确认**：
- [ ] 旋钮拖拽方向手感（`atan2(pz−cz, px−cx)` + renderExtra `Axis.YP −anim`；若反了翻转 atan2 符号）
- [ ] 旋钮角度文字 / 按钮标签朝向（内部变换按竖面设计，顶面可能转 90° 或不可见，需要时给 SLAB 单独变换）
- [ ] 存档重进 NBT（四路径）、多 slab 状态隔离

### 改动文件

| 文件 | 说明 |
|---|---|
| `block/MonitorSlabBlockEntity.java`（新） | MonitorGridHost 14×14 + 面板几何常量 + NBT 四路径 + slabChanged 同步 |
| `block/MonitorSlabBlock.java`（改） | 父类换 BaseEntityBlock + EntityBlock + getDrops（模块掉落）+ onSneakWrenched（顶面放行拆单模块/已装内容禁止整拆并提示「gui.ccpe.monitor_slab.remove_blocked」对齐 ControlDeskBlock/侧面底面光板才整拆保数据）+ useItemOn（模块物品消费右键）+ **onWrenched（顶面命中且已装内容 → 禁止旋转，放行给 overlay；`isPanelHit` 与 onSneakWrenched 共用）** |
| `block/MyModBlockEntities.java`（改） | 注册 monitor_slab_entity |
| `client/MonitorSlabClientRegistry.java`（新） | 已加载 slab 坐标集合 |
| `client/MonitorSlabHitDetector.java`（新） | 射线 vs 水平面板平面（FLOOR）+ 背面剔除 + 排除自身遮挡 |
| `client/MonitorSlabGridOverlay.java`（新） | 网格/预览/放置/按压/钮子/旋钮/屏幕/拆除/配置菜单 |
| `block/MonitorSlabRenderer.java`（新） | BER：模块（button +90°/toggle·knob 平放，offsetZ 不映射高度，**facing 旋转绕足迹中心 `modulePivotX/Z` 跟随 FACING**）+ 9 宫格（水平 ScreenPlane + 每帧 `facingInPlaneDeg` 平面内旋转）+ 表面装饰 |
| `block/Screen9GridRenderer.java`（改） | ScreenPlane.horizontal() 水平面支持 + **inPlaneRotationDeg() 平面内旋转（绕屏幕区域中心，内容跟随 FACING，位置不动）** |
| `block/ModuleSurfaceRenderer.java`（改） | 加 KnobDisplaySource.SLAB |
| `CCPeripheralExtenderClient.java`（改） | 注册 MonitorSlabRenderer + MonitorSlabGridOverlay |

免费获得：8 个 payload 分发（`MonitorPacketHandlers.findHost`）、MonitorModuleScreen、共享渲染类。
