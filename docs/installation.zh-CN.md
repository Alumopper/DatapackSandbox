# 安装与获取

## 适用场景

不知道项目该接哪个 Datapack Sandbox 产物时，从本页开始。数据包作者和 CI 通常使用独立 CLI；Kotlin/Java 测试使用 `testkit`，自行管理沙盒生命周期的应用使用 `core`，需要生成图像的 JVM 应用再加入 `renderer`。VS Code 扩展、Jupyter kernel 和浏览器 Playground 是建立在这些运行时入口之上的独立集成。

## 前置条件

- 运行已发布的 JVM 产物需要 Java 25。遇到 class-file 错误时先执行 `java -version`。
- 从源码构建 JVM 模块同样需要 JDK 25 toolchain。
- 从源码构建文档或 Web Playground 需要 Node.js 与 npm；仓库 lockfile 使用 `npm ci` 安装。
- 本文对应发布版本 `1.1.0`。内置 Minecraft profile 覆盖 `1.20.4` 至 `26.2`，默认是 `26.2`。

## 选择产物

| 需求 | 产物 | 使用方式 |
| --- | --- | --- |
| 运行 pack、Manifest、REPL、视窗或 JSONL 服务 | CLI fat jar | `java -jar datapack-sandbox-cli.jar ...` |
| 在 JVM 测试套件中编写 fluent 测试 | `testkit` | Gradle/Maven 测试依赖 |
| 构建自定义 JVM 宿主 | `core` | Gradle/Maven 应用依赖 |
| 渲染 PNG/GIF 或编译实时场景 | `renderer` + `core` | Gradle/Maven 应用依赖 |
| 在 VS Code 中编辑与调试数据包 | VS Code 扩展 `0.4.1` | 从 Marketplace 安装 `Alumopper.datapack-sandbox-vscode`；已内置匹配的 CLI jar |
| 运行持久化 Notebook cell | Jupyter kernel | 安装 Python wheel；已内置匹配的 CLI jar |
| 在网页嵌入本地执行 | `@datapack-sandbox/vitepress-playground` | npm 依赖和打包 Worker |

CLI fat jar 是应用分发包，不能代替 JVM 类库，也不应作为稳定 classpath 依赖。

## 最小可运行示例

在 Windows PowerShell 的仓库根目录构建独立 jar，并验证应用与 profile 目录：

```powershell
.\gradlew.bat :cli:fatJar
java -jar .\cli\build\libs\datapack-sandbox-cli.jar --help
java -jar .\cli\build\libs\datapack-sandbox-cli.jar version
```

产物位于 `cli/build/libs/datapack-sandbox-cli.jar`。再运行一个真实 smoke case：

```powershell
java -jar .\cli\build\libs\datapack-sandbox-cli.jar check `
  .\examples\single-function\single-function.dps.json `
  --validate-schema
```

成功意味着 Java 能启动 jar、内置 Manifest schema 可读，而且仓库内的实际示例能够执行。

## 安装 CLI

### 使用发布 jar

从准备采用的项目 release 下载 CLI 产物，在 CI 中固定文件名或 wrapper 路径，并用 `java -jar` 启动；无需 Mojang server jar。需要追踪 profile 漂移时，把 `version` 输出一并归档到构建日志。

### 从源码构建

```powershell
.\gradlew.bat :cli:fatJar
.\gradlew.bat :cli:smokeCliJar
```

`fatJar` 生成可执行文件。`smokeCliJar` 还会检查独立分发、schema、受保护文档表、示例、渲染和具体 CLI 样例。需要覆盖所有模块与发布产物时使用 `releaseCheck`。

## 添加 JVM 依赖

同时配置项目仓库、Maven Central 和 Mojang library repository：

```kotlin
repositories {
    maven("https://nexus.mcfpp.top/repository/maven-releases/")
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:testkit:1.1.0")
}
```

宿主只引入真正需要的模块：

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
    implementation("moe.afox.dpsandbox:renderer:1.1.0") // 仅图像/实时场景需要
}
```

`testkit` 会传递引入 `core`。在本仓库或 included multi-project build 中，改用 `project(":testkit")`、`project(":core")` 或 `project(":renderer")`。所有 Datapack Sandbox 模块应保持同一版本。

## 安装集成

### VS Code 与 Jupyter

推荐从 [VS Code Marketplace 安装 Datapack Sandbox](https://marketplace.visualstudio.com/items?itemName=Alumopper.datapack-sandbox-vscode)，也可以执行：

```powershell
code --install-extension Alumopper.datapack-sandbox-vscode
```

Marketplace 是推荐渠道，VS Code 可以正常接收扩展更新。`0.4.1` 已内置匹配的 CLI jar；只有在有意测试另一个本地构建时才设置 `datapackSandbox.cliJarPath`。Release 中仍保留 VSIX，供离线安装或固定版本使用。详见 [VS Code 扩展](/guide/vscode-extension)。

Jupyter 可安装 release wheel，也可从 `jupyter/` 构建；wheel 同样内置 CLI jar，`DPS_CLI_JAR` 只用于显式选择其他构建。详见 [Jupyter](/integrations/jupyter)。

### Web Playground

```bash
npm install @datapack-sandbox/vitepress-playground
```

同时导入组件与包内 CSS。浏览器运行时是本地 Worker，不使用 JVM jar。渲染资源也不会内置：需要原版模型与纹理时，用户必须显式导入匹配的 client JAR。完整流程见 [Playground](/guide/playground)。

## 验证与升级

替换产物后按范围递增执行：

1. `java -jar ... version` 验证启动与内置 profile 集合。
2. `java -jar ... schema --output build/dps-manifest.schema.json` 验证 schema 导出。
3. `java -jar ... check <your-cases> --strict` 验证真实项目。
4. JVM 消费者运行测试套件；Web 消费者重新构建，使 Worker bundle 与生成 profile 一并刷新。

CLI、JVM 模块坐标、编辑器后端和浏览器包要有计划地同步升级。Manifest 的 `version` 选择的是 Minecraft 行为 profile，不是 Datapack Sandbox 发布版本。

## 安装排障

| 现象 | 检查项 |
| --- | --- |
| `UnsupportedClassVersionError` | `java -version` 必须是 Java 25 |
| 找不到 main class 或 jar | 使用准确的 `cli/build/libs/datapack-sandbox-cli.jar` 路径 |
| 依赖无法解析 | 核对三个 repository 与 `1.1.0` 坐标 |
| 编辑器/kernel 启动了别的运行时 | 清除意外设置的 `datapackSandbox.cliJarPath`/`DPS_CLI_JAR`，恢复使用内置 jar |
| Web Worker 不可用 | 通过 HTTP(S) 提供站点，确认 Worker 被打包并检查 CSP/CORS |
| 渲染缺少资源 | 显式提供/导入匹配的 client JAR 或 `assets/` 目录 |

## 限制

- 这是 clean-room runtime；任何安装方式都不包含 Mojang server jar 或原版服务端代码。
- CLI jar 适合进程调用，不提供稳定类库依赖边界。
- 源码构建使用仓库锁定的 toolchain，不会悄悄用旧 Java/Node 替代。
- JVM 与浏览器渲染所需的 Minecraft 客户端资源都由用户提供。

## 相关页面

- [快速开始](/guide/getting-started)
- [CLI 运行工作流](/workflows/cli)
- [QuickTest 总览](/guide/code-test-api)
- [Playground](/guide/playground)
