# VS Code 插件

Datapack Sandbox for VS Code 将运行、测试、断点调试和沙盒检查直接带进编辑器。插件由 **Alumopper** 发布，扩展 ID 为 `Alumopper.datapack-sandbox-vscode`。

## 安装

插件要求 VS Code 1.95 或更高版本，以及 Java 25。可分发的 VSIX 已经内置 Datapack Sandbox CLI，不需要另外克隆仓库或执行 Gradle 构建。

在 VS Code 中打开扩展视图，选择 **从 VSIX 安装…**，然后选择：

```text
datapack-sandbox-vscode.vsix
```

也可以从终端安装：

```powershell
code --install-extension .\datapack-sandbox-vscode.vsix
```

如果 Java 25 不在 `PATH` 中，在设置里填写 `datapackSandbox.javaPath`。

## 快速开始

1. 打开一个包含数据包的工作区。
2. 点击状态栏中的 **DPS**。
3. 在控制面板中确认 Minecraft Profile 和数据包路径，然后点击 **Start sandbox**。
4. 在命令框中输入命令。输入过程中会显示基于当前沙盒资源和世界状态的补全，同时进行不会修改活动世界的预检查。
5. 使用 Inspector 查看输出、trace、snapshot、资源、玩家、实体、计分板和诊断。

命令补全支持鼠标选择，也支持 `↑`、`↓` 切换，按 `Tab` 或 `Enter` 接受建议。Inspector 中的 JSON 可以逐层展开；带源码位置的 trace 可以直接跳回 `.mcfunction` 文件。

## 临时沙盒与活动沙盒

插件提供两种执行目标：

| 模式 | 适用场景 | 状态是否保留 |
| --- | --- | --- |
| Temporary sandbox | 普通 Run、Debug 和隔离测试 | 每次执行都创建新沙盒 |
| Active sandbox | 连续输入命令、调试共享世界状态、交互检查 | 保留到手动停止或重置 |

普通 **Run** 和 **Debug** 默认使用临时沙盒，避免上一次运行污染结果。若希望默认使用活动沙盒，将 `datapackSandbox.defaultExecutionTarget` 设置为 `active`。

活动沙盒可以通过命令面板中的以下命令控制：

- `Datapack Sandbox: Start Sandbox`
- `Datapack Sandbox: Stop Sandbox`
- `Datapack Sandbox: Open Sandbox Panel`
- `Datapack Sandbox: Run Current Mcfunction in Active Sandbox`
- `Datapack Sandbox: Debug Current File in Active Sandbox`

## 运行与测试

打开 `.mcfunction` 后，可以使用编辑器顶部按钮、CodeLens 或命令面板运行当前函数。打开 `.dps.json` 后，可以运行普通检查或 Strict 检查。

Test Explorer 会发现工作区中的 `**/*.dps.json`，并提供四个 Profile：

- Run in Temporary Sandbox（默认）
- Run Strict in Temporary Sandbox
- Run in Active Sandbox
- Run Strict in Active Sandbox

活动沙盒 Profile 会按执行顺序保留世界变化；需要完全隔离的回归测试应继续使用默认临时 Profile。

## 断点调试

在 `.mcfunction` 中设置断点后启动 **Datapack Sandbox Trace Debug**。调试器默认先执行到第一个断点，不会在第一行无条件暂停。

调试侧边栏中的 Trace 和 Final State 以可展开对象展示，包括：

- 当前 trace、命令结果和源码位置
- 输出和诊断
- snapshot diff
- 最终玩家、实体、计分板、storage 与资源状态

如果确实希望在第一条 trace 处暂停，可在 `launch.json` 中设置：

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

## 常用设置

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `datapackSandbox.javaPath` | `java` | Java 25 可执行文件 |
| `datapackSandbox.defaultVersion` | `26.2` | 默认 Minecraft Profile |
| `datapackSandbox.packPaths` | `[]` | 额外数据包目录或 zip |
| `datapackSandbox.strict` | `false` | Run/Debug 是否使用严格检查 |
| `datapackSandbox.defaultExecutionTarget` | `temporary` | 普通 Run/Debug 的默认执行目标 |
| `datapackSandbox.cliJarPath` | 空 | 使用自定义 CLI JAR；留空时使用插件内置版本 |

## 常见问题

### 状态栏显示 Stopped

这只表示当前没有持久活动沙盒。普通 Run、Debug 和临时测试仍可正常工作。点击 DPS 并启动沙盒即可使用控制面板、活动沙盒测试和状态感知补全。

### 补全没有出现

控制面板补全依赖活动沙盒。先启动沙盒，并确认操作类型为 **Command**。`.mcfunction` 编辑器中的状态感知补全同样需要活动沙盒。

### Java 启动失败

运行 `java -version` 确认当前 Java 为 25，或把 `datapackSandbox.javaPath` 指向正确的可执行文件。插件输出面板中的 **Datapack Sandbox** 通道会记录 CLI 启动错误。

### 调试没有停在断点

确认断点位于实际产生 trace 的命令行，并检查启动配置中的 `program` 是否指向当前 `.mcfunction` 或 `.dps.json`。默认 `stopOnEntry` 为 `false`。

## 开发与打包

仓库中的插件源码位于 `vscode/`。本地验证和打包命令为：

```powershell
.\gradlew.bat :cli:fatJar
cd vscode
npm install
npm test
npm run package
```

输出文件为 `build/datapack-sandbox-vscode.vsix`，包内发布者应显示为 **Alumopper**。
