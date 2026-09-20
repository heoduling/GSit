# [GSit](https://github.com/gecolay/GSit) - [GPlugin](https://discord.gg/Cy2P4AU)

> [!IMPORTANT]
> This is an unofficial compatibility fork of [gecolay/GSit](https://github.com/gecolay/GSit), focused on Shiroha/Folia 26.2. For the original project, general downloads, and upstream support, use the links below.

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
./gradlew clean :core:test :v26_2:build shadowJarDev -PtargetVersion=v26_2
```

The final `GSit-x.x-x.jar` file will be in the [`build/libs`](./build/libs) folder.

## Pull requests

You can create a pull request to submit your code to this repository: [Pull requests](https://github.com/gecolay/GSit/pulls)
