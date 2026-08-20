# 外设扩展器概述

![外设扩展器](../img/micro_peripheral_extender.png)

外设扩展器（Peripheral Extender）是 CCPE 的核心组件，整合多种读取信息与控制方式，并且支持区块加载和物理结构加载。

!!! tip "pe"
    下文将 **外设扩展器** 简称为 **pe**

---

## 使用教程
1. [频道设置](channel-setup.md) — 放置 pe 并设置频道号
2. [NBT 读取详细文档](nbt-reading.md) — 读取方块 NBT 数据
3. [外设代理详细文档](peripheral-proxy.md) — 获取的 CC:T 外设
4. [无线红石详细文档](wireless-redstone.md) 
5. [航空学传感器集成详细文档](simulated-integration.md) — 读取 Sable 物理引擎的速度、质量、姿态
6. [区块/物理结构加载详细文档](chunk-loading.md)

---

## API

完整 API 参考：[Lua API 参考](../api-reference.md)

!!! tip "AI 编程辅助"
    [api](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/wiki/docs/api-reference.md)

    ↑ 下载此文档后可发送给 AI 助手（如 ChatGPT、Claude 等），帮助你快速编写外设扩展器的 Lua 代码。

---

## 性能特点

| 指标 | 数值 | 说明 |
|---|---|---|
| 数据刷新频率 | 50ms | 服务端每 tick 更新 |
| Lua 读取延迟 | ~0.02ms | 单次调用耗时 |
| 最大传感器数 | 65535 | 受频道号范围限制 |
| 传输距离 | 无限制 | 默认配置 |

!!! success "高频监控友好"
    即使需要每 tick 读取多个传感器的数据，性能开销也非常小。

