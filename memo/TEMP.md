TO DO



---

# 虚拟摇杆 HUD 改造计划（定稿，2024 记录）

## 决策（用户确认）
- 底座：**半透明圆形**（`fillCircle` 逐行扫描近似，半径 ~22px）；摇杆头：**最简单的小正方形**（8×8 `g.fill`）；无数值 readout、无十字轴线
- 保底：扫描圆不理想 → 底座切菱形（线性宽度）
- CC 轴值语义：**0..1 幅度 + 0/1 原始值为主，另加 -1..1 带符号变体**
- 按键冲突：**不做**（memo 的 KeyConflictContext 待确认项标记不实施）

## 步骤
1. **GUI Layer 迁移**：`JoystickOverlay implements LayeredDraw.Layer`，MOD 总线 `RegisterGuiLayersEvent.registerAbove(VanillaGuiLayers.HOTBAR, "ccpe:joystick_overlay", ...)`；去掉 `RenderGuiEvent.Post` / `NeoForge.EVENT_BUS` 注册
2. **纯 `g.fill` 绘制**：`fillCircle`（底座）+ 方块摇杆头；删除 `virtual_joystick_base.png` / `virtual_joystick_knob.png`
3. **状态扩展**（`SeatControlState`）：新增 `rawX/rawY`（0/1 原始值，轴上有无按键动作）+ `analogX/analogY`（0..1 幅度）；保留 `joyX/joyY`（-1..1，HUD/动画用）；`SeatControlListener` 一并派生
4. **SMOOTHED 平滑**：overlay 内 `Map<String,Float>`，`approach(rendered, target, 0.01f)` 指数衰减 + `getRealtimeDeltaTicks()` 帧率修正；只平滑显示层；离开操作模式 clear
5. **按键冲突：不做**
6. **CC 接口**（后续实现，状态已预留）：`isJoystickXActive()/isJoystickYActive()` → 0/1；`getJoystickX()/getJoystickY()` → 0..1；`getJoystickXSigned()/getJoystickYSigned()` → -1..1

## 涉及文件
`JoystickOverlay.java` / `SeatControlState.java` / `SeatControlListener.java` / `CCPeripheralExtenderClient.java` / 删 2 张 PNG