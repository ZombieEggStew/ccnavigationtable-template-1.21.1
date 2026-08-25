# controlDesk 棋盘网格自由放置系统（棋盘插槽）

> 记录 controlDesk 桌顶「棋盘网格」自由放置系统的**设计与实现**，作为后续添加新模块（throttle / monitor_2 / 新控件）的参考。
> **背景**：monitor_2 / throttle / joystick_2 原本共用桌体后缘上方插槽（`BACK_SLOT`，整宽一条、互斥安装）——该插槽已**整体移除**，改为在桌顶显示 6×14 的 1px 棋盘网格，模块自由放置（先做显示，放置逻辑逐步落地）。
> 本文描述的状态：**joystick_2 已完整接入**（预览 → 放置 → 存储 → 渲染 → 拆除 → 占用阻挡）；**throttle 已完整接入**（占地 14×6 全占桌顶网格 → 唯一合法位 (8,12)，只能 0°/180° 旋转）；**monitor_2 已完整接入**（占地 14×6 全占桌顶网格 → 唯一合法位 (8,12)，预览框高 12，**不面向玩家**只随桌体 FACING 旋转）。三者互斥已全部改为纯占地判定。

## 一句话

手持模块物品对准控制台 → 桌顶出现 1px 棋盘网格 → 准星吸附到 1px 格作为**放置中心** → 右键安装，模块渲染在预览盒位置（模型平移到放置位）→ 每个模块记录**占地矩形**，重叠安装被阻挡 → 扳手蹲下右键命中放置盒拆除。

## 坐标约定（北向基准，全系统统一）

- 所有模型/预览/放置坐标均为**北向基准模型空间 px（0..16）**，随桌体 FACING 旋转（绕方块中心 Y，`rotateCenteredDegrees(-facing.getOpposite().toYRot(), UP)` 同约定）。
- 桌体：`x0..16, y0..8, z8..16`（桌顶面 y=8）。桌顶放置区域 = 8 深 × 16 宽（`z8..16`）。
- **棋盘网格**：6×14 格、1px/格、四周内缩 1px → `x1..15, z9..15`（15 条竖线 + 7 条横线，`ControlDeskPlacementOverlay.showTopGrid`）。
- **放置中心** `(placeX, placeZ)`：命中点经 `ControlDeskBlock.snappedBoxCenter` 吸附到 1px 网格整数 px（客户端预览与服务端放置共用同一方法，防偏差）。
- **占地矩形**：中心 ± 半宽（joystick_2 = 4×4 → `FOOTPRINT_HALF=2`；throttle = 14×6 → `FOOTPRINT_HALF_X=7 / HALF_Z=3`）。**throttle 占地必须完全处于网格内（x1..15 / z9..15）→ 唯一合法放置中心 (8,12)（全占）**。
- **放置位竖直（预览下沉 1px 规则）**：底 `PLACE_Y_BOTTOM=7`（嵌入桌面 1px）～ 顶 `PLACE_Y_TOP`（joystick_2=16 高9 / throttle=13 高6）。
- **模型平移**：模型在 Blockbench 中的实际位置（中心 `MODEL_CENTER=8`、底座底 `MODEL_BOTTOM_Y=0`）→ 渲染时平移到放置位：`shift = (placeX-8, 7-0, placeZ-8) / 16`（块单位）。

## 核心常量（集中在 `ControlDeskBlockEntity`）

| 常量 | 值 | 含义 |
|---|---|---|
| `JOYSTICK_2_FOOTPRINT_HALF` | 2 | joystick_2 占地半宽（px）；预览盒与占用阻挡共用 |
| `JOYSTICK_2_PLACE_Y_BOTTOM` | 7f | joystick_2 放置位底 y（嵌入桌面 1px） |
| `JOYSTICK_2_PLACE_Y_TOP` | 16f | joystick_2 放置位顶 y（高 9） |
| `JOYSTICK_2_MODEL_CENTER` | 8f | joystick_2 模型默认中心 x/z（Blockbench 中模型 x6..10 / z6..10 → 8） |
| `THROTTLE_FOOTPRINT_HALF_X / _Z` | 7 / 3 | throttle 占地半宽（14×6 → x±7 / z±3）；预览盒与占用阻挡共用 |
| `THROTTLE_PLACE_Y_BOTTOM` | 7f | throttle 放置位底 y（嵌入桌面 1px） |
| `THROTTLE_PLACE_Y_TOP` | 13f | throttle 放置位顶 y（高 6） |
| `THROTTLE_MODEL_CENTER` | 8f | throttle 模型默认中心 x/z（Blockbench 中模型 x0.99..15.01 / z4.99..11.01 → 8） |
| `THROTTLE_PLACE_X / _Z` | 8 / 12 | throttle **唯一合法放置中心**（14×6 全占网格 x1..15 / z9..15） |
| `MONITOR_2_FOOTPRINT_HALF_X / _Z` | 7 / 3 | monitor_2 占地半宽（14×6 → x±7 / z±3）；预览盒与占用阻挡共用 |
| `MONITOR_2_PLACE_Y_BOTTOM` | 7f | monitor_2 放置位底 y（嵌入桌面 1px） |
| `MONITOR_2_PLACE_Y_TOP` | 19f | monitor_2 放置位顶 y（高 12） |
| `MONITOR_2_MODEL_CENTER` | 8f | monitor_2 模型默认中心 x/z（Blockbench 中模型 14×6 居中 → 8，**用户会同步改模型**） |
| `MONITOR_2_PLACE_X / _Z` | 8 / 12 | monitor_2 **唯一合法放置中心**（14×6 全占网格） |
| `JOYSTICK_2_MODEL_BOTTOM_Y` | 0f | 模型底座底 y（joystick_2 / throttle / monitor_2 均 0） |
| `rotationToFace(Direction, Direction)` | 静态方法 | **joystick_2 安装旋转**：桌体 FACING + 桌→玩家水平方向 → 90° 间隔，让模型 -Z（Blockbench 北向正面）面向玩家：`floorMod(toYRot(facing) - toYRot(toPlayer), 360)`；`toPlayer` 由 `ControlDeskBlock.directionFromDeskTo`（桌体中心→玩家最近基本方向）计算，预览与实装共用 |
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
shift = ( (placeX-8)/16, (7-0)/16, (placeZ-8)/16 )
```
- `R_facing`：`rotateCenteredDegrees(-facing.getOpposite().toYRot(), UP)`（与桌体底座模型同约定）
- `R_install`：安装朝向旋转，绕**放置中心**转（模型已平移到放置位，绕放置中心转才不甩开；Y 旋转枢轴 y 值无关）
  - joystick_2：`rotationToFace(facing, 桌→玩家方向)`（90° 间隔）——让模型 **-Z（Blockbench 北向正面）面向安装时的玩家**；R_facing 已把 -Z 转到桌体 FACING 方向（操作者所在侧），故常规操作位安装 = 0°
  - throttle：`rotationToFace180(facing, 桌→玩家方向)`（**只能 0°/180°**，`rotationToFace` 结果量化到最近 0/180）
  - monitor_2：**无 R_install**（不面向玩家，只随桌体 FACING）
- `T(shift)` 必须是最内层（最后调用）：先于 facing/安装旋转作用于模型空间
- 位置计算：BE 存整数 `placeX/placeZ`；渲染换算 `/16` 成块单位

## 添加新模块参考步骤（checklist）

以「把 joystick_2 的做法复制给新模块 X」为例：

1. **物品**：`MyModItems` 注册 `CONTROL_X`；`MyModCreativeModeTabs` 加入创造模式物品栏；`models/item/x.json` → 用户绘制的物品模型；lang 名称
2. **枚举与状态**：`ControlType` 加 `X`；BE 加 `xInstalled` + 放置字段（若自由放置）+ NBT 四路径（`saveAdditional`/`loadAdditional` 含 contains 守卫/`writeSafe`/`getUpdateTag`）
3. **常量**：BE 加 `X_FOOTPRINT_HALF`（占地半宽）、`X_PLACE_Y_BOTTOM/TOP`（放置位竖直）、`X_MODEL_CENTER/MODEL_BOTTOM_Y`（Blockbench 中模型实际位置）；改模型后同步
4. **块交互**：`ControlDeskBlock.controlTypeOf` / `controlItem` / `getDrops`；`useItemOn` 里计算放置中心（若中心吸附可直接复用 `snappedBoxCenter`，尺寸不同则扩展）；`installBounds` 返回空（自由放置模块）
5. **安装/拆除**：BE `install` 分支（记录位置 + `blocksPlacement` 检查 + 记录安装朝向旋转）、`remove` 分支（重置位置）；`hitControlType` 加放置盒命中；lang「位置被占用」
6. **预览**：`ControlDeskPlacementOverlay` 加手持时显示（网格加类型；盒子参考 `showJoystick2Box`，尺寸用常量）；`showRemovePreview` 加放置盒显示；`ControlDeskGhostPreviewRenderer` 加实物预览（若需要）并解除 early-return
7. **渲染**：`MyModPartialModels` 加部件；`ControlDeskVisual`（`syncInstance` + 放置变换）+ `ControlDeskRenderer`（放置变换）——三处变换保持一致
8. **占用**：`blocksPlacement` 加 X 的占地矩形判定（半宽/非方形需扩展参数）；**三个自由放置模块（joystick_2 / throttle / monitor_2）的旧互斥已全部移除**（改为纯占用判定）
9. **验证**：预览位置 == 实装位置；四个朝向安装旋转正确；占用阻挡生效；扳手拆除命中放置盒；破坏掉落

## 当前状态与已知边界

- ✅ joystick_2 完整接入：桌顶网格 + 4×9×4 预览盒 + 半透明实物 + 位置存储/渲染 + 4×4 占用阻挡 + 扳手拆除
- ✅ throttle 完整接入：占地 14×6 全占桌顶网格 → 唯一合法位 (8,12) + 14×6×6 预览盒（固定位置）+ 半透明实物 + 位置存储/渲染 + 只能 0°/180° 旋转 + 14×6 占用阻挡 + 扳手拆除
- ✅ monitor_2 完整接入：占地 14×6 全占桌顶网格 → 唯一合法位 (8,12) + 14×6×12 预览盒（固定位置）+ 半透明实物 + 位置存储/渲染 + **不面向玩家**（仅随桌体 FACING）+ 14×6 占用阻挡 + 扳手拆除
- 三个自由放置模块互斥已全部改为纯占地判定（monitor_2 / throttle 同占 (8,12) → 天然互斥；joystick_2 网格内也与两者重叠）
- 网格线是纯显示层（Outliner），逻辑全部基于放置中心 + 占地矩形
- 安装位置 = 服务端对**右键命中点**吸附（与客户端准星同射线）；多玩家极端场景如需精确可控，可改客户端发 payload（对齐 Monitor 的 PlaceModulePayload 模式）
- 预览统一「下沉 1px」规则：模块预览盒/实物/实装放置位底都在 y7（嵌入桌面 1px）；网格线在桌顶面（y8，+0.06 防 z-fight），不下沉
- 吸附中心 = 盒子中心（居中吸附）；如需 monitor 式「角落吸附」（准星=模块左上角），改 `snappedBoxCenter` 的取整方式即可
- 旋转只能 0°/180° 的模块（throttle）：放置中心必须让占地矩形在 0° 与 180° 下重合（中心对称矩形，如 14×6 中心在 (8,12)）——否则两个角度占地不同，占用判定需按旋转区分
- monitor_2 模型需在 Blockbench 中同步改为 14×6 居中 (8,8)（当前模型 12×8 是过渡态；代码按 MODEL_CENTER=8 设计）
