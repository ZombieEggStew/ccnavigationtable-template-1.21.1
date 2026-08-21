
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
