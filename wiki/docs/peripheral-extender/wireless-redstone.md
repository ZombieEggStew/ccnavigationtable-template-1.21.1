## 无线红石

就像 机械动力 的 无线红石信号终端，但是使用频道控制

| 方法 | 说明 |
|---|---|
| `pe.setRedstoneOutput(ch, 0-15)` | 无线红石发送 mainThread = true |
| `pe.getRedstoneOutput(ch)` | 读取发送信号 |
| `pe.getRedstoneInput(ch)` | 读取输入红石信号 |


```lua
local pe = require("ccpe.pe")

-- 读取频道为5的pe附近的红石信号
local signal = pe.getRedstoneInput(5)
print("Signal: " .. signal)

-- 向频道为6的pe附近激活 10 级红石信号
pe.setRedstoneOutput(6, 10)

```

## 下一步

- [导航桌集成详细文档](navigation-table.md) — 将外设扩展器与导航桌集成
