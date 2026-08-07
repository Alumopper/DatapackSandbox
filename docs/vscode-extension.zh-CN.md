# VS Code 扩展

Datapack Sandbox for VS Code 将数据包运行、测试、trace 调试和沙盒检查直接集成进编辑器。

## 安装

扩展要求 VS Code 1.95 或更高版本以及 Java 25。可分发的 VSIX 内置 Datapack Sandbox CLI JAR，因此用户不需要克隆仓库或执行 Gradle 构建。沙盒执行、校验、检查点和渲染始终由 JVM JAR 完成；扩展不会切换到浏览器运行时。

在扩展视图中选择 **从 VSIX 安装…**，然后选择：

```text
datapack-sandbox-vscode.vsix
```

也可以从终端安装：

```powershell
code --install-extension .\datapack-sandbox-vscode.vsix
```

如果 Java 25 不在 `PATH` 中，请设置 `datapackSandbox.javaPath`。

## 快速开始

1. 打开包含数据包的工作区。
2. 点击状态栏中的 **DPS**。
3. 确认 Minecraft Profile 和数据包路径，然后选择 **Start sandbox**。
4. 输入命令。面板会根据活动沙盒提供补全，并在不修改活动世界的隔离副本中检查命令。
5. 使用 Inspector 查看富文本输出、命令与玩家事件 trace、snapshot、资源、玩家、实体、计分板和诊断。历史事件会从 JAR 增量读取。
6. 使用 **Save point** / **Return** 保存和恢复可复用检查点，使用 **Render PNG** 调用 JVM 渲染器，或使用 **Interrupt** 在命令边界停止长时间执行。

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
| `datapackSandbox.strict` | `false` | Run/Debug 是否启用严格检查 |
| `datapackSandbox.defaultExecutionTarget` | `temporary` | Run/Debug 的默认执行目标 |
| `datapackSandbox.cliJarPath` | 空 | 自定义 CLI JAR；留空时使用扩展内置 JAR |
| `datapackSandbox.render.width` / `.height` | `960` / `540` | JAR 渲染 PNG 的尺寸 |
| `datapackSandbox.render.fieldOfView` | `70` | 渲染相机视野 |
| `datapackSandbox.render.distance` | `128` | 建模渲染距离（方块） |
| `datapackSandbox.render.minecraftAssetsPath` | 空 | 可选本地 Minecraft 客户端资源目录或 JAR |
| `datapackSandbox.render.resourcePackPaths` | `[]` | 额外本地资源包 |
| `datapackSandbox.render.strictAssets` | `false` | 配置的渲染资源无法解析时是否失败 |

## 常见问题

### 状态栏显示 Stopped

这只表示当前没有持久活动沙盒。普通 Run、Debug 和临时测试仍可正常工作。点击 DPS 并启动沙盒即可使用控制面板、活动沙盒测试和状态感知补全。

### 补全没有出现

控制面板补全和 `.mcfunction` 状态感知补全都依赖活动沙盒。请先启动沙盒，并确认面板操作类型为 **Command**。

### Java 启动失败

运行 `java -version` 确认当前 Java 为 25，或将 `datapackSandbox.javaPath` 指向正确的可执行文件。启动错误会写入 **Datapack Sandbox** 输出通道。

错误面板会区分 Java 不存在、CLI JAR 缺失、启动超时、版本不匹配、资源缺失和命令失败，并提供错误代码、相关 Profile 或命令以及建议操作。

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
