# 红石收发器

![红石收发器](../img/temp.png)

使计算机可以直接读取和发送**机械动力无线红石终端网络**的信号，不需要再在计算机旁边摆上一堆 无线红石终端

支持create蓝图系统保存频道配置，但在部署的时候要注意频道重复

每个收发器能够配置多个频道，每个频道绑定一个红石频率。Lua 端通过频道号操作：

!!! tip "频道"
    此频道与 [外设扩展器](../peripheral-extender/overview.md) 的频道号无关，互不干扰。

---

## 红石信号

| 方法 | 说明 |
|---|---|
| `getRedstoneSignal(频道)` | 读取指定频道绑定的 Create Redstone Link 信号（0-15） |
| `setRedstoneSignal(频道, 0-15)` | 向指定频道绑定的 Create 网络发送红石信号 |

```lua
local r = peripheral.find("ccpe:redstone_transceiver")

-- 读取频道 3 对应的 Create 红石网络信号
local signal = r.getRedstoneSignal(3)

-- 向频道 7 对应的 Create 网络发送满信号
r.setRedstoneSignal(7, 15)
```

---

## 频道管理

除了在游戏里手动配置频率，也可以用 Lua 直接管理频道：

| 方法 | 说明 |
|---|---|
| `setFrequency(频道, 物品1, 物品2)` | 新建/修改频道的频率物品。`物品2` 留空时与 `物品1` 相同，两个都留空则新建一个空频道 |
| `getFrequency(频道)` | 读取频道的频率物品 ID，返回 `{freq1=..., freq2=...}`；频道不存在返回 `nil` |
| `removeChannel(频道)` | 删除指定频道 |
| `getChannels()` | 列出当前所有已配置的频道号 |

```lua
local r = peripheral.find("ccpe:redstone_transceiver")

-- 新建频道 7，频率物品为 (红石, 红石)
r.setFrequency(7, "minecraft:redstone")

-- 频道 7 的频率改为 (红石, 石头) (省略前缀，判定为 minecraft:)
r.setFrequency(7, "minecraft:redstone", "stone")

-- 读取频道 7 的频率物品
local freq = r.getFrequency(7)
print(freq.freq1, freq.freq2)

-- 列出所有频道
for _, ch in ipairs(r.getChannels()) do
    print("channel: " .. ch)
end

-- 删除频道 7
r.removeChannel(7)
```

