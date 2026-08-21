# 最需要处理的风险（P0）
1. "可选依赖"声明与硬编码引用自相矛盾 —— 崩溃风险
neoforge.mods.toml 里 sable 和 simulated 都声明为 optional，但代码里是硬引用：

SableCompat 直接 import Sable 的类（Sable.HELPER、SubLevelContainer 等），类初始化时会加载它们；
PeripheralExtenderBlockEntity 有字段 SubLevel cachedSubLevel，且 refreshAllCaches() 每 tick 执行 attachedBE instanceof NavTableBlockEntity；
MonitorGridOverlay.onRenderLevel（客户端每帧）直接调 SableCompat.getContainingSubLevel。
关键问题：NoClassDefFoundError 是 Error 不是 Exception，你代码里的 try/catch (Exception e) 接不住它。没有装 Sable 时，传感器的 serverTick 或客户端渲染帧会直接崩。SableCompat.isAvailable() 还硬编码返回 true，与 "optional" 完全矛盾。build.gradle 注释里提到过"Sable 代码隔离在 SableImpl，只在 ModList 报告存在时加载"——现在的代码已经退化成直接引用了。

建议二选一：

把 sable、simulated 改为 required 依赖（如果这两个 mod 本来就是你的必装环境）；或
恢复隔离方案：所有 Sable/Simulated 调用收敛到一个门面类，门面只在 ModList.get().isLoaded("sable") 时才被触碰，BE 里的字段类型改成 Object 或经接口间接持有。
2. 主类 CCPeripheraExtender 职责过重
368 行的构造函数里内联注册了 15 个 payload handler，每个都是"getBlockEntity(pos) → instanceof → 改状态"的重复骨架。以后每加一个功能，主类就再胖一圈，也不好单独测试。

建议：把网络注册拆成独立的 ModPackets/NetworkHandler 类（static void register(PayloadRegistrar)），甚至按功能（Monitor / Sensor / Receiver）拆多个 handler 类；重复的"按 pos 取 BE 并 instanceof"可以提一个 findMonitor(level, pos) 之类的工具方法。15 个 payload record 本身是 idiomatic 的，样板可以保留，但 handler 应该搬走。


# 性能与健壮性（P1）
3. 传感器每 tick 全量 NBT 序列化
PeripheralExtenderBlockEntity.serverTick → refreshAllCaches 每个传感器、每一 tick 都执行 attachedBE.saveWithFullMetadata(...) 加 Sable 查询。附着大型机器（Create 装置、大存储等）时，每 tick 全量序列化 NBT 的开销是实打实的。你给 GUI 做了 SENSOR_NBT_POLL_INTERVAL 轮询配置，说明意识到了轮询成本，但缓存刷新却是硬编码每 tick。

建议：降频到每 10~20 tick 刷新一次 + Lua 读取时提供"按需强制刷新"的方法；或者只在附着 BE 标记为脏（setChanged 无法直接监听，但可以比较 tick 计数或由 BE 主动通知）时才刷新。

4. 字符串魔法值匹配（脆弱）
MonitorGridOverlay 判断扳手：held.getItem().toString().contains("create") && contains("wrench") —— 依赖物品注册名的字符串包含匹配，任何改名/本地化/同名前缀的物品都会误判；
ModuleType.fromItem 用 stack.getItem().toString() 拼 "ccpe:module_" + t.name；
applyModuleConfig 用 "screen" 字符串区分模块/屏幕。
建议：扳手判断改用 instanceof IWrenchable 或 Create 提供的 tag/工具类；模块物品 ↔ ModuleType 的映射建议建一个显式的 Map<Item, ModuleType>（或 ModuleType 里持有 Supplier<Item>），而不是拼字符串。

5. 两种状态同步方式混用
Monitor 的网格走自定义包 SyncGridPayload（可靠、全量），而背景/角度/频道走 sendBlockUpdated + getUpdateTag。两种机制并存没问题，但边界要清晰：建议注释里明确"什么时候走哪种"，否则以后加字段容易漏同步。另外 PeripheralExtenderScreen 用 handleInventoryButtonClick(menu.containerId, 0) 当轮询触发器的写法比较隐晦，建议换成专用请求 payload 或服务端主动推送（你已有 SensorNbtPayload 推送通道）。
