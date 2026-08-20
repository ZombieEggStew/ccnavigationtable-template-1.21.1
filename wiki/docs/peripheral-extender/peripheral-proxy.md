# 外设代理

1. 将 pe 附着在 cc:t 外设上
2. 使用 getPeripheral 获取外设对象

!!! info "OP"
    不需要线缆，无视距离，无重量


```lua
local pe = require("ccpe.pe")

local monitor = pe.getPeripheral(10) -- 获取频道为10的pe附着的外设

assert(monitor , "未找到外设")
```