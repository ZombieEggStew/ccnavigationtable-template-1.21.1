

5. 两种状态同步方式混用
Monitor 的网格走自定义包 SyncGridPayload（可靠、全量），而背景/角度/频道走 sendBlockUpdated + getUpdateTag。两种机制并存没问题，但边界要清晰：建议注释里明确"什么时候走哪种"，否则以后加字段容易漏同步。

另外 PeripheralExtenderScreen 用 handleInventoryButtonClick(menu.containerId, 0) 当轮询触发器的写法比较隐晦，建议换成专用请求 payload 或服务端主动推送（你已有 SensorNbtPayload 推送通道）。
