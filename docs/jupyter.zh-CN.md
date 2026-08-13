# Jupyter 集成

## 适用场景

当你希望在 Notebook 中逐 cell 执行原生 MCFunction、保留世界状态，并把命令输出、snapshot diff 和 PNG 穿插在分析记录中时使用 Jupyter kernel。只需要静态图片、GIF 或桌面视窗时，直接使用 [渲染、动图与实时视窗](/guide/rendering-notebook)。

## 前置条件

- Python 3.10 或更新版本，以及 JupyterLab 或支持自定义 kernel 的 Notebook 前端。
- Java 25。Release wheel 已内置兼容的 CLI JAR，但不包含 Minecraft 资产。
- 从 checkout 安装时，先构建 `cli/build/libs/datapack-sandbox-cli.jar`。

## 最小可运行示例

从 GitHub Release 安装 wheel，并注册 kernelspec：

```powershell
python -m pip install --upgrade .\datapack_sandbox_kernel-<version>-py3-none-any.whl jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab --no-browser
```

选择 **Datapack Sandbox (MCFunction)**，先应用配置，再执行 MCF：

```text
%dps version 26.2
%dps config autoRender true
%dps reset --apply
```

```mcfunction
scoreboard objectives add runs dummy
scoreboard players add #notebook runs 1
say notebook ready
```

成功的 MCF cell 会显示可读摘要、结构化 metadata，并默认内嵌 PNG。

## 完整能力

### 从 checkout 安装

```powershell
.\gradlew.bat prepareJupyterKernel
$env:DPS_CLI_JAR = ".\cli\build\libs\datapack-sandbox-cli.jar"
python -m pip install -e ".\jupyter[test]" jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab examples/jupyter/datapack-sandbox-demo.ipynb
```

`DPS_JAVA` 可显式选择 Java 25 executable。用 `jupyter kernelspec list` 确认 `datapack-sandbox` 已注册。VS Code 需要安装 Microsoft Python 与 Jupyter 扩展，并选择安装 wheel 的同一 Python 解释器。

### `%dps` magics

| 指令 | 作用 |
| --- | --- |
| `%dps version <id>` | 设置 Minecraft profile；已打开世界时要求 reset |
| `%dps pack <path>` / `%dps packs` | 添加或列出数据包 |
| `%dps assets <path>` | 设置 client JAR 或 `assets/` 目录 |
| `%dps resource-pack <path>` | 叠加渲染资源包 |
| `%dps skin <player> <path>` | 为渲染中的玩家指定本地 PNG 皮肤 |
| `%dps world <fixture.json>` | 将 world fixture 应用到当前世界 |
| `%dps camera <mode...>` | 选择自动、玩家、实体 UUID 或固定位置相机 |
| `%dps tick <count>` | 推进 tick |
| `%dps function <id>` | 执行已加载函数 |
| `%dps load` / `%dps event <event text>` | 执行 load 函数或注入玩家事件 |
| `%dps checkpoint [list\|save\|restore\|delete] [name]` | 管理可复用的内存检查点 |
| `%dps coverage [options]` / `%dps reset-coverage` | 查看或重置累计行/函数覆盖率 |
| `%dps render [output.png]` | 内嵌 PNG，并可同时保存文件 |
| `%dps snapshot` / `%dps outputs` / `%dps traces` / `%dps event-traces` | 检查世界状态和执行历史 |
| `%dps resources` / `%dps function-source <id>` | 检查资源优先级与实际生效函数源码 |
| `%dps reload [--discard-world]` / `%dps reset-world` | 重载数据包或只替换建模世界 |
| `%dps config <option> <bool>` | 控制自动渲染、透明背景、HUD 与调试覆盖层 |
| `%dps status` / `%dps help` | 显示会话状态或帮助 |
| `%dps reset --apply` | 用待处理的版本与 pack 配置重建世界 |

控制行可以与 MCF 放在一个 cell 中。更改 `version` 或 `pack` 后不会静默丢弃旧世界；在 `%dps reset --apply` 前执行会返回 `RESET_REQUIRED`。普通 cell 会生成 `notebook:cell_<execution-count>` 函数，在同一持久 Serve 会话中运行。

### 配置与渲染缓存

项目根目录可放置 `.dps-kernel.json`，字段包括 `version`、`packs`、`minecraftAssets`、`resourcePacks`、`playerSkins`、`defaultPlayer`、`cameraPlayer` 或 `cameraEntity`、`autoRender`、`strict`，以及 `render` 中的尺寸、FOV、distance、固定相机与覆盖层选项。优先级从高到低为 Notebook `%dps`、项目配置、环境变量、用户配置、内置默认值。

客户端资源采用显式配置。Kernel 不会搜索 `.minecraft`、启动器 Profile 或已经安装的游戏版本；请自行设置 `minecraftAssets` / `DPS_MINECRAFT_ASSETS`，或运行 `%dps assets <path>`，并分别指定资源包与玩家皮肤。未配置时渲染器使用建模 fallback 材质，并把资产来源与诊断写入 render metadata。

自动渲染会复用世界 revision、相机、资产和设置均未变化的 PNG，并在 metadata 中标记 `render.reused`。失败或中断可能已经改变世界，因此会使缓存失效。

### 中断与错误恢复

kernel 通过 message-mode interrupt 请求 Serve 在下一条命令边界取消。返回 `EXECUTION_INTERRUPTED` 时，已完成命令及其 outputs、traces、snapshot diffs 会作为 partial result 保留；这不是事务回滚。

如果 JVM 进程意外退出，当前 cell 返回 `SESSION_LOST`。kernel 不会假装恢复旧世界；修复原因后执行 `%dps reset --apply` 创建明确的新世界。普通命令错误不会关闭会话，后续 cell 仍可执行。

## 限制

- Notebook 前端未必把自定义 kernel 当作完整语言服务；补全协议可用，但独立 `.mcfunction` 的完整诊断体验仍以 [VS Code 扩展](/guide/vscode-extension)为主。
- 重跑 cell 会再次修改当前世界，不会恢复到该 cell 上次执行前的状态。
- Wheel、VSIX 和 standalone JAR 不携带、也不会自动发现 Mojang client/server JAR 或资源包；只能引用用户显式配置的本地资产。
- Notebook 不应提交绝对本地路径或大型二进制 pack。

## 相关页面

- [Serve JSONL 协议](/reference/serve-jsonl)
- [渲染、动图与实时视窗](/guide/rendering-notebook)
- [VS Code 扩展](/guide/vscode-extension)
- [报告与可观测性](/reference/reports-observability)
