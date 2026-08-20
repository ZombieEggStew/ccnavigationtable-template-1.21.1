# 外设扩展器概述

![外设扩展器](../img/micro_peripheral_extender.png)

外设扩展器（Peripheral Extender）是 CCPE 的核心组件，整合多种读取信息与控制方式，并且支持区块加载和物理结构加载。

!!! tip "pe"
    下文将 **外设扩展器** 简称为 **pe**

---

## 使用教程
1. [频道设置](channel-setup.md) — 放置 pe 并设置频道号
2. [NBT 读取详细文档](nbt-reading.md)

---

## API

完整 API 参考：[Lua API 参考](../api-reference.md)

<a href="api-reference.md" download="peripheral-extender-api-reference.md" style="display: inline-block; padding: 8px 16px; background-color: #4051b5; color: white; text-decoration: none; border-radius: 4px; font-weight: 500;">📥 下载 API 文档</a>

!!! tip "AI 编程辅助"
    下载此文档后可发送给 AI 助手（如 ChatGPT、Claude 等），帮助你快速编写外设扩展器的 Lua 代码。

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

