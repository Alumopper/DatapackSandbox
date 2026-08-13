# VS Code 扩展

## 适用场景

希望在打开数据包时直接获得 `.mcfunction` 着色、诊断、补全、悬停和函数跳转，并在编辑器内运行 `.mcfunction`、执行 `.dps.json`、设置 trace 断点或检查活动世界时，使用 VS Code 扩展。

## 前置条件

需要 VS Code 1.95 或更高版本和 Java 25；可分发 VSIX 已内置 CLI JAR。

## 最小可运行示例

从 Marketplace 安装 `Alumopper.datapack-sandbox-vscode`，打开包含 `pack.mcmeta` 的数据包目录和任意 `.mcfunction`。着色会立即出现，JVM 语言会话随后在后台加载当前数据包；不需要先点击 **Start sandbox**。

## 完整能力

Datapack Sandbox for VS Code 将数据包运行、测试、trace 调试和沙盒检查直接集成进编辑器。

## `.mcfunction` 语言支持

扩展采用两层语言支持：TextMate grammar 在文件打开时立即处理注释、命令根、`execute ... run` 后的命令、selector、坐标、资源位置、字符串、数字、SNBT/JSON 括号和函数宏；随后，独立的 DSB JVM `serve` 会话提供 profile 感知的诊断、补全与悬停。语法着色不要求 Java 已经启动，智能能力也不依赖状态栏中的持久活动沙盒。

它提供与 Spyglass 同类的编辑体验，但没有嵌入或代理 Spyglass language server。DSB 使用自己的命令目录、补全引擎、数据包 loader 和 `checkCommands` 校验，因此编辑器提示与实际 DSB JVM 能运行的 Minecraft profile、命令行为等级和资源优先级一致；仓库中的 `@spyglassmc/mcdoc` 仍只负责构建期 vanilla NBT schema 生成。

打开文件后可直接使用：

- 对整篇 `.mcfunction` 做防抖校验，Problems 项定位到对应物理行；多行续写会按运行时相同的反斜杠规则合并。
- 对命令根、子命令、selector、方块、物品、实体类型、函数和当前数据包资源补全；接受会插入空格、`:`、`=`、`{` 或 `[` 的候选后会立即打开下一阶段补全，空 `{}`/`[]` 模板会把光标留在内部。建议中显示 DSB behavior level 与当前 profile。
- 悬停命令根查看 usage/profile，悬停 selector 查看执行语义，悬停数据包资源位置查看解析情况。
- 在活动资源索引可以解析的字面量资源 ID 上执行 **Go to Definition** 或 Ctrl+单击：支持目录数据包中的函数/标签、战利品表、谓词、进度、配方、item modifier 及其他有实际文件的资源。
- 对 `.mcfunction` 中不合法的前导 `/` 给出错误，并提供首选 Quick Fix 直接删除。宏命令仍会着色和导航，但含 `$(...)` 的整行不会在缺少调用参数时产生误报。

语言会话首先使用 `datapackSandbox.defaultVersion`；若为空，则读取当前数据包 `pack.mcmeta` 的 `pack_format` 并选择匹配的最新内置 profile；无法匹配时使用 CLI 的规范默认 profile。保存、新建、删除或重命名 `.mcfunction`/数据资源时会复用现有 JVM 会话执行 reload 并刷新资源索引；修改 `pack.mcmeta` 才会因 profile 可能变化而重建会话。ZIP 数据包可以参与补全和校验，但 VS Code 无法直接跳转到 ZIP 内部定义。

## 安装

扩展要求 VS Code 1.95 或更高版本以及 Java 25。Marketplace `0.4.1` 与可分发 VSIX 都内置 Datapack Sandbox CLI JAR，因此用户不需要克隆仓库、执行 Gradle 或配置 `cliJarPath`。沙盒执行、校验、检查点和渲染始终由 JVM JAR 完成；扩展不会切换到浏览器运行时。

安装 Marketplace 公开版本：

```powershell
code --install-extension Alumopper.datapack-sandbox-vscode
```

也可以打开 [VS Code Marketplace 中的 Datapack Sandbox](https://marketplace.visualstudio.com/items?itemName=Alumopper.datapack-sandbox-vscode) 并点击 **Install**。通过 Marketplace 安装后可正常接收 VS Code 扩展更新。

需要离线安装或固定版本时，从对应的 [GitHub Release](https://github.com/Alumopper/DatapackSandbox/releases/tag/1.1.0) 下载 VSIX，在扩展视图中选择 **从 VSIX 安装…**，然后选择：

```text
datapack-sandbox-vscode.vsix
```

对应的离线终端命令是：

```powershell
code --install-extension .\datapack-sandbox-vscode.vsix
```

如果 Java 25 不在 `PATH` 中，请设置 `datapackSandbox.javaPath`。

## 快速开始

1. 打开包含数据包的工作区和一个 `.mcfunction`，确认着色、Problems、补全和悬停正常工作。
2. 需要执行世界时点击状态栏中的 **DPS**。
3. 确认 Minecraft Profile 和数据包路径，然后选择 **Start sandbox**。
4. 输入命令。面板会根据活动沙盒提供补全，并在不修改活动世界的隔离副本中检查命令。
5. 使用 Inspector 查看覆盖率、富文本输出、命令与玩家事件 trace、snapshot、资源、玩家、实体、计分板和诊断。历史事件会从 JAR 增量读取。
6. 使用 **Save point** / **Return** 保存和恢复可复用检查点，使用 **Render PNG** 调用 JVM 渲染器，使用 **Reset coverage** 开始新的测量窗口，或使用 **Interrupt** 在命令边界停止长时间执行。

补全列表支持鼠标选择，也支持 <kbd>↑</kbd>/<kbd>↓</kbd> 切换以及 <kbd>Tab</kbd> 或 <kbd>Enter</kbd> 接受。Inspector JSON 可以逐层展开；带源码位置的 trace 可以直接跳回对应 `.mcfunction` 行。函数命令还能打开按数据包优先级实际生效的源码；ZIP 或 synthetic function 会作为只读编辑器文档打开。

Profile 下拉框直接读取内置 CLI 的唯一版本注册表。通过命令面板启动沙盒时也会显示同一份动态列表。

## 在 VS Code 中使用 Jupyter Notebook

除本扩展外，还需要安装 Microsoft 的 **Python** 和 **Jupyter** 扩展。选择安装了 Datapack Sandbox wheel 与 kernelspec 的 Python 解释器，打开 `examples/jupyter/datapack-sandbox-demo.ipynb`，点击 **Select Kernel** 并选择 **Datapack Sandbox (MCFunction)**。

Notebook Kernel 会在多个单元格之间保留同一个沙盒世界，并在每个成功的 MCF 单元格下方显示内嵌 PNG。Spyglass 风格的编辑与诊断适用于独立 `.mcfunction` 文件，不会自动注入 Notebook 单元格。

## 临时沙盒与活动沙盒

| 模式 | 适用场景 | 状态生命周期 |
| --- | --- | --- |
| Temporary sandbox | 常规 Run、Debug 和隔离测试 | 每次执行都创建新沙盒 |
| Active sandbox | 连续命令、共享世界调试和交互检查 | 保留到手动停止或重置 |

常规 **Run** 和 **Debug** 默认使用临时沙盒，以免上一次执行污染结果。只有在确实需要持久状态时，才将 `datapackSandbox.defaultExecutionTarget` 设置为 `active`。

活动沙盒可通过以下命令控制：

- `Datapack Sandbox: Start Sandbox`
- `Datapack Sandbox: Stop Sandbox`
- `Datapack Sandbox: Open Sandbox Panel`
- `Datapack Sandbox: Run Current Mcfunction in Active Sandbox`
- `Datapack Sandbox: Debug Current File in Active Sandbox`
- `Datapack Sandbox: Save Checkpoint`
- `Datapack Sandbox: Restore Checkpoint`
- `Datapack Sandbox: Delete Checkpoint`
- `Datapack Sandbox: Render Active Sandbox to PNG`
- `Datapack Sandbox: Show Active Sandbox Coverage`
- `Datapack Sandbox: Reset Active Sandbox Coverage`
- `Datapack Sandbox: Open Loaded Function Source`
- `Datapack Sandbox: Interrupt Active Execution`

检查点包含完整的建模世界、输出和 trace，可重复恢复；数据包资源和单调递增的安全预算不属于检查点状态。

## 运行与测试

打开 `.mcfunction` 后，可以使用编辑器标题按钮、CodeLens 或命令面板运行当前函数。打开 `.dps.json` 后，可以执行普通或 Strict 检查。

Test Explorer 会发现工作区中的 `**/*.dps.json`，并提供四个 Profile：

- Run in Temporary Sandbox（默认）
- Run Strict in Temporary Sandbox
- Run in Active Sandbox
- Run Strict in Active Sandbox

活动沙盒 Profile 会按执行顺序保留世界变化；需要完全隔离的回归测试应继续使用默认临时 Profile。

## Trace 调试

在 `.mcfunction` 中设置断点后启动 **Datapack Sandbox Trace Debug**。调试器默认先执行到第一个断点，而不是无条件停在第一行。

调试侧边栏中的 Trace 和 Final State 以可展开对象展示，包括：

- 当前 trace、命令结果、输出事件和源码位置
- 诊断与 snapshot diff
- 最终玩家、实体、计分板、storage 和资源状态

如果需要在第一条 trace 处暂停，请在 `launch.json` 中显式设置：

```json
{
  "type": "datapack-sandbox",
  "request": "launch",
  "name": "Datapack Sandbox Trace",
  "program": "${file}",
  "sandbox": "temporary",
  "stopOnEntry": true
}
```

## 设置

| 设置 | 默认值 | 用途 |
| --- | --- | --- |
| `datapackSandbox.javaPath` | `java` | Java 25 可执行文件 |
| `datapackSandbox.defaultVersion` | 空 | 可选 Profile 覆盖；留空时跟随内置 CLI 默认值 |
| `datapackSandbox.packPaths` | `[]` | 额外数据包目录或 ZIP |
| `datapackSandbox.language.enabled` | `true` | 独立 JVM 语言会话的诊断、补全、悬停和跳转；关闭后仍保留 TextMate 着色 |
| `datapackSandbox.language.diagnostics` | `true` | 对打开的 `.mcfunction` 做整篇 profile 感知检查 |
| `datapackSandbox.language.diagnosticDelay` | `300` | 输入停止后启动检查的防抖毫秒数 |
| `datapackSandbox.defaultPlayerName` | `Steve` | 新活动沙盒启动时创建的玩家；留空则不创建 |
| `datapackSandbox.strict` | `false` | Run/Debug 是否启用严格检查 |
| `datapackSandbox.defaultExecutionTarget` | `temporary` | Run/Debug 的默认执行目标 |
| `datapackSandbox.cliJarPath` | 空 | 自定义 CLI JAR；留空时使用扩展内置 JAR |
| `datapackSandbox.coverage.*` | `0`、`0`、`[]`、`[]` | 行/函数阈值与资源 id include/exclude glob |
| `datapackSandbox.render.width` / `.height` | `960` / `540` | JAR 渲染 PNG 的尺寸 |
| `datapackSandbox.render.fieldOfView` | `70` | 渲染相机视野 |
| `datapackSandbox.render.distance` | `128` | 建模渲染距离（方块） |
| `datapackSandbox.render.minecraftAssetsPath` | 空 | 手动选择的本地 Minecraft 客户端资源目录或 JAR |
| `datapackSandbox.render.resourcePackPaths` | `[]` | 额外本地资源包 |
| `datapackSandbox.render.playerSkins` | `{}` | 玩家名到本地 PNG 皮肤路径的映射 |
| `datapackSandbox.render.camera*` | 自动 | 玩家、实体 UUID 或固定位置/yaw/pitch/dimension 相机 |
| `datapackSandbox.render.transparentBackground` / `.showHud` / `.showDebugOverlay` | `false` | 可选画面图层 |
| `datapackSandbox.render.strictAssets` | `false` | 配置的渲染资源无法解析时是否失败 |

扩展不会搜索 Minecraft 安装目录或启动器 metadata 来寻找客户端资源。请自行配置 `render.minecraftAssetsPath`、资源包和皮肤；这些路径只会传给本地 JVM 渲染器。没有配置时仍可使用建模 fallback 资产。

## 常见问题

### 状态栏显示 Stopped

这只表示当前没有持久活动沙盒。普通 Run、Debug、临时测试以及独立 `.mcfunction` 语言支持仍可正常工作。点击 DPS 并启动沙盒后可额外使用控制面板和活动沙盒测试。

### 补全没有出现

独立 `.mcfunction` 补全不要求启动活动沙盒。先确认语言模式为 **Minecraft Function**、`datapackSandbox.language.enabled` 为 `true`、Java 25 可用，并在 **Datapack Sandbox** 输出通道查看语言会话启动错误。只有控制面板补全才要求活动沙盒和 **Command** 操作类型。

### Java 启动失败

运行 `java -version` 确认当前 Java 为 25，或将 `datapackSandbox.javaPath` 指向正确的可执行文件。启动错误会写入 **Datapack Sandbox** 输出通道。

错误面板会区分 Java 不存在、CLI JAR 缺失、启动超时、版本不匹配、资源缺失、中断和命令失败，并提供错误代码、相关 Profile 或命令以及建议操作。Serve 返回 partial execution 时，还会显示已完成的命令边界及保留的 output、trace、玩家事件 trace 和状态变化数量。

### 调试没有停在断点

确认断点位于实际产生 trace 的命令行，并检查启动配置中的 `program` 是否指向预期 `.mcfunction` 或 `.dps.json`。`stopOnEntry` 默认为 `false`。

## 开发与打包

扩展源码位于 `vscode/`：

```powershell
.\gradlew.bat :cli:fatJar
cd vscode
npm install
npm test
npm run package
```

输出文件为 `build/datapack-sandbox-vscode.vsix`，发布者应为 **Alumopper**。打包脚本会复制刚构建的 standalone CLI JAR；不会嵌入浏览器运行时或 Mojang server JAR。

## 限制

TextMate 着色可以离线工作；profile 感知诊断、补全、悬停和索引资源跳转是 JVM CLI 的编辑器前端，需要本机 Java 25。定义跳转只打开目录数据包中有实际文件的资源，不打开 ZIP 内部条目或内置注册表值；宏行在没有实参时跳过语义诊断。Notebook 单元格不自动获得独立 `.mcfunction` 文件的语言能力。

## 相关页面

- [安装与获取](/workflows/installation)
- [Jupyter Notebook](/integrations/jupyter)
- [CLI 参考](/reference/cli)
- [Serve JSONL 协议](/reference/serve-jsonl)
