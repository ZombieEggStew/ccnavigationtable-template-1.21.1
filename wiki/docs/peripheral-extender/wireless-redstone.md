## 无线红石

就像 机械动力 的 无线红石信号终端，但是使用频道控制


| 方法 | 说明 |
|---|---|
| `getRedstoneSignal(频道)` | 读取指定频道绑定的 Create Redstone Link 信号（0-15） |
| `setRedstoneSignal(频道, 0-15)` | 向指定频道绑定的 Create 网络发送红石信号 `mainThread=true` |

```lua
local pe = require("ccpe.pe")

-- 读取频道为5的pe附近的红石信号
local signal = pe.getRedstoneSignal(5)
print("Signal: " .. signal)

-- 向频道为6的pe附近激活 10 级红石信号
pe.setRedstoneSignal(6, 10)

```