# CC:T 两台电脑通信（rednet）与「A 让 B 执行函数」的做法

> 状态：**结论已按 CC:T 1.118.0 源码核实**（`rednet.lua` 原文，见文末「来源」）；本文代码**未在游戏内跑过**，属方案 + 可直接粘贴的脚本对，首次使用时按第九节验证清单确认。
> 适用场景：游戏内写 Lua，让 0 号电脑给 1 号电脑发请求、1 号执行某个函数并回结果。
> 明确结论：**网络上传不了函数 / 元表**，只能传 number / string / boolean / table；「远程执行函数」本质是**接收端本地命令表（dispatch table）**被消息触发。

## 一、核心结论

1. 两台电脑之间**没有**「直接调用对方 Lua 函数」的通道；CC:T 的合作式多任务也不跨电脑。
2. 可行的通信层只有 modem：`rednet`（高层）或直接用 `modem` 外设的 `transmit` / `modem_message` 事件（低层，自定义频道）。
3. 「1 号执行函数」= 1 号常驻程序里维护一张 `handlers` 表，收到消息后按 `msg.cmd` 查表 + `pcall` 调用。
4. 请求/响应模式必须自带**关联码**（`replyTo`）与**超时**：消息不排队，目标没在 `receive` 上等待就直接丢。

## 二、rednet API 速查（CC:T 1.118.0）

| API | 签名 | 备注 |
|---|---|---|
| `rednet.open` | `open(modem)` | 打开某 modem：一条 = 本机 ID 频道（`getComputerID() % 65500`）+ 广播频道；必须 modem 类型外设 |
| `rednet.close` | `close(modem)` | 关 modem；`modem` 为 nil 时关全部 |
| `rednet.isOpen` | `isOpen(modem?)` → boolean | 查本机 ID 频道 + 广播频道是否都开 |
| `rednet.send` | `send(recipient, message, protocol?)` → boolean | 返回「是否成功发出」，**不代表对方收到** |
| `rednet.receive` | `receive(protocolFilter?, timeout?)` → `senderId, message, protocol` / `nil` | 超时返回 `nil`；兼容旧式 `receive(timeout)` |
| `rednet.broadcast` | `broadcast(message, protocol?)` | 发到 `CHANNEL_BROADCAST` |
| `rednet.host` / `unhost` | `host(protocol, hostname)` / `unhost(protocol)` | 注册服务名；`hostname == "localhost"` 保留会 `error`，重名 `error("Hostname in use")` |
| `rednet.lookup` | `lookup(protocol, hostname?)` → id / … | 按协议（可选再按名字）找电脑 ID |

常量（`rednet` 是全局 API，这些值直接可读）：

```lua
rednet.CHANNEL_BROADCAST = 65535   -- 广播频道
rednet.CHANNEL_REPEAT    = 65533   -- 中继频道（send 会额外在这里发一份）
rednet.MAX_ID_CHANNELS   = 65500   -- 电脑 ID 频道空间，id_as_channel = id % 65500
```

消息可序列化范围：number / string / boolean / table；**函数、元表不传输**（会被转成 nil）。

## 三、硬件与连接

| 方式 | 要求 | 适用范围 |
|---|---|---|
| 无线 modem | 贴在电脑侧面（口袋电脑/海龟需装 upgrade），两台都 `rednet.open` | 有效范围受配置项 `modem_range` 限制，雷雨会缩短 |
| 有线 modem | 各自一个有线 modem 接到**同一段网络线缆**（同一网络） | 距离无限、不受天气影响，固定设备首选 |

```lua
local modem = peripheral.find("modem") or error("没有 modem")
rednet.open(peripheral.getName(modem))
```

`rednet.open` 只接受 **modem 类型**外设名；用 `peripheral.getName()` 拿到的就是可用名字（不一定是 "back"，取决于贴哪一面）。

## 四、1 号（worker / 执行端）参考脚本

```lua
-- 放在 startup.lua 或 shell.run("worker")，否则电脑停在命令行 = 什么都不执行
local PROTO = "myproto"

-- 可被远程触发的函数表（真正的"被执行的函数"在这里）
local handlers = {
  ping     = function() return "pong" end,
  add      = function(x, y) return x + y end,
  setSpeed = function(v) return true end,
}

rednet.open(peripheral.getName(peripheral.find("modem") or error("no modem")))
rednet.host(PROTO, "worker1")            -- 可选：让调用方用名字找到本机 ID

local unpack = table.unpack or unpack    -- CC 默认 Lua 5.1，只有全局 unpack

while true do
  local sender, msg = rednet.receive(PROTO)
  if type(msg) == "table" and msg.cmd then
    local h = handlers[msg.cmd]
    local ok, res
    if h then
      ok, res = pcall(h, unpack(msg.args or {}))
    else
      ok, res = false, "unknown cmd: " .. tostring(msg.cmd)
    end
    rednet.send(sender, { replyTo = msg.id, ok = ok, result = res }, PROTO)
  end
end
```

要点：
- `pcall` 保证单个 handler 抛错不会打死 worker 循环；`local ok, res = pcall(...)` 只取**第一个**返回值，要多值返回就用 `table.pack(pcall(...))`。
- `rednet.receive(PROTO)` 只收本协议消息，别的模组/别的程序的流量自动丢弃。

## 五、0 号（caller / 调用端）参考脚本

```lua
local PROTO = "myproto"
rednet.open(peripheral.getName(peripheral.find("modem") or error("no modem")))

local target = rednet.lookup(PROTO, "worker1") or 1     -- 也可直接写电脑 ID

--- 同步调用：等 worker 回包；超时返回 nil, "timeout"
local function call(cmd, args, timeout)
  local reqId = tostring(os.getComputerID()) .. ":" .. tostring(math.random(1, 1000000))
  if not rednet.send(target, { id = reqId, cmd = cmd, args = args or {} }, PROTO) then
    return nil, "no modem"
  end
  local deadline = os.clock() + (timeout or 3)
  while true do
    local left = deadline - os.clock()
    if left <= 0 then return nil, "timeout" end
    local sender, msg = rednet.receive(PROTO, left)
    if not msg then return nil, "timeout" end
    if type(msg) == "table" and msg.replyTo == reqId and sender == target then
      return msg.ok, msg.result          -- 自己这次请求的回复
    end
  end
end

local ok, result = call("add", { 2, 3 })
print(ok, result)   -- true  5
```

## 六、坑清单（按踩到频率排）

1. **消息不排队、不回执**：目标关机 / 未在 `receive` 等待 / 超出范围 → 消息直接丢，`rednet.send` 仍返回 `true`。关键指令必须重试 + 超时判定。
2. **worker 必须常驻**：电脑空闲在 shell 时没有任何程序在 `rednet.receive`，表现是「发了没反应」。用 `startup.lua` 或 `shell.run("worker")`。
3. **多请求并发必须靠关联码**：同一台电脑用 `parallel.waitForAny` 跑多任务时，两路 `receive` 会抢消息 → 用 `replyTo == reqId` 过滤（并可用 `sender == target` 再筛一层）。
4. **协议字符串要一致且唯一**：随便取名如 `"myproto"`；命名冲突会互相干扰（`rednet.receive` 只按协议过滤，不按发送方）。
5. **`rednet.host` 重名会 `error`**；`localhost` 是保留名。多台同一服务用不同 hostname。
6. **安全**：同网络任何电脑都能给你的 worker 发指令（CC 官方明确说明 rednet **不保证任何安全性**，可被监听、可被冒充）。worker 里别放「任意代码执行」，关键操作加 token：`if msg.token ~= SECRET then rednet.send(sender, {replyTo=msg.id, ok=false, err="denied"}, PROTO); ... end`。多人服务器上建议签名/加密。
7. **Lua 5.1**：用 `unpack`（或 `local unpack = table.unpack or unpack`）；`os.clock()` 是启动后的 CPU 秒数，做超时足够。
8. 低层写法（`modem.transmit(channel, replyChannel, msg)` + `os.pullEvent("modem_message")`）可自定义频道，但要自己管频道号与序列化；一般没必要，rednet 已经封装。

## 七、广播 + 注册表（多台 worker 组网）

- 少量、固定角色：每台 `rednet.host(PROTO, "workerN")`，调用方 `rednet.lookup(PROTO, "workerN")` 拿 ID。
- 很多台、动态：worker 启动时 `rednet.broadcast({type="register", role="x"})`，协调方维护 `id → role` 表，按角色发请求（同样要 `replyTo` + 超时）。

## 八、本项目内的「直连」通道（不是两电脑通信，别混用）

| 通道 | 代码位置 | 能力 | 边界 |
|---|---|---|---|
| 外设同步调用 | `peripheral.wrap(side)` / `peripheral.find(type)`；capability 注册在 `compat/cc/CCPeripheralCapabilities.java` | 一次 Lua 调用直接执行服务端方法并**同步拿返回值**（走 CC 的 mainThread 机制） | 只对**外设**（方块实体）有效；两台电脑之间无效 |
| pe 全局频道 | `PeripheralExtenderAPI` + `GlobalChannelRegistry`（`ccpe.pe`） | `getPeripheral(channel)` 全局寻址，与 Monitor / 控制台共享命名空间 | 全局作用域，跨物理体可达 |
| 物理体作用域频道 | `SensorSystemAPI.getPeripheral(ch)`（并入 `ccpe.sensor_system`）+ `ShortRangeLinkerRegistry` / `ControlDeskRegistry` | 按调用电脑所在 Sable 约束链寻址 | 只在同一物理体内有效；返回的是**外设**，不是另一台电脑 |
| 传感器系统 | `my_sensor_system.md` / `cc-sensor-lua-api` skill | 应力/红石/NBT 读数等 | 服务端主线程约束，见该 memo |

如果需求是「0 号电脑触发 1 号电脑上的动作」，在上述直连通道里**都没有对应物**：直连通道只能拿到外设，而电脑本身不是外设。跨电脑一律回到 rednet。

## 九、首次使用验证清单

1. 两台电脑`peripheral.find("modem")` 都非 nil，`rednet.isOpen()` 为 true。
2. worker 端 `rednet.host(PROTO, "worker1")` 不报错；调用端 `rednet.lookup(PROTO, "worker1")` 返回 worker 的真实 ID。
3. 调用 `call("ping")` 得到 `true, "pong"`。
4. 调 `call("add", {2,3})` 得到 `true, 5`（验证 `args` 表与 `unpack` 传递正确）。
5. 传一个不存在的 `cmd`，应得到 `false, "unknown cmd: ..."`（验证 pcall 兜底）。
6. 关掉 worker，调用端应在约 3 秒后返回 `nil, "timeout"`（验证超时）。
7. 两台同时发两条请求（`parallel`），两条回复都能按 `replyTo` 正确配对（验证关联码）。

## 十、来源

- `references/CC-Tweaked-mc-1.21.x/projects/core/src/main/resources/data/computercraft/lua/rom/apis/rednet.lua`（API 签名、常量、`send`/`receive`/`host` 实现、安全警告原文）
- `references/CC-Tweaked-mc-1.21.x/projects/core/src/test/resources/test-rom/spec/apis/rednet_spec.lua`（行为用例：协议过滤、超时、lookup 语义）
- `api/cc-tweaked-1.21.1-core-api-1.118.0-sources/`（`IPeripheral` 等外设接口定义）
- `references/CC-Tweaked-mc-1.21.x/projects/core/src/main/resources/data/computercraft/lua/rom/programs/rednet/chat.lua`（redis `host`/`lookup`/协议分发的实战范例）
