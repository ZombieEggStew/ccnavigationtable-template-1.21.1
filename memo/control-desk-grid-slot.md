# controlDesk 棋盘网格自由放置系统（棋盘插槽）

> 记录 controlDesk 桌顶「棋盘网格」自由放置系统的**设计与实现**，作为后续添加新模块（throttle / monitor_2 / 新控件）的参考。
> **背景**：monitor_2 / throttle / joystick_2 原本共用桌体后缘上方插槽（`BACK_SLOT`，整宽一条、互斥安装）——该插槽已**整体移除**，改为在桌顶显示 6×14 的 1px 棋盘网格，模块自由放置（先做显示，放置逻辑逐步落地）。
> 本文描述的状态：**joystick_2 已完整接入**（预览 → 放置 → 存储 → 渲染 → 拆除 → 占用阻挡；**输入检测 + 倾斜动画已接入**，照抄 joystick 模块、配置/轴值独立，见 `memo/control-desk-seat.md`）；**throttle 已完整接入**（占地 14×6 全占桌顶网格 → 唯一合法位 (8,12)，只能 0°/180° 旋转）；**monitor_2 已完整接入**（占地 14×6 全占桌顶网格 → 唯一合法位 (8,12)，预览框高 12，**不面向玩家**只随桌体 FACING 旋转）；**throttle_2 已接入放置/拆卸/静态渲染**（占地 14×6 → 唯一合法位 (8,12)，预览框高 6，安装朝向照抄 throttle 只能 0°/180°，模型旋转中心 (8,0,8)；输入检测/档位动画/配置 GUI/Lua 后续接入）。四者互斥已全部改为纯占地判定。

## 一句话

手持模块物品对准控制台 → 桌顶出现 1px 棋盘网格 → 准星吸附到 1px 格作为**放置中心** → 右键安装，模块渲染在预览盒位置（模型平移到放置位）→ 每个模块记录**占地矩形**，重叠安装被阻挡 → 扳手蹲下右键命中放置盒拆除。

## 坐标约定（北向基准，全系统统一）

- 所有模型/预览/放置坐标均为**北向基准模型空间 px（0..16）**，随桌体 FACING 旋转（绕方块中心 Y，`rotateCenteredDegrees(-facing.getOpposite().toYRot(), UP)` 同约定）。
- 桌体：`x0..16, y0..8, z8..16`（桌顶面 y=8）。桌顶放置区域 = 8 深 × 16 宽（`z8..16`）。
- **棋盘网格**：6×14 格、1px/格、四周内缩 1px → `x1..15, z9..15`（15 条竖线 + 7 条横线，`ControlDeskPlacementOverlay.showTopGrid`）。
- **放置中心** `(placeX, placeZ)`：命中点经 `ControlDeskBlock.snappedBoxCenter` 吸附到 1px 网格整数 px（客户端预览与服务端放置共用同一方法，防偏差）。
- **占地矩形**：中心 ± 半宽（joystick_2 = 4×4 → `FOOTPRINT_HALF=2`；throttle / monitor_2 = 14×6 → `FOOTPRINT_HALF_X=7 / HALF_Z=3`）。**throttle / monitor_2 占地必须完全处于网格内（x1..15 / z9..15）→ 唯一合法放置中心 (8,12)（全占）**。
- **放置位竖直**：**预览盒下沉 1px**（底 `PLACE_Y_BOTTOM=7`，嵌入桌面示意）～ 顶 `PLACE_Y_TOP`（joystick_2=16 高9 / throttle=13 高6 / monitor_2=19 高12）；**模型坐桌面不下沉**（底 `MODEL_PLACE_Y=8` = 桌顶面）。
- **模型平移**：模型在 Blockbench 中的实际位置（中心 `MODEL_CENTER=8`、底座底 `MODEL_BOTTOM_Y=0`）→ 渲染时平移到放置位：`shift = (placeX-8, 8-0, placeZ-8) / 16`（块单位，模型坐桌面 y8）。

## 核心常量（集中在 `ControlDeskBlockEntity`）

| 常量 | 值 | 含义 |
|---|---|---|
| `JOYSTICK_2_FOOTPRINT_HALF` | 2 | joystick_2 占地半宽（px）；预览盒与占用阻挡共用 |
| `JOYSTICK_2_PLACE_Y_BOTTOM` | 7f | joystick_2 **预览盒底 y**（下沉 1px 嵌入桌面示意；模型坐桌面 y8 见 `MODEL_PLACE_Y`） |
| `JOYSTICK_2_PLACE_Y_TOP` | 16f | joystick_2 预览盒顶 y（高 9） |
| `JOYSTICK_2_MODEL_CENTER` | 8f | joystick_2 模型默认中心 x/z（Blockbench 中模型 x6..10 / z6..10 → 8） |
| `THROTTLE_FOOTPRINT_HALF_X / _Z` | 7 / 3 | throttle 占地半宽（14×6 → x±7 / z±3）；预览盒与占用阻挡共用 |
| `THROTTLE_PLACE_Y_BOTTOM` | 7f | throttle **预览盒底 y**（下沉 1px 嵌入桌面示意） |
| `THROTTLE_PLACE_Y_TOP` | 13f | throttle 预览盒顶 y（高 6） |
| `THROTTLE_MODEL_CENTER` | 8f | throttle 模型默认中心 x/z（Blockbench 中模型 x0.99..15.01 / z4.99..11.01 → 8） |
| `THROTTLE_PLACE_X / _Z` | 8 / 12 | throttle **唯一合法放置中心**（14×6 全占网格 x1..15 / z9..15） |
| `THROTTLE_2_FOOTPRINT_HALF_X / _Z` | 7 / 3 | throttle_2 占地半宽（14×6 → x±7 / z±3，与 throttle 同尺寸）；预览盒与占用阻挡共用 |
| `THROTTLE_2_PLACE_Y_BOTTOM` | 7f | throttle_2 **预览盒底 y**（下沉 1px 嵌入桌面示意） |
| `THROTTLE_2_PLACE_Y_TOP` | 13f | throttle_2 预览盒顶 y（高 6，与 throttle 相同） |
| `THROTTLE_2_MODEL_CENTER` | 8f | throttle_2 模型默认中心 x/z（Blockbench 旋转中心 (8,0,8) → 8） |
| `THROTTLE_2_PLACE_X / _Z` | 8 / 12 | throttle_2 **唯一合法放置中心**（14×6 全占网格） |
| `MONITOR_2_FOOTPRINT_HALF_X / _Z` | 7 / 3 | monitor_2 占地半宽（14×6 → x±7 / z±3）；预览盒与占用阻挡共用 |
| `MONITOR_2_PLACE_Y_BOTTOM` | 7f | monitor_2 **预览盒底 y**（下沉 1px 嵌入桌面示意） |
| `MONITOR_2_PLACE_Y_TOP` | 19f | monitor_2 预览盒顶 y（高 12） |
| `MONITOR_2_MODEL_CENTER` | 8f | monitor_2 模型默认中心 x/z（Blockbench 中模型 14×6 居中 → 8，**用户会同步改模型**） |
| `MONITOR_2_PLACE_X / _Z` | 8 / 12 | monitor_2 **唯一合法放置中心**（14×6 全占网格） |
| `MONITOR_2_SCREEN_Z` | 2f | monitor_2 **屏幕表面（case 前脸）z 坐标**（北向基准模型空间 px；case 元素 x2..14 / y1..11 / z2..6 的 z=2 前脸） |
| `MONITOR_2_MODULE_PROTRUDE_PX` | 1f | monitor_2 **表面模块/屏幕锚点相对屏幕面的凸出量**（px）：模块背面本地 z=1px → 锚点 = 屏幕面 − 1px（背面贴屏幕面、整体向外凸 1px）；**模块模型与屏幕 9 宫格/文字共用**（网格线/命中面仍贴屏幕面） |
| `MONITOR_2_SCREEN_X_MIN/_MAX` | 2f / 14f | monitor_2 屏幕面 x 范围（12px 宽） |
| `MONITOR_2_SCREEN_Y_MIN/_MAX` | 1f / 11f | monitor_2 屏幕面 y 范围（10px 高） |
| `MONITOR_2_GRID_WIDTH/_HEIGHT` | 10 / 8 | monitor_2 表面棋盘网格格数（屏幕面 12×10 → 四周各内缩 1px → 10×8，用户定稿） |
| `MONITOR_2_SCREEN_TILT_DEG` | 22.5f | monitor_2 case 前脸绕 x 轴旋转角（**模型内烘焙**，monitor_2.json case 元素 rotation） |
| `MONITOR_2_SCREEN_TILT_ORIGIN_X/Y/Z` | 14f / 4f / 3f | monitor_2 case 旋转原点（Blockbench origin，px） |
| `MODEL_PLACE_Y` | 8f | **模型放置底 y（三个模块共用）= 桌顶面：模型坐于桌面不下沉；仅预览盒下沉 1px（`*_PLACE_Y_BOTTOM=7`）** |
| `JOYSTICK_2_MODEL_BOTTOM_Y` | 0f | 模型底座底 y（joystick_2 / throttle / monitor_2 均 0） |
| `rotationToFace(Direction, Direction)` | 静态方法 | **安装旋转基础公式**：桌体 FACING + 桌→玩家水平方向 → 90° 间隔，让模型 -Z（Blockbench 北向正面）面向玩家：`floorMod(toYRot(facing) - toYRot(toPlayer), 360)`；`toPlayer` 由 `ControlDeskBlock.directionFromDeskTo`（桌体中心→玩家最近基本方向）计算，预览与实装共用 |
| `rotationToFace2(Direction, Direction)` | 静态方法 | **joystick_2 安装旋转**：`rotationToFace` 结果 + 基础 **+90°** 偏移（`JOYSTICK_2_ROTATION_OFFSET=90`，模型默认朝向与「-Z 面向玩家」差 90°，用户定稿）→ `floorMod(rotationToFace + 90, 360)`；预览（ghost）与实装（install）共用 |
| `rotationToFace180(Direction, Direction)` | 静态方法 | **throttle 安装旋转**：`rotationToFace` 结果量化到最近 0°/180°（油门只能 180° 旋转） |

> 改模型位置（Blockbench）后必须同步 `MODEL_CENTER` / `MODEL_BOTTOM_Y`；改模块尺寸（占地/高度）后必须同步 `FOOTPRINT_HALF` / `PLACE_Y_BOTTOM` / `PLACE_Y_TOP`。

## 数据流

```mermaid
flowchart LR
    A[手持 joystick_2 / throttle / monitor_2 + 准星指向控制台] --> B[ControlDeskPlacementOverlay: 桌顶网格 + 放置预览盒 绿/红]
    B --> C[ControlDeskGhostPreviewRenderer: 半透明实物跟随盒子]
    C --> D[右键安装 useItemOn 服务端]
    D --> E[放置中心: joystick_2 命中点吸附 / throttle·monitor_2 恒 (8,12)]
    E --> F[ControlDeskBlockEntity.install: 记录 placeX/Z + 占用检查 + 安装旋转(monitor_2 无)]
    F --> G[NBT PlaceX/Z + getUpdatePacket 同步]
    G --> H[渲染 Visual/BER: 模型平移到放置位 + 绕放置中心旋转(monitor_2 无)]
    F --> I[占用记录 blocksPlacement: 矩形重叠阻挡安装]
    A2[手持扳手 + 准星] --> J[showRemovePreview: 已装模块线框/放置盒]
    J --> K[蹲下右键 onSneakWrenched: hitControlType 命中放置盒 → remove + 掉落]
```

## 各文件职责与关键方法

### `block/ControlDeskBlockEntity.java`（服务端状态权威）
- 字段：`joystick2PlaceX/Z`（int，默认 8）、`throttlePlaceX/Z`（int，默认 8/12）、`monitor2PlaceX/Z`（int，默认 8/12）、`backSlotRotation`（int，默认 0）
- `install(type, placeX, placeZ, toPlayer)`：JOYSTICK_2 / THROTTLE / MONITOR_2 分支记录位置 + `blocksPlacement` 占用检查；JOYSTICK_2 / THROTTLE 按 `toPlayer` 记录安装朝向旋转（`rotationToFace` / `rotationToFace180`），**monitor_2 不记录旋转**（只随桌体 FACING）；PEDAL / JOYSTICK 忽略位置
- `blocksPlacement(cx, cz, halfX, halfZ)`：候选矩形 vs 已装模块占地矩形重叠判定（joystick_2 4×4 + throttle / monitor_2 各 14×6）；**三者互斥已全部改为纯占用判定**（monitor_2 / throttle 同占 (8,12) 全占网格 → 天然互斥）
- `remove(type)`：JOYSTICK_2 重置 (8,8)；THROTTLE / MONITOR_2 重置 (8,12)
- `getJoystick2PlaceX/Z()` / `getThrottlePlaceX/Z()` / `getMonitor2PlaceX/Z()` / `getBackSlotRotation()`
- NBT：`Joystick2PlaceX/Z` + `ThrottlePlaceX/Z` + `Monitor2PlaceX/Z`（四路径全覆盖，蓝图兼容；旧存档缺字段保持默认）
- ~~`rotationFor`（按玩家朝向）已删除~~——throttle / joystick_2 均改用 `rotationToFace` 系列（与桌体 FACING 相关，对任意桌体朝向正确）

### `block/ControlDeskBlock.java`（交互 + 纯数学工具，服务端/客户端共用）
- `useItemOn`（服务端安装）：JOYSTICK_2 → `snappedBoxCenter(pos, FACING, hitResult.getLocation())`；THROTTLE / MONITOR_2 → 恒 (8,12)（monitor_2 不传 toPlayer，无旋转）；失败提示「已安装」/「位置被占用」（`gui.ccpe.control_desk.position_occupied`）
- `onSneakWrenched`（扳手蹲下右键）：`hitControlType` 命中 → `remove` + `Block.popResource` 掉落 + 拆除音效
- `hitControlType`：PEDAL/JOYSTICK 走 `installBounds` 安装位框；JOYSTICK_2 / THROTTLE / MONITOR_2 走各自放置盒
- `joystick2PlaceBox(desk, facing, pos)` / `throttlePlaceBox` / `monitor2PlaceBox` → 世界 AABB（放置盒，拆除命中 + 客户端扳手预览共用）
- `modelToWorld(pos, x, y, z, facing)`：北向基准 px → 世界（私有；`gridWorld` 的纯数学版，无 +0.06 抬高）
- `snappedBoxCenter(pos, facing, click)` → `{cx, cz}`：命中点 → 北向模型坐标 → 吸附整数 px（**预览与放置共用的唯一吸附实现**）
- `directionFromDeskTo(player, pos)`：桌体中心 → 玩家最近水平方向（90° 间隔；joystick_2 / throttle 安装旋转 + ghost 预览共用，防偏差）
- `hitBounds(bounds, click)`：闭区间 + 0.001 容差（不能直接用 `AABB.contains`，半开区间问题）
- `installBounds(type, facing, pos)`：仅 PEDAL（左右两框）/ JOYSTICK 有安装位框；MONITOR_2/THROTTLE/JOYSTICK_2 返回**空列表**（自由放置模块走放置盒）

### `client/ControlDeskPlacementOverlay.java`（客户端预览，ClientTickEvent.Pre，每 tick 重绘）
- `showTopGrid`：手持 throttle / joystick_2 / monitor_2 时桌顶 6×14 白色网格线（1/128 线宽；y=7.1 + `gridWorld` 的 +0.06 → 恰好桌顶面，防 z-fight）
- `showJoystick2Box`：手持 joystick_2 时 4×9×4 绿色盒子（1/64 线宽），被阻挡变红；盒子中心 = `ControlDeskBlock.snappedBoxCenter`
- `showThrottleBox`：手持 throttle 时 14×6×6 绿色盒子（1/64 线宽），**固定显示在唯一合法位 (8,12)**（不跟随准星），被阻挡变红
- `showMonitor2Box`：手持 monitor_2 时 14×6×12 绿色盒子（1/64 线宽），**固定显示在唯一合法位 (8,12)**，被阻挡变红
- `isJoystick2PlacementBlocked` / `isThrottlePlacementBlocked` / `isMonitor2PlacementBlocked`：同类型已装 / `blocksPlacement` 占用重叠（纯占用，无显式互斥）
- `showRemovePreview`：手持扳手时已装模块线框（PEDAL/JOYSTICK 安装位框；JOYSTICK_2 / THROTTLE / MONITOR_2 放置盒），命中变红
- `gridWorld`：北向基准 px → 世界（含 +0.06 y 抬高，仅预览绘制用）

### `client/ControlDeskGhostPreviewRenderer.java`（半透明实物预览，AFTER_BLOCK_ENTITIES）
- PEDAL / JOYSTICK / JOYSTICK_2 / THROTTLE / MONITOR_2 均显示
- JOYSTICK_2 / THROTTLE / MONITOR_2：模型平移到预览盒位（`shift`），JOYSTICK_2 / THROTTLE 安装朝向旋转绕盒子中心、**MONITOR_2 无旋转**，被阻挡时不显示
- 变换链：`R_facing · [T(盒心)·R_install·T(-盒心)] · T(shift)`

### `block/ControlDeskVisual.java`（Flywheel）/ `block/ControlDeskRenderer.java`（BER 回退）
- 安装渲染与 ghost 同变换：`applyPlacement`（Visual，joystick_2 / throttle 各自常量）/ `placedBuffer`（BER） = 平移到放置位 + 绕放置中心旋转；throttle 手柄在放置链后再沿模型空间 x 平移档位位置
- **三处变换必须保持一致**：Visual、BER、Ghost（防预览与实装不一致）

### 变换链（关键，三处统一）
```
M = R_facing · [ T(placeX/16, 0.5, placeZ/16) · R_install · T(-placeX/16, -0.5, -placeZ/16) ] · T(shift)
shift = ( (placeX-8)/16, (8-0)/16, (placeZ-8)/16 )   // 模型坐桌面 y8（MODEL_PLACE_Y），不下沉
```
- `R_facing`：`rotateCenteredDegrees(-facing.getOpposite().toYRot(), UP)`（与桌体底座模型同约定）
- `R_install`：安装朝向旋转，绕**放置中心**转（模型已平移到放置位，绕放置中心转才不甩开；Y 旋转枢轴 y 值无关）
  - joystick_2：`rotationToFace2(facing, 桌→玩家方向)`（90° 间隔，= `rotationToFace` + **基础 +90°**）——让模型 **-Z（Blockbench 北向正面）面向安装时的玩家**后再整体转 90°（模型默认朝向差 90°，用户定稿）；R_facing 已把 -Z 转到桌体 FACING 方向（操作者所在侧），故常规操作位安装 ≈ 90°
  - throttle：`rotationToFace180(facing, 桌→玩家方向)`（**只能 0°/180°**，`rotationToFace` 结果量化到最近 0/180）
  - monitor_2：**无 R_install**（不面向玩家，只随桌体 FACING）
- `T(shift)` 必须是最内层（最后调用）：先于 facing/安装旋转作用于模型空间
- 位置计算：BE 存整数 `placeX/placeZ`；渲染换算 `/16` 成块单位

## 添加新模块参考步骤（checklist）

以「把 joystick_2 的做法复制给新模块 X」为例：

1. **物品**：`MyModItems` 注册 `CONTROL_X`；`MyModCreativeModeTabs` 加入创造模式物品栏；`models/item/x.json` → 用户绘制的物品模型；lang 名称
2. **枚举与状态**：`ControlType` 加 `X`；BE 加 `xInstalled` + 放置字段（若自由放置）+ NBT 四路径（`saveAdditional`/`loadAdditional` 含 contains 守卫/`writeSafe`/`getUpdateTag`）
3. **常量**：BE 加 `X_FOOTPRINT_HALF`（占地半宽）、`X_PLACE_Y_BOTTOM/TOP`（**预览盒**竖直，模型坐桌面用共享 `MODEL_PLACE_Y=8`）、`X_MODEL_CENTER/MODEL_BOTTOM_Y`（Blockbench 中模型实际位置）；改模型后同步
4. **块交互**：`ControlDeskBlock.controlTypeOf` / `controlItem` / `getDrops`；`useItemOn` 里计算放置中心（若中心吸附可直接复用 `snappedBoxCenter`，尺寸不同则扩展）；`installBounds` 返回空（自由放置模块）
5. **安装/拆除**：BE `install` 分支（记录位置 + `blocksPlacement` 检查 + 记录安装朝向旋转）、`remove` 分支（重置位置）；`hitControlType` 加放置盒命中；lang「位置被占用」
6. **预览**：`ControlDeskPlacementOverlay` 加手持时显示（网格加类型；盒子参考 `showJoystick2Box`，尺寸用常量）；`showRemovePreview` 加放置盒显示；`ControlDeskGhostPreviewRenderer` 加实物预览（若需要）并解除 early-return
7. **渲染**：`MyModPartialModels` 加部件；`ControlDeskVisual`（`syncInstance` + 放置变换）+ `ControlDeskRenderer`（放置变换）——三处变换保持一致
8. **占用**：`blocksPlacement` 加 X 的占地矩形判定（半宽/非方形需扩展参数）；**三个自由放置模块（joystick_2 / throttle / monitor_2）的旧互斥已全部移除**（改为纯占用判定）
9. **验证**：预览位置 == 实装位置；四个朝向安装旋转正确；占用阻挡生效；扳手拆除命中放置盒；破坏掉落

## 当前状态与已知边界

- ✅ joystick_2 完整接入：桌顶网格 + 4×9×4 预览盒 + 半透明实物 + 位置存储/渲染 + 4×4 占用阻挡 + 扳手拆除 + **输入检测/倾斜动画**（照抄 joystick，配置/轴值独立，枢轴 (8,1,8)，见 `memo/control-desk-seat.md`）
- ✅ throttle 完整接入：占地 14×6 全占桌顶网格 → 唯一合法位 (8,12) + 14×6×6 预览盒（固定位置）+ 半透明实物 + 位置存储/渲染 + 只能 0°/180° 旋转 + 14×6 占用阻挡 + 扳手拆除
- 🔶 throttle_2 已接入放置/拆卸/静态渲染：占地 14×6 → 唯一合法位 (8,12) + 14×6×6 预览盒（固定位置）+ 半透明实物 + 位置存储/静态渲染（底座+手柄，安装朝向照抄 throttle 只能 0°/180°）+ 14×6 占用阻挡 + 扳手拆除；模型旋转中心 (8,0,8)（Blockbench 单位）；**输入检测 / 档位动画 / 配置 GUI / Lua 后续接入**
- 🔶 throttle_2 输入 + 总距杆动画已接入（见 `memo/control-desk-seat.md`）：写死键 空格=上台 / 左Ctrl=下拉（与 throttle 默认键相同但输入字段独立 `inputThrottle2Up/Down`，`SeatInputPayload` 扩到 13 字段）；数值 = BE `throttle2Angle`（0..+30°，服务端权威，`simulateThrottle2` 每 tick 线性累加 15°/tick、锁存不回正，`getUpdatePacket` 同步）；动画 = 手柄绕枢轴 (4,2,8) 旋转（`Throttle2Motion` 单一实现，Flywheel/BER 指数逼近）
- ✅ monitor_2 完整接入（已进游戏验证）：占地 14×6 全占桌顶网格 → 唯一合法位 (8,12) + 14×6×12 预览盒（固定位置）+ 半透明实物 + 位置存储/渲染 + **不面向玩家**（仅随桌体 FACING）+ 14×6 占用阻挡 + 扳手拆除
- ✅ **monitor_2 表面小 Monitor（完整接入：网格 + 放置 + 交互 + 渲染 + Lua；Lua 全链路已进游戏验证通过）**：
  - **网格**：10×8 格（屏幕面 12×10 内缩 1px，`MONITOR_2_GRID_WIDTH/HEIGHT`），**GridState 已参数化**（构造器传宽高，Monitor 保持 12×10 / monitor_2 用 10×8，`getWidth/getHeight`）
  - **命中**：`Monitor2HitDetector` 独立检测（遍历 `ControlDeskClientRegistry`，facing逆 → shift逆 → case 22.5° 逆，返回 `{pos, facing, distance, screenX, screenY, grid}`，`localToGrid` 10×8 转换）
  - **客户端交互**：`Monitor2GridOverlay`（对齐 `MonitorGridOverlay`）——手持模块物品显示网格 + 放置预览、右键放置模块、按钮按压/钮子切换/旋钮拖拽（屏幕局部坐标直接由命中给出）、屏幕两点放置、扳手蹲下右键拆除、右键模块/屏幕打开 `MonitorModuleScreen` 配置、悬停 tooltip
  - **服务端**：`ControlDeskBlockEntity` 实现 `MonitorGridHost` 接口（`getMonitor2Grid` 懒加载 10×8 + 放置/移除/按压/旋钮/屏幕文本全套方法），NBT 四路径持久化（`Monitor2Grid` tag），`monitor2Changed` 同步（`sendBlockUpdated` + `SyncGridPayload`）
  - **payload 复用**：现有 8 个 Monitor payload（Place/Remove Module、ModulePress、KnobRotate、ModuleConfig、Place/RemoveScreen、SyncGrid）处理器按 pos 处 BE 类型分发（`MonitorGridHost`，Monitor 方块或已装 monitor_2 的 controlDesk 方块）
  - **渲染**：`ControlDeskVisual`（Flywheel）+ `ControlDeskRenderer`（BER）渲染表面模块（复用 Monitor 模块模型/动画，变换 = 放置 + case 22.5° 旋转 + 屏幕面定位 + 模块初始旋转）；**屏幕 9 宫格 + 文字 + 图形由 BER 补画**（Flywheel 无法表达，control_desk 注册改 `neverSkipVanillaRender`，renderSafe 在 Flywheel 可用时只画 monitor_2 屏幕、控件模型由 Visual 画；**随 Lua 测试验证：9 宫格、屏幕字符/自由图形渲染正常**）；**旋钮表面角度/卡位文字与按钮灯带/标签已补上**（对齐 Monitor：Flywheel 可用时 BER 经 `renderMonitor2ModuleDecorations` 补画 `ModuleSurfaceRenderer.renderKnobAngle`/`renderButtonLabel` + `ButtonBehavior.renderIndicator`，动画深度读 `ControlDeskVisual.getModuleAnim`；BER 回退时全量渲染补画角度/标签，灯带由 `renderExtra` 画）
  - **9 宫格/模块表面装饰走共享类**：`ControlDeskRenderer` 与 `MonitorRenderer` 共用 `Screen9GridRenderer`（`ScreenPlane` 接口提供屏幕面起点/z，统一块单位——monitor_2 不再有 px 换算，防「只改一半」类 bug）与 `ModuleSurfaceRenderer`（`KnobDisplaySource.MONITOR_2` 包 `Monitor2GridOverlay.getActiveKnobAngle`/`getHoveredKnobModuleId`）
  - **Lua**：`ControlDeskPeripheral.getModule("monitor")` → `MonitorPeripheral`（type = `"ccpe:monitor_2"`，宿主参数化为 `MonitorGridHost`，方法与 Monitor 外设完全同款）→ `getCellModule(x,y)` / `getModule(id)` 返回模块/屏幕 handle（复用 `ModuleHandleRegistry`；`ModuleHandle` 系列绑定 `MonitorGridHost` 接口，Monitor 与 monitor_2 共用同一套 handle）；另有快捷入口 `getMonitor2Module(id)` / `getMonitor2CellModule(x,y)`
- ✅ **monitor_2 命中检测对齐 Monitor 独立命中**：`Monitor2HitDetector` 不依赖原版 `mc.hitResult`（monitor_2 屏幕在桌面碰撞体上方，准星瞄准屏幕时原版可能 MISS），遍历 `ControlDeskClientRegistry`（客户端已加载控制台坐标注册表，`ControlDeskBlockEntity.onLoad/setRemoved/onChunkUnloaded` 维护）枚举候选，用玩家视线射线 + monitor_2 屏幕面实时变换（facing逆 → shift逆 → case 22.5° 逆，严格互逆于 `monitor2World`）做屏幕面板正面求交 + 背面剔除 + **落点屏幕面范围检查（`isOnScreen`，对齐 Monitor 的 `isOnPanel`）** + 遮挡检测（`ClipContext.COLLIDER`，**排除 controlDesk 自身方块**），取最近命中；Sable 子次元兼容（射线回投 plot 坐标）。**修复记录见文末「monitor_2 命中判定两处缺陷」**
- 三个自由放置模块互斥已全部改为纯占地判定（monitor_2 / throttle 同占 (8,12) → 天然互斥；joystick_2 网格内也与两者重叠）——throttle_2 与 throttle / monitor_2 同为 14×6 全占 (8,12)，天然互斥
- 网格线是纯显示层（Outliner），逻辑全部基于放置中心 + 占地矩形
- 安装位置 = 服务端对**右键命中点**吸附（与客户端准星同射线）；多玩家极端场景如需精确可控，可改客户端发 payload（对齐 Monitor 的 PlaceModulePayload 模式）
- **预览盒下沉 1px、模型坐桌面**：预览盒/拆除盒底 y7（嵌入桌面示意），**模型实装与 ghost 底 y8（坐于桌面不下沉，`MODEL_PLACE_Y`）**；网格线在桌顶面（y8，+0.06 防 z-fight），不下沉
- 吸附中心 = 盒子中心（居中吸附）；如需 monitor 式「角落吸附」（准星=模块左上角），改 `snappedBoxCenter` 的取整方式即可
- 旋转只能 0°/180° 的模块（throttle）：放置中心必须让占地矩形在 0° 与 180° 下重合（中心对称矩形，如 14×6 中心在 (8,12)）——否则两个角度占地不同，占用判定需按旋转区分
- monitor_2 模型需在 Blockbench 中同步改为 14×6 居中 (8,8)（当前模型 12×8 是过渡态；代码按 MODEL_CENTER=8 设计）

---

## ⚠️ monitor_2 屏幕坐标单位约定（移植类 bug 教训，改代码前必读）

**monitor_2 的屏幕渲染代码（`ControlDeskRenderer.renderMonitor2Screens` / `Monitor2GridOverlay`）全部使用「北向基准模型空间 px」单位，其中 1 格 = 1px。** 这与正式 Monitor（`MonitorRenderer`，块单位）不同，**不要照抄 monitor 的换算**。

### 单位速查表

| 量 | monitor（块单位，勿直接照抄） | monitor_2（px 单位） |
|---|---|---|
| 屏幕起点 | `scrX = (SCREEN_X_MIN + GRID_INSET + minX) / 16` | `scrX = MONITOR_2_SCREEN_X_MIN + 1 + scr.minX()` |
| 屏幕宽 | `scrW = width * cellSize`（= width/16 块） | `scrW = scr.width()`（**格 = px，直接相等**） |
| 屏幕高 | `scrH = height * cellSize` | `scrH = scr.height()` |
| 边框 | `borderSize = cellSize`（1/16 块） | `borderSize * 16f = 1px` |
| 中央面板 scale | `scale(innerW / cellSize, ...)`（innerW 块） | `scale(innerW, innerH, 1)`（**模型 1px 宽 → scale = px 数**） |
| 转块（渲染） | 已为块单位，直接用 | `translate(px/16, py/16, pz/16)` |

> `GridState.ScreenRegion` 的 `width()/height() = maxX-minX+1`，单位是**格**。monitor_2 上 1 格 = 1px，**宽高直接当 px 用，绝不能 ×16f**（×16 会把整个 9 宫格放大 16 倍、四角飞出屏幕）。

### 2026-08 实际踩坑记录（9 宫格布满天空事件）

- **症状**：monitor_2 上放置 screen 模块后，9 宫格模型错乱、四角/边/中心散落到屏幕外很远（用户描述「碎片布满天空」）；模块（button/toggle/knob）位置正常。
- **根因**：`renderMonitor2Screens` 里 `scrW = scr.width() * 16f`——移植 monitor 的块单位公式时只改了一半：`minX/minY` 已换成 px 单位，但 `width/height` 沿用了「× cellSize」的思路却错写成 `×16f`，宽高被放大 16 倍。中央面板 scale 同样残留 `/16f`。
- **教训**：
  1. **移植类 bug 的典型特征 = 只改一半**：同一个函数里 `minX/minY` 与 `width/height` 必须用同一套单位，逐行核对换算系数（/16、×16f、cellSize）。
  2. **「右下角正常」是误导信号**：16 倍错位下个别部件（右下角）恰好落在可见区，会让人误判成「旋转/朝向问题」，实际是整体坐标错。
  3. **第一时间加锚点可视化 debug**：`Monitor2GridOverlay.DEBUG_SCREEN_PATCH`（现为 false）可把 9 宫格各部件理论锚点画成彩色十字线（四角红/绿/蓝/黄、边青、中心品红），与实渲染对比即可区分「坐标错 vs 旋转错 vs 模型错」——比反复改数值快得多。
  4. **memo 的 ✅ 只代表「代码已接入」，不代表「进游戏验证过」**：monitor_2 表面小 Monitor 标 ✅ 时 9 宫格渲染从未真正核对，导致排查初期默认「这块没问题」、优先级降低。

### 2026-08 实际踩坑记录（monitor_2 命中判定两处缺陷：判定蔓延天空 + 面前被自遮挡）

- **症状**：① 视角离开 monitor_2 屏幕（准星移开/看向天空）后，屏幕面网格/预览判定仍不消失，命中判定「向左上角蔓延、布满天空」；② 站在 monitor_2 面前的一小片范围内，命中判定被阻挡（点不中屏幕）。
- **根因**：
  1. **缺落点范围检查**：`Monitor2HitDetector.intersectScreen` 只与 z=2 的**无限屏幕平面**求交（d_z>0 即命中），返回落点 (sx, sy) 后**没有检查是否落在屏幕面 x2..14 / y1..11 内**。而正式 Monitor 在 `MonitorHitDetector` 里有 `MonitorBlock.isOnPanel(sx, sy)` 检查（`if (!isOnPanel(sx, sy)) continue;`）。monitor_2 移植时漏了这一步 → 视角移开后射线仍与平面相交于屏幕外很远处，hit 非空，overlay 继续显示。
  2. **遮挡检测自遮挡**：`isOccluded` 用 `ClipContext.COLLIDER`，而 monitor_2 的屏幕是 controlDesk **桌体的一部分**（屏幕面 z2..6 与桌体碰撞体 z8..16 同处一个方块内）——玩家从正面看屏幕时射线会先穿过**桌体自身的碰撞体**，被判为「被遮挡」→ 面前小范围点不中。正式 Monitor 的屏幕是独立方块、底座碰撞体（y0..2px）远低于屏幕（y3..15px）所以不会自遮挡，monitor_2 不能照抄这个假设。
- **修复**（`client/Monitor2HitDetector.java`）：
  - 新增 `isOnScreen(sx, sy)`（屏幕面 x2..14 / y1..11 闭区间），`find` 里求交成功后先检查，落点在屏幕面外 → 不算命中（对齐 Monitor `isOnPanel`）。
  - `isOccluded` 增加 `deskPos` 参数：`level.clip` 命中 `deskPos`（controlDesk 自身方块）→ 不算遮挡；其它方块（墙/箱子）仍正常遮挡。
- **教训**：
  1. **「射线与平面求交」≠「命中」**：平面求交只给落点，命中判定必须再检查落点是否在目标区域内（`isOnPanel`/`isOnScreen` 等价物）。漏掉 = 判定蔓延到无限远。
  2. **自遮挡假设不能照抄**：Monitor 的「COLLIDER 不会自遮挡」成立是因为其底座碰撞体在屏幕下方；monitor_2 屏幕与桌体碰撞体重叠，必须**显式排除载体方块**。
  3. 调试日志（`Monitor2HitDetector.DEBUG_TRACE` / `Monitor2GridOverlay.DEBUG_HIT`，现为 false）可分别看到「求交失败 / 落点屏幕面外 / 被遮挡」与命中点十字/屏幕边界框，排查命中类问题先开它们。
