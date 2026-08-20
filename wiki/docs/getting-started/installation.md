# 安装与配置

## 前置条件

CCPE 需要以下环境：

- **Minecraft** 1.21.1
- **NeoForge** 21.1.235 或更高版本
- **ComputerCraft: Tweaked** (CC:T) — 必须
- **Create** — 必须（红石收发器和电子变速箱功能依赖）
- **Simulated** (Create: Aeronautics) — 可选（导航桌功能需要）
- **Sable** — 可选（物理数据功能需要）

## 下载安装

1. 前往 [GitHub Releases](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/releases) 页面
2. 下载最新版本的 `.jar` 文件
3. 将文件放入 Minecraft 的 `mods` 文件夹
4. 启动游戏

## 配置文件

首次运行后，配置文件将生成在 `config/ccpe/` 目录下。

### 主配置项

```toml
[peripheral_extender]
# 传感器数据刷新频率（tick）
refresh_interval = 1

# 最大传输距离（米，0 = 无限制）
max_distance = 0

[redstone_transceiver]
# 红石收发器最大频道数
max_channels = 16
```

## 验证安装

启动游戏后，在创造模式物品栏搜索 `CCPE` 或 `外设扩展`，应该能看到：

- 微型外设扩展器 (Micro Peripheral Extender)
- 红石收发器 (Redstone Transceiver)
- 电子变速箱 (Electronic Transmission)

## 下一步

- [第一个脚本](first-script.md) — 快速上手无线方块读取
- [外设扩展器概述](../peripheral-extender/overview.md) — 了解详细功能
