---
name: minecraft-mod-source-lookup
description: '查找 Minecraft NeoForge/Fabric mod 依赖的源代码。当需要查看其他 mod（如 Create、CC:Tweaked、Sable、JEI、Flywheel 等）的 API 或实现源码时使用此技能。源码存放于工作区根目录下的 sources/ 或 libs/ 文件夹中。用于：理解 mod API 方法签名、追踪调用链、调试兼容性问题、阅读 mixin 目标类、理解网络协议。'
---

# Minecraft Mod 依赖源码查找

## 何时使用

- 需要查看某个依赖 mod 的类、方法或接口的源代码
- 需要理解 Create、CC:Tweaked、Sable、JEI 等 mod 的 API 行为
- 调试与依赖 mod 的兼容性问题
- 追踪跨 mod 的调用链或 mixin 目标
- 了解某个 mod 的网络包、事件或注册机制

## 源码位置

工作区根目录下有专门的源码目录：

| 目录 | 用途 |
|------|------|
| `sources/` | 解压后的 mod 源码 jar（`-sources.jar`），按 mod 名称分文件夹 |
| `libs/` | 部分 mod 的源码可能放在此处 |

源码文件夹命名格式通常为：`<mod-name>-<mc_version>-<mod_version>-sources/`

例如：
- `sources/create-1.21.1-6.0.10-280-sources/`
- `sources/cc-tweaked-1.21.1-common-api-1.118.0-sources/`
- `sources/sable-neoforge-1.21.1-2.0.3-sources/`
- `sources/jei-1.21.1-common-api-19.42.0.379-sources/`

## 查找步骤

### 1. 确认目标 mod

根据用户问题或当前代码中的 import 语句确定需要查看哪个 mod 的源码。

常见 import 前缀与对应源码文件夹：

| Import 前缀 | 源码文件夹关键词 |
|-------------|-----------------|
| `com.simibubi.create` | `create-` |
| `dan200.computercraft` | `cc-tweaked-` |
| `dev.ryanhcode.sable` | `sable-` |
| `mezz.jei` | `jei-` |
| `net.createmod.catnip` | `create-` |
| `dev.engine_room.flywheel` | `flywheel`（在 sable 源码内） |

### 2. 在 sources/ 中搜索

使用 `file_search` 或 `grep_search` 工具，限定搜索范围为对应 mod 的源码目录。

示例 — 搜索 Create 的 RedstoneLinkNetworkHandler：
```
grep_search: includePattern = "sources/create-*/**/*.java", query = "RedstoneLinkNetworkHandler"
```

示例 — 搜索 Sable 的 Pose3dc：
```
grep_search: includePattern = "sources/sable-*/**/*.java", query = "transformPosition"
```

### 3. 阅读源码文件

找到目标文件后，使用 `read_file` 读取完整内容。Minecraft mod 的源码目录结构通常遵循标准 Java 包结构：

```
sources/<mod>-sources/
├── META-INF/
├── <mod>.mixins.json
├── assets/
├── data/
└── <com|net|dev>/
    └── <author>/
        └── <mod>/
            └── ...
```

### 4. 必要时使用 GitHub

如果源码不在 `sources/` 中（例如仅依赖了编译后的 jar），可使用 `github_repo` 工具在线查找。常见的 Create mod 仓库：

- Create: `Creators-of-Create/Create`（分支 `mc1.21.1/dev`）

## 注意事项

- `sources/` 中的源码是解压后的 `-sources.jar`，可能不包含完整注释
- 部分 mod 的 API 和主代码在不同的 sources jar 中（如 CC:Tweaked 分为 `common-api`、`core-api`、`forge-api`）
- Sable 的 `sable-companion-common`（API 接口定义）和 `sable-neoforge`（实现）是两个独立的 sources jar
- 如果源码不在工作区中，可以使用 `github_text_search` 或 `github_repo` 在线搜索
