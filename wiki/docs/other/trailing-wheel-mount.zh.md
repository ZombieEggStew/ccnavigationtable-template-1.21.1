# 从动轮悬架

> 单轮无动力悬架，转向由 Lua 驱动——无红石、无 Create 动力输入

**从动轮悬架**（`ccpe:trailing_wheel_mount`）是一个被动的单轮悬架方块。它承载一个轮胎，
用真实的 Sable 悬挂物理撑住车身，被推动时随车身自由滚转——它自己**不产生驱动力**
（无 Create 动力/应力输入）。

与 offroad 原版 `wheel_mount` 不同，转向**不读取红石**：该方块被注册为 CC:Tweaked 外设，
完全由 Lua 控制转向。

## 前置条件

- 必须先安装轮胎：任意带 `offroad:TIRE` 数据组件的物品（右键**朝向面**或**底面**装卸/更换轮胎）。
- 方块必须随车体结构装配进 Sable 物理体（sub-level），悬挂与转向力才会生效；未装配时只是静态方块。

## 外设

- 类型名：`trailing_wheel_mount`
- 通过 `peripheral.find("trailing_wheel_mount")` 或 `peripheral.wrap(side)` 获取。

## Lua API

| 方法 | 返回 | 说明 |
|---|---|---|
| `setSteering(value)` | - | 转向输入，范围 `[-1, 1]`（越界自动钳位）。`0` = 直线，`±1` = 满舵（约 ±30°）。正负对应两个转向方向（符号约定沿用 offroad 红石差速转向；游戏内物理左右方向以实际表现为准）。在服务端主线程执行。 |
| `getSteering()` | number | 当前转向输入，范围 `[-1, 1]` |
| `getSteeringAngle()` | number | 当前转向角度（度），最大约 ±30 |
| `hasTire()` | boolean | 是否已安装轮胎 |
| `getTireRadius()` | number | 已安装轮胎的半径（格）；无轮胎返回 `0` |
| `getExtension()` | number | 实时悬挂行程（格），越大表示轮子垂得越低 |
| `getAngularVelocity()` | number | 轮子自转角速度（弧度/tick） |
| `isLiftedUp()` | boolean | 是否离地悬空（离地轮无牵引力） |
| `getTouchingFriction()` | number | 当前轮下地面摩擦系数（正常方块为 `1.0`，冰面等更低） |

## 行为说明

- **纯 Lua 转向**：完全没有红石输入。Lua 值在服务端存为转向信号（`-15..15`，`0` = 直线），
  再用与 offroad 相同的公式转成偏航角（最大约 ±30°）。
- **不持久化**：转向值只存在内存里——区块卸载或服务器重启后归零（直线）。
- **平滑转向**：轮子偏航角朝目标值 chasing 平滑（物理侧与客户端动画都做插值）。
- **转向改变施力方向**：滚动阻力与侧滑摩擦力沿转向后的轴线施加，因此转向真的会引导车身——
  推动车辆可以看到它转弯。
- **从动轮**：悬架本身从不推车，驱动力必须来自别处（驱动轴、发动机等）。
- 转向值会同步到客户端，悬架臂与轮胎绕转向 pivot 视觉偏转。

## 示例

```lua
local wm = peripheral.find("trailing_wheel_mount")

wm.setSteering(1.0)   -- 向一个方向打满
wm.setSteering(0.0)   -- 回正
wm.setSteering(-0.5)  -- 向另一个方向打一半

print(wm.getSteering())       -- 例如 -0.5
print(wm.getSteeringAngle())  -- 当前转向角（度）

-- 遥测
if wm.hasTire() then
    print("tire radius:", wm.getTireRadius())
end
print("extension:", wm.getExtension())
print("angular velocity:", wm.getAngularVelocity())
print("lifted:", wm.isLiftedUp())
print("friction:", wm.getTouchingFriction())
```
