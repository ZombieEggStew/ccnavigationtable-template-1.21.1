# 短程信号链接器

> 作用域限定在单个物理体内的外设寻址

![short_range_linker](../img/short_range_link.png)

**短程信号链接器**（Short-Range Signal Linker，`ccpe:short_range_linker`）是一个贴附式方块（地面 / 天花板 / 墙面，同微型外设扩展器）。装在物理体（Sable sub-level）上时，它会注册一个**频道**，该频道**只在当前物理体内**（含约束链）可寻址——同一物理体上的 CC:Tweaked 电脑可以通过 `ccpe.sensor_system` 取到链接器所附着方块的外设。

与微型外设扩展器的全局频道不同，链接器频道是**按物理体隔离**的：两架飞机可以同时使用频道 `1` 而互不干扰。

## 频道语义

- 频道号是链接器在**本物理体内的地址**：同一物理体内，一个频道号恰好对应一个链接器（1:1）。
- 同一物理体上两个链接器抢同一频道时，**后注册者自动顺延**到下一个空闲频道。
- 查询方（电脑）**不需要自己的频道号**——它只需要目标链接器的频道号（电脑侧零配置）。
- **不在任何物理体上**的链接器严格不注册：GUI 显示「只在物理体上可用」，所有 Lua 查询一律返回 `nil`。
- 不同物理体完全隔离——A 机的频道 `1` 对 B 机不可见。

## GUI

右键链接器打开配置界面（控件只在物理体上显示）：

- **频道滚轮** — 在频道数字上滚动修改（按住 Shift 步进 10）；**同体**已被其它链接器占用的频道会自动跳过。
- **加载物理体**开关 — 链上共享开关，见下节。
- 不在物理体上时，界面只显示「只在物理体上可用」。

## 加载物理体（链上共享开关）

整条物理体链共用一个布尔值，机制同微型外设扩展器的物理体加载模式（Sable force-load + PORTAL ticket）：

- 在**任意**链接器上切换该开关，会把同一个值写入**链上全部**链接器（last-toggle-wins）——链上所有 GUI 开关显示一致。
- **开启**：该链接器为整条链注册 Sable force-load + PORTAL ticket，远离后物理体也不会卸载。
- **关闭**：释放 ticket。
- （重新）加载 / 新放置时开关按 **OR 自愈**：新链接器加入一条已有人开启的链时自动把自己置为开，蓝图部署后各链接器状态保持一致。
- 与微型外设扩展器自己的加载模式相互独立；重复 ticket 无害（Sable 按 (ticketType, key=pos) 去重）。
- 不在物理体上时不可用。
- 与频道一起随 Create 蓝图（schematic）持久化。

## Lua API

同一物理体（含约束链）上的电脑通过 `require("ccpe.sensor_system")` 使用——与传感器方块共用同一模块：

| 方法 | 返回 | 说明 |
|---|---|---|
| `getPeripheral(channel)` | peripheral / nil | 本体内频道 `channel` 对应设备的外设：频道被链接器占用 → 链接器所附着方块的外设；**频道被[控制台](../control-desk/overview.zh.md)占用 → 控制台自身外设**（同一物理体频道空间；Capability 查询，主线程执行） |
| `getRedstoneOutput(channel)` | number | 目标链接器当前的红石输出信号（0-15） |
| `getRedstoneInput(channel)` | number | 目标链接器位置当前接收到的最强红石信号（0-15） |
| `setRedstoneOutput(channel, signal)` | - | 写目标链接器的红石输出（自动钳位 0-15），更新方块的充能状态与相邻红石 |

- **作用域 = 调用电脑所在物理体**（含约束链）：电脑不在任何物理体上时，`getPeripheral` 返回 `nil`，红石读方法返回 `0`。
- 目标未命中（频道空闲 / 链接器已卸载）→ 同上。
- `getPeripheral` / `setRedstoneOutput` 调度到服务端主线程执行；红石读方法为缓存直读、零主线程调度（每 tick 刷新，最多滞后 1 tick）。

```lua
local ss = require("ccpe.sensor_system")

-- 取频道 1 的链接器所附着方块的外设
local nav = ss.getPeripheral(1)
if nav then
    print("got peripheral")
end

-- 红石输出：点亮相邻红石线
ss.setRedstoneOutput(1, 15)

-- 红石输入：读目标链接器位置的最强信号
print("input at channel 2:", ss.getRedstoneInput(2))
```

## 已知边界

- **链动态变化**：两个物理体经轴承连接后频道空间自动合并（约束链），断开后恢复各自独立；链接器每 20 tick 复核并在冲突时顺延。
- **蓝图兼容**：频道与共享加载开关随 Create 蓝图持久化；部署后共享开关按 OR 规则自愈一致。
- 不同物理体频道号相互独立——链接器只放在**目标**方块上即可，电脑侧无需任何配置。
