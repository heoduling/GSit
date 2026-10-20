# [GSit](https://github.com/gecolay/GSit) - [GPlugin](https://discord.gg/Cy2P4AU)

[![Release](https://img.shields.io/github/v/release/heoduling/GSit)](https://github.com/heoduling/GSit/releases/latest)
[![Target](https://img.shields.io/badge/target-Shiroha%20%2F%20Folia%2026.2-blue)](#shirohafolia-262-fork)
[![License](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)

> [!IMPORTANT]
> This is an unofficial compatibility fork of [gecolay/GSit](https://github.com/gecolay/GSit), focused on Shiroha/Folia 26.2. For the original project, general downloads, and upstream support, use the links below.

### 最新版本 · 3.6.0.4

[下载插件](https://github.com/heoduling/GSit/releases/tag/v3.6.0.4-shiroha26.2) · [更新与验证记录](docs/releases/3.6.0.4.md) · [上游项目](https://github.com/gecolay/GSit)

只优化一条路径：没有坐躺对象的方块被活塞推动时，不再创建无用集合。保留既有命令、权限、配置、坐躺和时装联动行为；没有修改发包逻辑。

| 验证范围 | 结果 |
| --- | --- |
| 自动测试 | 33 项通过 |
| 隔离服回归 | 16 个协议客户端、两个实际区域、320 次躺下/起身切换通过 |
| 无坐躺对象的活塞局部基准 | 分配量减少 97.61%，耗时减少 33.45%（非整服收益） |
| 装备包数量中位数 | 前后相同，减少 0% |
| Hibiscus/HMC 组合热替换 | **失败，必须完整重启替换** |

> [!WARNING]
> 安装 HibiscusCommons / HMCCosmetics 的服务器：完整停服，备份后替换 JAR，再启动。即使已冷启动安装新版，也不能据此单独热卸载/加载 GSit；时装插件会保留旧事件绑定。PlugMan 显示加载成功不等于联动正常。详细测试范围与未验证项见发布说明。

## Shiroha/Folia 26.2 fork

The `folia-shiroha-26.2` branch contains narrowly scoped Folia lifecycle fixes:

- owner-safe PlayerSit marker cleanup across entity owners;
- owner-aware cleanup for active Seat, PlayerSit, Pose, and Crawl state;
- guarded PlugManX reload/unload support for the exact tested Shiroha 26.2 path;
- cleanup of GSit-owned tasks, listeners, Netty handlers, database state, metrics, and command help references;
- focused regression coverage for every PlayerSit stop direction and stop reason.

### Deployment boundary

Hot reload is supported only when no enabled plugin declares a dependency on GSit. If `HibiscusCommons` is installed, stop the whole server, replace the GSit JAR, and start normally. Reloading only GSit would leave the old third-party event listener bound to the old GSit class loader.

This release targets Shiroha/Folia 26.2. It is not a claim of general Paper, Spigot, or arbitrary plugin-manager compatibility.

## Overview

This repository contains the GSit project!

- Download: [GSit - Modrinth](https://modrinth.com/plugin/gsit)
- Download: [GSit - Spigot](https://www.spigotmc.org/resources/GSit.62325)
- Download: [GSit - PaperMC Hanger](https://hangar.papermc.io/gecolay/GSit)
- GitHub: [GSit - GitHub](https://github.com/gecolay/GSit)
- Discord: [GPlugins - Discord](https://discord.gg/Cy2P4AU)

## Local development

### Local project

Clone the repository:
```bash
git clone https://github.com/heoduling/GSit.git
git switch folia-shiroha-26.2
```

### Build

Build the Shiroha/Folia 26.2 artifact:

```bash
./gradlew :clean :core:clean :v26_2:clean :check :core:check :v26_2:check :shadowJarDev -PtargetVersion=v26_2 --configure-on-demand
```

The final `GSit-x.x-x.jar` file will be in the [`build/libs`](./build/libs) folder.

The 3.6.0.4 release reuses the exact artifact tested on 2026-10-16; its SHA-256 and the local dependency pins used for that build are recorded in the [release notes](docs/releases/3.6.0.4.md). Dynamic upstream dependencies can otherwise resolve differently on a later build. Java 25 is required for the selected target and tests.

## Pull requests

You can create a pull request to submit your code to this repository: [Pull requests](https://github.com/gecolay/GSit/pulls)
