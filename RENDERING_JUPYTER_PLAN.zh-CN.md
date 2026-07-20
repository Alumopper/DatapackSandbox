# Datapack Sandbox 近原版渲染与 Jupyter Kernel 实施计划

> 状态：已实施并通过 Windows/Linux 发布级验收  
> 编写日期：2026-07-19  
> 完成日期：2026-07-19  
> 默认目标版本：Minecraft Java 26.2  
> 适用仓库：DatapackSandbox 1.0.x 及后续版本

## 1. 背景

Datapack Sandbox 已经能够在不启动原版服务端的情况下加载数据包、运行 `.mcfunction`、推进 tick、注入玩家事件，并输出 snapshot、trace 和结构化报告。当前输出适合自动化验证，但人类观察复杂空间状态时仍需阅读大量 JSON；交互入口也主要是 CLI、REPL、manifest 和 VS Code 面板，不具备 Notebook 式的叙事、执行和可视化闭环。

本计划增加两项相互配合的能力：

1. 根据 `SandboxWorld` 中已经建模的方块、实体、玩家和时间状态，读取用户本地 Minecraft/资源包资产，输出接近原版视觉风格的三维 PNG。
2. 提供真正的 Datapack Sandbox Jupyter Kernel，使 Notebook 代码单元可以直接运行 MCF，并在单元下方显示命令输出、状态变化和三维截图。

这两项能力共用同一个 JVM 运行时和持久世界。Jupyter 层只负责标准 Kernel 协议、会话管理和富输出，不重新实现 Minecraft 命令语义。

## 2. 目标与非目标

### 2.1 目标

- 使用用户拥有的原版资源或资源包中的 blockstate、model 和 texture 数据生成接近原版的画面。
- 在 Windows、Linux、无显示器 CI 和 JupyterHub 环境中均可渲染 PNG。
- 不要求启动 Minecraft 客户端、原版服务端或图形窗口。
- 渲染输入必须是一次不可变的世界视图，不能改变沙盒状态。
- 同一个世界快照和同一套资源在相同渲染参数下应产生确定性结果。
- CLI、JSONL `serve`、JVM API 和 Jupyter Kernel 使用同一渲染实现。
- Jupyter 代码单元直接接受 `.mcfunction` 文本，并按执行顺序共享世界状态。
- Jupyter 输出同时提供人类可读文本和机器可读 JSON。
- 运行失败必须准确关联 Notebook 单元及 MCF 行号。
- 保持 clean-room：仓库和发布物中不包含 Mojang 服务器 JAR、客户端代码或原版资源文件。

### 2.2 非目标

- 不追求与原版客户端逐像素一致。
- 不执行或反射加载 Minecraft 客户端 JAR 中的 class 文件。
- 不模拟当前 core 未建模的区块生成、红石传播、方块更新、实体 AI、物理、完整战斗和客户端粒子系统。
- 首版不实现 shader pack、光线追踪、PBR 材质或 Sodium/Iris 兼容。
- 首版不提供多人并发修改同一个 Kernel 世界。
- 不在 JVM 中重新实现 Jupyter ZeroMQ/HMAC 协议；由成熟的 Python Jupyter 库负责协议层。
- 不把 Notebook 结果当作原版差分测试证据。沙盒通过仅表示已建模行为通过。

## 3. 可观察验收地图

| 场景 | 触发入口 | 持久状态 | 人类可见输出 | 失败行为 |
| --- | --- | --- | --- | --- |
| CLI 执行后截图 | `run --screenshot-file` | 单次 run 的最终世界 | PNG、路径、尺寸、相机信息 | 资源/相机/输出路径错误时非零退出 |
| 持久会话截图 | JSONL `render` | 当前 serve session 世界 | Base64 PNG 与元数据 | 返回结构化错误，不终止 session |
| JVM 嵌入渲染 | `SandboxRenderer.render` | 调用者持有的世界 | `RenderedFrame` | 抛出带诊断码的渲染异常 |
| Notebook MCF 单元 | Jupyter `execute_request` | Kernel 生命周期内持续保留 | stdout、diff、PNG、执行摘要 | 单元显示 error，Kernel 保持可用 |
| Notebook 重置 | `%dps reset` | 恢复新世界并重新加载配置 | 新 snapshot 和确认信息 | 配置无效时保留旧世界 |
| Notebook 重复执行 | 再次运行同一单元 | 再执行一次而非隐式回滚 | 新 diff 和新截图 | 由 MCF 自身幂等性决定结果 |
| Notebook 中断 | Jupyter Interrupt | 已完成命令保留；未开始命令不执行 | 中断错误和最后稳定状态 | 不留下半写入协议消息 |
| 多玩家观察 | `%dps camera <player>` | 不改变玩家状态 | 从指定玩家视角渲染 | 玩家不存在时给出候选名称 |

默认 Profile 为 `26.2`。架构必须允许使用仓库已支持的 `1.20.4` 至 `26.2` Profile；资源目录、数据格式和模型差异以所选 Profile 为准。

## 4. 总体架构

```text
JupyterLab / Notebook / VS Code Notebook
                 |
                 | Jupyter messages
                 v
       datapack-sandbox-kernel (Python)
                 |
                 | JSONL stdin/stdout
                 v
           :cli serve session
          /                   \
         v                     v
      :core                 :renderer
  command/runtime       asset + scene + PNG
         \                     /
          \---- WorldView ----/
```

计划新增：

- `:renderer`：公开的 JVM 渲染模块，依赖 `:core`。
- `jupyter/`：Python 包、Kernel 实现、kernelspec、测试和打包脚本。
- `examples/jupyter/`：可直接运行的 Notebook、数据包和自有测试资源。

依赖方向：

```text
:core <- :renderer <- :cli
:core <- :testkit
:core <- :manifest <- :cli
Python Kernel -> cli fat JAR serve
```

`:core` 不依赖渲染器、Python 或图形库。这样现有嵌入式运行时继续保持轻量，调用者仅在需要图像时引入 `renderer` artifact。

## 5. 三维渲染设计

### 5.1 资源来源与 clean-room 边界

渲染器按以下优先级叠加资源，后者覆盖前者：

1. 用户指定的 Minecraft 客户端 JAR 中 `assets/` 目录。
2. 用户指定的基础资源包目录或 ZIP。
3. 用户指定的多个覆盖资源包，按 CLI 参数顺序叠加。
4. 调用者显式提供的玩家皮肤或测试纹理。

允许的输入形式：

```text
--minecraft-assets <client.jar|assets-directory>
--resource-pack <directory|zip>   # 可重复
--player-skin <player>=<png>      # 可重复
```

安全规则：

- 读取客户端 JAR 时只允许访问 `assets/<namespace>/...` 和必要的资源元数据。
- 禁止加载、解析或执行 `.class`。
- 禁止把读取到的资产复制进构建产物、缓存提交目录或测试 golden。
- 默认缓存只保存解析结果的可丢弃索引；纹理缓存位于用户缓存目录并可关闭。
- 报告记录资产来源路径和资源包摘要，但不嵌入原版纹理。
- 缺少原版资产时使用仓库自有的程序化占位材质，并明确输出 warning。

### 5.2 模块内部划分

建议包结构：

```text
renderer/src/main/kotlin/moe/afox/dpsandbox/render/
  RenderApi.kt
  RenderRequest.kt
  RenderedFrame.kt
  WorldView.kt
  AssetSource.kt
  AssetResolver.kt
  TextureAtlas.kt
  BlockStateResolver.kt
  ModelLoader.kt
  ModelBaker.kt
  SceneBuilder.kt
  EntityModelRegistry.kt
  Camera.kt
  SoftwareRasterizer.kt
  Lighting.kt
  PngWriter.kt
  RenderDiagnostics.kt
```

职责约束：

- `WorldView`：从 mutable `SandboxWorld` 复制渲染所需字段，渲染期间不再访问可变世界。
- `AssetResolver`：负责路径归一化、资源覆盖、父模型解析和循环检测。
- `ModelBaker`：把 JSON model 转成不可变 quad/triangle 数据。
- `SceneBuilder`：将方块、实体和显示实体转换为场景实例。
- `SoftwareRasterizer`：只处理相机、裁剪、深度、纹理采样、混合和写像素，不理解 Minecraft JSON。
- `PngWriter`：输出 PNG 字节或文件，不负责场景逻辑。

### 5.3 世界视图

`WorldView.capture(sandbox)` 至少复制：

- Profile ID、gameTime、dayTime、weather、dimension。
- 显式方块位置、方块 ID、properties、必要 block entity NBT。
- 实体 UUID、类型、位置、yaw、pitch、NBT、equipment 和 special entity 状态。
- 玩家名称、位置、yaw、pitch、game mode、skin 映射。
- 世界出生点和可选相机锚点。

首版只渲染 `SandboxWorld.blocks` 中存在的稀疏方块。未加载/未建模位置按 void 或配置的背景处理，不能凭空生成地形。

### 5.4 方块模型解析

首版支持：

- `blockstates/*.json` 的 `variants`。
- `multipart` 的 AND/OR 条件组合。
- model `parent` 链和纹理变量引用。
- element 的 `from`、`to`、faces、UV、rotation、shade。
- blockstate X/Y 旋转、uvlock 和权重选择。
- `cullface`、透明像素和双面策略。
- 内置生成模型：cube、cross、平面和缺失模型占位符。

权重 variant 必须使用稳定种子：默认由世界 seed、方块坐标和模型 ID 计算，确保重复渲染一致。

模型父链循环、缺失纹理、非法 UV 和不支持字段产生渲染诊断。默认模式继续渲染占位模型；严格模式使截图失败。

### 5.5 纹理与颜色

- PNG 解码使用 JVM 标准图像能力，内部转换为固定 RGBA8。
- 纹理采样首版默认 nearest neighbor，以保持 Minecraft 像素风格。
- 支持 alpha test 和半透明混合两个材质通道。
- grass、foliage、water tint 首版使用可配置的固定/biome 颜色；若世界提供 biome 数据则按当前位置取值。
- animated texture 首版取确定性的指定帧；默认根据 `gameTime` 选择。
- mipmap 首版可关闭；大分辨率和远距离闪烁问题在第二阶段处理。

### 5.6 相机

相机来源按显式程度排序：

1. 完整指定 position、yaw、pitch。
2. 指定玩家名称，使用玩家眼睛位置与朝向。
3. 指定实体 UUID。
4. 自动相机：围绕显式场景包围盒选择三分之四观察角度。

公开参数：

- `width`、`height`：默认 1280×720，限制 64 至 8192。
- `fov`：默认 70°。
- `nearPlane`、`farPlane` 或 `renderDistance`。
- `cameraPlayer`、`cameraEntity`、`position`、`yaw`、`pitch`。
- `dimension`：默认跟随相机主体。
- `transparentBackground`、`showHud`、`showDebugOverlay`。

无有效相机、相机坐标非有限数、图片尺寸越界和目标维度不存在都返回输入诊断。

### 5.7 光栅化与视觉效果

CPU 渲染管线：

1. 视锥和维度过滤。
2. 方块邻面剔除和实例级包围盒剔除。
3. 模型矩阵、视图矩阵和投影矩阵变换。
4. near-plane 裁剪。
5. opaque pass：深度测试和深度写入。
6. cutout pass：alpha threshold 和深度写入。
7. translucent pass：按相机距离稳定排序，深度测试但默认不写深度。
8. 实体、名称标签和可选调试 overlay。
9. PNG 编码。

基础光照由 sky brightness、方块面方向、天气和 dayTime 组合。由于 core 不模拟 lightmap，首版不会伪造逐方块光照值；输出元数据必须标记 `lightingModel=approximate`。

### 5.8 实体范围

首版必须覆盖当前 runtime 重点建模的实体：

- player
- zombie
- skeleton
- item
- experience_orb
- marker（默认不可见，仅 debug overlay 显示）
- block_display
- item_display
- text_display
- interaction（默认不可见，仅 debug overlay 显示）

实体渲染分级：

- `modeled`：有专用几何、纹理和姿态。
- `approximate`：使用通用 billboard、方块或简化模型。
- `hidden`：原版本就不可见或缺少可观察外观。

实体不受模拟 AI/动画驱动；只根据 snapshot 中存在的位置、旋转、age 和 special state 选择确定性姿态。

### 5.9 JVM 公共 API 草案

```kotlin
val renderer = SandboxRenderer(
    RenderAssets.builder()
        .minecraftAssets(Path.of("client.jar"))
        .resourcePack(Path.of("my-resources.zip"))
        .build(),
)

val frame = renderer.render(
    sandbox,
    RenderRequest.builder()
        .size(1280, 720)
        .playerCamera("Steve")
        .renderDistance(64.0)
        .build(),
)

frame.writePng(Path.of("state.png"))
println(frame.metadata.diagnostics)
```

`RenderedFrame` 包含：

- PNG bytes 或惰性编码后的 RGBA frame。
- width、height、camera、dimension。
- 资产来源摘要。
- 渲染耗时、可见方块/三角形/实体数量。
- warning 和 unsupported feature 列表。
- `visualParity=false` 与光照模型标记。

公共 API 确定后更新 `api/renderer.api`，并把 renderer 纳入发布 artifact、sources JAR、javadoc JAR 和 POM 验证。

### 5.10 CLI 接口草案

```powershell
java -jar datapack-sandbox-cli.jar run `
  --pack ./pack `
  --function demo:main `
  --minecraft-assets "$env:APPDATA/.minecraft/versions/26.2/26.2.jar" `
  --resource-pack ./resources.zip `
  --camera-player Steve `
  --screenshot-file build/state.png `
  --screenshot-width 1280 `
  --screenshot-height 720
```

约定：

- 指定 `--screenshot-file` 即启用渲染。
- 输出路径父目录自动创建。
- CLI 成功信息打印绝对路径、尺寸和诊断数量。
- `--strict` 同时使缺失模型/纹理成为错误。
- 没有资产时默认允许占位渲染，但输出明显 warning；可通过 `--require-render-assets` 禁止 fallback。
- snapshot、report 和 screenshot 可以在一次运行中同时输出。

### 5.11 Serve 接口草案

请求：

```json
{
  "id": "render-1",
  "method": "render",
  "params": {
    "cameraPlayer": "Steve",
    "width": 1280,
    "height": 720,
    "minecraftAssets": "C:/Users/me/.minecraft/versions/26.2/26.2.jar",
    "resourcePacks": ["C:/packs/my-pack.zip"]
  }
}
```

响应：

```json
{
  "id": "render-1",
  "ok": true,
  "result": {
    "mimeType": "image/png",
    "encoding": "base64",
    "data": "...",
    "width": 1280,
    "height": 720,
    "metadata": {
      "camera": "Steve",
      "lightingModel": "approximate",
      "visualParity": false,
      "diagnostics": []
    }
  }
}
```

为了避免无界 JSONL 消息，serve 增加最大图像字节数限制；超过限制时建议降低尺寸或通过明确授权的临时文件输出模式返回路径。

## 6. Jupyter Kernel 设计

### 6.1 用户体验

安装：

```powershell
pip install datapack-sandbox-kernel
datapack-sandbox-kernel install --user
jupyter lab
```

Launcher 中显示 `Datapack Sandbox (MCFunction)`。Notebook 的 Markdown 单元保持标准 Markdown，代码单元的语言为 `mcfunction`。

初始化单元：

```text
%dps version 26.2
%dps pack ./pack
%dps assets ~/.minecraft/versions/26.2/26.2.jar
%dps world ./world.fixture.json
%dps camera Steve
```

MCF 单元：

```mcfunction
scoreboard objectives add points dummy
scoreboard players set Steve points 10
setblock 0 64 2 minecraft:diamond_ore
summon minecraft:zombie 2 64 4
```

单元下方依次显示：

1. 命令产生的文本输出。
2. 执行摘要：成功、命令数、gameTime、实体数。
3. 可折叠的 snapshot diff JSON。
4. 当前玩家视角 PNG。
5. warning/unsupported 诊断。

### 6.2 Python 包布局

```text
jupyter/
  pyproject.toml
  README.md
  src/datapack_sandbox_kernel/
    __init__.py
    __main__.py
    kernel.py
    session.py
    protocol.py
    display.py
    magics.py
    install.py
    resources/
      kernelspec/kernel.json
      kernelspec/logo-32x32.png
      kernelspec/logo-64x64.png
      datapack-sandbox-cli.jar
  tests/
```

发布 wheel 默认携带本项目构建出的 fat JAR，从而不要求用户单独下载 CLI。开发模式按以下顺序定位 JAR：

1. `DPS_CLI_JAR`。
2. Python 包内置 JAR。
3. 当前源码仓库 `cli/build/libs/datapack-sandbox-cli.jar`。

启动前检查 Java 25、JAR 版本和 serve protocol 版本。失败信息必须包含实际 Java 路径和可操作的修复建议。

### 6.3 Kernel 生命周期

1. Python Kernel 启动并读取 Jupyter connection file。
2. Kernel 第一次执行时启动 `java -jar ... serve` 子进程。
3. 等待 serve hello，验证协议和默认 Profile。
4. 根据当前 `%dps` 配置创建 sandbox。
5. 每个 MCF 单元 upsert 一个合成函数并在同一 world 中运行。
6. 请求 snapshot diff、outputs、diagnostics 和可选 render。
7. Kernel shutdown 时发送关闭请求并终止 JVM 子进程。
8. JVM 意外退出时，当前单元失败；Kernel 可重新启动 JVM，但不得假装恢复丢失世界。

每个 Notebook Kernel 拥有独立 JVM serve session；不同 Notebook 不共享世界。

### 6.4 单元执行语义

每个代码单元生成稳定资源 ID：

```text
notebook:cell_<execution_count>
```

执行步骤：

1. 保存单元源码、Notebook cell ID 和 execution count。
2. `upsertFunctionSource`，sourceName 使用 `<notebook:cell-id>`。
3. serve reload 时保留当前 world。
4. 记录 before snapshot、output cursor 和 trace cursor。
5. `runFunction notebook:cell_N`。
6. 获取本次新增输出、trace、diff 和状态摘要。
7. 如果启用自动渲染，请求 `render`。
8. 发布 Jupyter stream/display_data/execute_result 或 error 消息。

默认执行上下文仍是沙盒 synthetic server context。需要玩家上下文的代码必须显式使用：

```mcfunction
execute as Steve at @s run function demo:main
```

文档和错误提示必须说明这一点，不能隐式把所有单元改写为 `execute as Steve`。

### 6.5 `%dps` 控制指令

首版指令：

| 指令 | 作用 | 是否重建 sandbox |
| --- | --- | --- |
| `%dps version <id>` | 选择 Profile | 是 |
| `%dps pack <path>` | 增加数据包 | 是，需确认或使用新世界 |
| `%dps packs` | 查看当前数据包 | 否 |
| `%dps assets <path>` | 设置原版资产 | 否，仅重建 renderer |
| `%dps resource-pack <path>` | 增加资源包 | 否，仅重建 renderer |
| `%dps world <path>` | 应用 world fixture | 修改当前世界 |
| `%dps camera <player|uuid>` | 选择观察相机 | 否 |
| `%dps tick <count>` | 推进 tick | 是，修改世界 |
| `%dps function <id>` | 运行已加载函数 | 是，修改世界 |
| `%dps render [path]` | 显示并可选保存截图 | 否 |
| `%dps snapshot` | 显示 JSON snapshot | 否 |
| `%dps outputs` | 显示累计输出 | 否 |
| `%dps reset` | 新建世界并重载配置 | 是 |
| `%dps status` | 显示版本、包、世界和 renderer 状态 | 否 |
| `%dps help` | 显示帮助 | 否 |

一旦当前世界有修改，更换 version 或 pack 必须明确重建世界。首版采用确定性规则：指令返回“需要 `%dps reset --apply`”而不静默丢弃状态。

### 6.6 Jupyter 消息和 MIME 输出

Kernel 使用标准消息：

- `stream`：tellraw/say、warning 和简短进度。
- `display_data`：PNG、JSON diff、HTML 摘要。
- `execute_result`：最终机器可读摘要。
- `error`：ename、evalue、traceback，其中 traceback 显示 MCF 文件、行号和命令。

一个成功单元的 MIME bundle 至少包含：

```json
{
  "text/plain": "OK commands=4 gameTime=0 entities=2",
  "application/json": {
    "commands": 4,
    "gameTime": 0,
    "entities": 2,
    "snapshotDiffs": []
  },
  "image/png": "<base64>"
}
```

是否自动附带 PNG 由 `%dps config autoRender true|false` 控制，默认 `true`。如果场景没有变化，仍可选择复用上一帧；元数据必须标记是否复用。

### 6.7 补全、检查和完整性判断

- `do_complete` 调用 serve `completions`，支持命令、资源 ID、selector 和已加载函数。
- `do_inspect` 返回命令支持级别、语法提示、资源来源和 Profile 信息。
- `do_is_complete` 根据引号、JSON 文本组件和命令续行判断代码是否完整。
- 单元中的空行和 `#` 注释保持 `.mcfunction` 语义。
- Kernel language metadata 使用 `name=mcfunction`，文件扩展名 `.mcfunction`。

### 6.8 中断与恢复

当前 core 的单次命令执行是同步的。首版中断策略：

- Python Kernel 收到 interrupt 后请求取消当前执行。
- JVM 在命令边界检查取消标志。
- 已完成的命令产生的状态保留，不尝试事务回滚。
- 当前函数尚未执行的后续命令停止。
- 返回明确的 `ExecutionInterrupted`，并附带最后稳定 snapshot diff。
- 若 JVM 无法响应，则在超时后终止 JVM；此时世界丢失并要求 `%dps reset`。

长期可增加 world checkpoint/restore，但不纳入首版。

## 7. 配置与发现

Kernel 配置优先级：

1. 当前 Notebook `%dps` 指令。
2. Notebook 所在目录 `.dps-kernel.json`。
3. 环境变量。
4. 用户级默认配置。
5. 内置默认值。

建议配置：

```json
{
  "version": "26.2",
  "packs": ["./pack"],
  "minecraftAssets": "~/.minecraft/versions/26.2/26.2.jar",
  "resourcePacks": [],
  "defaultPlayer": "Steve",
  "cameraPlayer": "Steve",
  "autoRender": true,
  "render": {
    "width": 960,
    "height": 540,
    "fov": 70,
    "renderDistance": 64
  },
  "strict": false
}
```

自动发现 `.minecraft` 只作为便利功能，并在日志中打印最终选择。CI、JupyterHub 和可复现示例必须显式提供资产路径。

## 8. 实施里程碑

### M0：架构骨架和契约

- 在 `settings.gradle.kts` 注册 `:renderer`。
- 建立 renderer build、Java 25 toolchain、依赖锁和 API baseline。
- 定义 `WorldView`、`RenderRequest`、`RenderedFrame` 和诊断模型。
- 为 CLI 和 serve 写尚未实现的接口契约测试。
- 建立 `jupyter/pyproject.toml`、Kernel 类和测试框架。

完成门槛：所有新模块可构建；没有真实渲染，但公共契约经评审稳定。

### M1：方块场景最小闭环

- 加载仓库自有测试资源包。
- 支持 cube 方块模型和纹理采样。
- 实现相机、投影、深度缓冲、opaque pass 和 PNG。
- CLI `run --screenshot-file` 端到端可用。
- 空世界、单方块、多方块遮挡和相机错误有测试。

完成门槛：CLI 执行 `setblock` 后产生可辨识的三维 PNG。

### M2：原版 blockstate/model 兼容

- parent、texture indirection、element faces、rotation。
- variants、multipart、权重和 uvlock。
- cutout/translucent、基础 tint 和动画帧。
- 资源包覆盖顺序与缺失资产诊断。
- 使用用户本地资产进行非发布门禁的人工观察验证。

完成门槛：常见石头、草方块、玻璃、植物和水场景接近原版资源外观。

### M3：实体、光照和可观察性

- 玩家、僵尸、骷髅、掉落物和经验球。
- block/item/text display。
- 昼夜、天气、方向阴影、天空和雾。
- debug overlay 与渲染统计。
- serve `render` Base64 输出。

完成门槛：当前 runtime 的关键实体能在截图中定位并辨认。

### M4：Jupyter Kernel 执行闭环

- kernelspec 安装器和 fat JAR 定位。
- 启动/关闭 serve session。
- 原生 MCF 单元执行和持久世界。
- stream、JSON diff、error 富输出。
- `%dps version/pack/world/reset/status/help`。
- completion、inspect 和 is_complete。

完成门槛：Jupyter 客户端启动 Kernel，连续两个单元共享 scoreboard 和方块状态。

### M5：Notebook 图像闭环

- `%dps assets/resource-pack/camera/render`。
- 自动 `image/png` 输出。
- 渲染缓存和无变化帧复用。
- 中断、JVM 崩溃和资源错误恢复。
- 示例 `.ipynb`。

完成门槛：运行 MCF 单元后无需离开 Notebook 即可看到接近原版的当前世界画面。

### M6：发布与文档

- renderer Maven artifact。
- Python wheel/sdist 和内置 fat JAR。
- Windows/Linux Jupyter smoke。
- CLI fat JAR smoke、API/POM/依赖锁验证。
- 中英文安装、渲染、Notebook、排障和 clean-room 文档。
- 将 Python 测试和 renderer 检查接入 `releaseCheck` 或并列发布门禁。

完成门槛：全新环境按照文档安装后可打开示例 Notebook 并运行。

## 9. 测试策略

### 9.1 Renderer 单元测试

- 资源路径标准化和 zip-slip 防护。
- 资源包覆盖优先级。
- parent/texture 循环检测。
- blockstate variant/multipart 条件。
- UV、旋转、裁剪和投影数学。
- 深度遮挡、alpha test、透明排序。
- 稳定 variant seed。
- PNG header、尺寸、颜色类型和可解码性。
- WorldView 捕获后修改原世界不影响正在渲染的帧。

### 9.2 Renderer golden 测试

使用仓库自有、可再分发的小型测试纹理和模型，覆盖：

- 单立方体六个面。
- 两方块遮挡。
- 透明玻璃前后的实体。
- cross 植物。
- multipart 模型。
- 玩家视角与自由相机。
- 昼夜和天气参数。

Golden 比较采用双层判定：

- 结构条件：尺寸、非透明像素数、关键像素区域。
- 感知条件：容许极小颜色误差的图像差异阈值。

不能提交从 Mojang 资产生成的 golden PNG。

### 9.3 CLI/Serve 集成测试

- `run --command setblock ... --screenshot-file`。
- screenshot 与 snapshot/report 同时输出。
- 无资产 fallback 和 strict failure。
- serve `render` 返回合法 Base64 PNG。
- 过大尺寸、无效相机、无权限路径和损坏资源包。
- 多次 render 不改变 snapshot。

### 9.4 Jupyter Kernel 测试

用 `jupyter_client.KernelManager` 启动真实 Kernel：

- kernel_info 返回 mcfunction language metadata。
- 第一个单元创建 objective，第二个单元读取/修改。
- MCF 语法错误包含 cell/line/command。
- `display_data` 含 `text/plain`、`application/json`、`image/png`。
- PNG Base64 可解码且尺寸匹配配置。
- completion 返回命令和资源 ID。
- reset 清除旧世界但保留配置。
- interrupt 停止长函数并保持 Kernel 可继续使用。
- shutdown 不遗留 Java 子进程。
- Windows 路径、空格路径和非 ASCII Notebook 路径。

### 9.5 性能基准

基准场景：

- 16³、32³、64³ 稀疏/实心方块区域。
- 100、1,000 个实体。
- 960×540、1280×720、1920×1080。
- 冷资产加载、热缓存、仅相机变化、世界无变化。

首版目标不是固定毫秒承诺，而是建立可检测回归的基线。基准报告记录资产解析、场景构建、光栅化和 PNG 编码的独立耗时。

## 10. 发布门禁

实施完成后的最低门禁：

```powershell
./gradlew.bat :renderer:check
./gradlew.bat :core:check
./gradlew.bat :cli:check
./gradlew.bat apiCheck
./gradlew.bat releaseCheck
```

Python/Jupyter：

```powershell
python -m pytest jupyter/tests
python -m build jupyter
python -m twine check jupyter/dist/*
```

端到端 smoke：

1. 构建 CLI fat JAR。
2. 用自有资源 fixture 执行 MCF 并生成 PNG。
3. 安装临时 wheel 和 kernelspec。
4. 通过 `jupyter_client` 启动 Kernel。
5. 执行两个共享状态单元。
6. 验证 diff、PNG 和 shutdown。

发布前还必须确认：

- wheel/JAR 不含 Mojang 资产或类文件。
- `LICENSE`、POM 和 Python metadata 完整。
- renderer API baseline 已更新并审查。
- 中英文文档成对更新。
- Linux 和 Windows 均通过无显示器渲染。

## 11. 风险与缓解

### 11.1 “接近原版”范围失控

风险：原版模型、材质、实体和光照细节很多，容易演变成完整客户端。

缓解：以可见数据包调试为目标；优先方块、display entities 和 runtime 已建模实体。每种能力记录 `modeled/approximate/unsupported`，不承诺像素一致。

### 11.2 原版资产授权和分发

风险：误把客户端纹理打包进仓库、wheel、JAR 或 golden。

缓解：所有发布测试使用自有合成资产；增加构建扫描，拒绝已知原版资产路径和异常大纹理集合；运行时只读取用户明确提供的资产。

### 11.3 CPU 渲染性能

风险：高分辨率、大场景和透明材质导致 Notebook 等待时间过长。

缓解：邻面剔除、视锥剔除、模型缓存、纹理 atlas、分 tile 光栅化、帧复用；Notebook 默认 960×540 和有限 render distance。

### 11.4 Jupyter 与 JVM 双进程故障

风险：Java 崩溃、stdout 被日志污染、Kernel shutdown 遗留进程。

缓解：协议严格使用 stdout JSONL，日志写 stderr；hello/version 握手；进程组清理；心跳/超时；真实客户端集成测试。

### 11.5 中断不是事务回滚

风险：用户误以为中断会恢复整个单元执行前状态。

缓解：文档明确“命令边界取消、已完成状态保留”；错误输出附 before/after diff。checkpoint/restore 作为后续能力。

### 11.6 工作区已有大规模未提交改动

风险：实现时覆盖用户正在进行的模块拆分和测试调整。

缓解：每个里程碑开始前重新检查 `git status` 和相关 diff；优先新文件/新模块；修改现有文件时使用小 patch；不重置、不清理、不格式化无关文件。

## 12. 建议的首个可交付纵向切片

首个实现切片只包含以下范围：

1. 新建 `:renderer`。
2. 使用仓库自有资源读取一个 cube model 和 PNG texture。
3. 从 `SandboxWorld.blocks` 构建场景。
4. 从 Steve 的 position/yaw/pitch 建立相机。
5. CPU 输出 960×540 PNG。
6. CLI `run --screenshot-file` 接入。
7. serve `render` 返回 Base64 PNG。
8. Python Kernel 执行两个连续 MCF 单元。
9. 第二个单元显示刚放置方块的 PNG。

该切片完成后，架构的四条关键链路都已被验证：

```text
MCF -> SandboxWorld -> Renderer -> PNG
Notebook -> Kernel -> serve -> core
Notebook -> Kernel -> serve -> renderer -> image/png
CLI -> core + renderer -> file
```

之后再增加完整 blockstate/model、原版资产兼容、透明材质和实体，不需要推翻协议或模块边界。

## 13. 最终验收标准

全部里程碑完成时必须满足：

- 用户能安装并选择 `Datapack Sandbox (MCFunction)` Jupyter Kernel。
- Notebook Markdown 与 MCF 代码单元可交错存在。
- 两个连续 MCF 单元共享同一个世界，重复执行语义明确。
- 命令失败能定位到 Notebook cell 和 MCF 行号，失败后 Kernel 仍可使用。
- 每个成功单元可直接显示当前世界的 PNG。
- 使用用户本地原版资产时，常见方块、透明方块、植物、玩家和关键实体视觉上可辨识并接近原版风格。
- 相机可绑定玩家，也可显式设置位置和朝向。
- 同一快照、资产和参数重复渲染结果确定。
- 渲染不会修改 sandbox snapshot。
- CLI 和 serve 使用与 Notebook 相同的 renderer。
- Windows、Linux、headless CI 均通过。
- 发布产物不包含 Mojang 代码或资产。
- 文档明确所有 approximate/unsupported 边界，不把 sandbox 图像声称为原版客户端截图。

## 14. 实施时的默认决策

除非后续评审明确修改，实施采用以下默认值：

- 新建独立公开模块 `:renderer`，不把图形代码放进 `:core`。
- 首版使用无窗口 CPU rasterizer，不依赖 OpenGL。
- Jupyter 协议由 Python `ipykernel`/`jupyter_client` 承担，JVM 继续通过 JSONL serve 提供业务能力。
- Python wheel 携带本项目 CLI fat JAR，但不携带任何 Minecraft 资产。
- Notebook 代码单元是原生 MCF；控制操作使用 `%dps` 指令。
- 世界在 Kernel 生命周期内持久存在；重复运行单元会再次执行。
- 自动截图默认开启，Notebook 默认分辨率为 960×540。
- 玩家视角优先；没有可用玩家时使用自动场景相机。
- 缺失资源默认 warning + 占位材质；strict 模式失败。
- 光照明确标记为 approximate，永不声称 vanilla parity。

## 15. 实施完成记录

本计划已于 2026-07-19 完成实现。实现保持 clean-room：运行时只读取调用者明确提供的 `assets/`、资源包和皮肤；仓库、Maven artifact、CLI JAR、wheel/sdist、测试 fixture 与 golden 均不包含 Mojang 代码或资产。

### 15.1 里程碑结果

| 里程碑 | 状态 | 实际交付 |
| --- | --- | --- |
| M0 | 完成 | 新增公开 `:renderer` 模块、Java 25 toolchain、依赖锁、`api/renderer.api`、不可变 `WorldView`、渲染请求/结果/诊断契约，以及 Python Kernel 包骨架。 |
| M1 | 完成 | 无窗口 CPU 相机/投影/深度/PNG 管线、程序化 fallback、CLI `run --screenshot-file`、固定/玩家/实体/自动相机和独立 JAR 截图烟测可用。 |
| M2 | 完成 | 支持 blockstate variants/multipart/AND/OR/权重、parent 与纹理引用、element/UV/rotation/uvlock/cullface、opaque/cutout/translucent、tint、动画帧、目录/ZIP/client JAR 覆盖和严格诊断。 |
| M3 | 完成 | 支持分段 player/zombie/skeleton、掉落物/经验球平面、block/item/text display、隐藏实体 debug、昼夜/天气/方向光/天空/距离雾、HUD/debug overlay、分阶段统计和 serve Base64 PNG。简化实体均输出 `ENTITY_APPROXIMATE`。 |
| M4 | 完成 | 提供标准 `ipykernel` Kernel、kernelspec 安装器、持久 JSONL JVM 会话、原生 MCF 单元、共享世界、全部 `%dps` 指令、text/JSON/HTML/PNG、completion/inspect/is_complete 和精确 cell/line 错误。 |
| M5 | 完成 | 默认自动 `image/png`、基于世界修订/相机/资产/参数的无变化帧复用与 `render.reused`、命令边界中断、upsert/run 间隙竞态保护、`SESSION_LOST` 显式恢复规则，以及可运行示例 Notebook。 |
| M6 | 完成 | renderer Maven 发布物、携带本项目 fat JAR 的 wheel/sdist、离线分发扫描、Windows/Linux CI 矩阵、CLI/Jupyter 烟测、中英文文档、VitePress 导航、API/POM/架构/依赖校验和性能基准均已接入。 |

### 15.2 最终验收逐条审计

- [x] `Datapack Sandbox (MCFunction)` kernelspec 可通过标准 Jupyter API 安装、发现和启动，language metadata 为 `mcfunction`。
- [x] 示例 `.ipynb` 使用标准 Markdown 与 MCF 代码单元；连续单元共享同一 JVM 世界，重复执行会再次执行命令。
- [x] MCF 错误包含 Notebook source/cell、行号和命令；普通错误、中断和资源错误后 Kernel 可继续使用。
- [x] 每个成功 MCF 单元默认发布 `text/plain`、`application/json`、`text/html`、`image/png` 和 `execute_result`。
- [x] client JAR/资源包路径只读取 `assets/`；合成 client-JAR、目录和 ZIP 测试覆盖模型、透明材质、cross、multipart、动画、玩家与关键实体。当前机器没有本地 Minecraft 安装，因此没有把用户原版资产观察结果当作发布门禁。
- [x] 相机支持玩家名称、实体 UUID、显式位置/yaw/pitch/dimension 和确定性自动构图；无效主体、非有限值、尺寸和不存在维度返回结构化错误。
- [x] 同一 snapshot、资产和参数重复渲染得到相同 PNG；稳定权重种子包含世界 seed、坐标和模型 ID。
- [x] 渲染前复制不可变视图，并通过 snapshot 前后对比和测试证明不会修改 sandbox。
- [x] JVM API、CLI、serve 与 Notebook 全部调用同一个 `SandboxRenderer`。
- [x] Windows Java 25 完整 `releaseCheck`、Linux/WSL Java 25 headless renderer/CLI 以及 Windows/Linux 真实 Jupyter Kernel 测试均通过。
- [x] 分发扫描确认 wheel/sdist 只携带一个本项目 CLI JAR，不含 Minecraft 客户端/服务端 JAR、版本目录、资产集合或 `.class` 输入。
- [x] 中英文文档明确 `visualParity=false`、`lightingModel=approximate`、实体 `modeled/approximate/hidden` 边界、中断非事务回滚和 `SESSION_LOST` 行为。

### 15.3 最终验证证据

Windows 11 / Temurin 25：

```powershell
./gradlew.bat jupyterKernelPackage releaseCheck --console=plain
# BUILD SUCCESSFUL；releaseCheck 109 tasks；wheel/sdist 构建与 clean-room 验证通过

./gradlew.bat jupyterKernelTest --console=plain
# 17 tests，包含真实 KernelManager、rich PNG、非 ASCII/空格路径、中断竞态与恢复

npm run docs:build
# VitePress build complete
```

Linux Ubuntu/WSL / Temurin 25.0.3 / Python 3.12：

```bash
./gradlew --no-daemon :renderer:check :cli:smokeCliJarRender --console=plain
# BUILD SUCCESSFUL；20 tasks；headless PNG 已生成

PYTHON=python3 ./gradlew --no-daemon jupyterKernelTest --console=plain
# BUILD SUCCESSFUL；17 tests
```

其他验证：

- `:renderer:renderBenchmark` 生成 `renderer/build/reports/render-benchmark.json`，覆盖 16/32/64 立方稀疏/实心表面、三档分辨率、100/1,000 实体、冷/热资产、相机变化和世界无变化，并分别记录 capture、asset、scene、rasterize、PNG 耗时。
- 自有合成 golden 同时检查尺寸、占用像素、包围区域和容差化颜色差异；near-plane、深度、透明排序、雾、昼夜/天气、cullface、parent cycle、multipart/cross 和 display/entity 几何另有回归测试。
- wheel 在未设置 `DPS_CLI_JAR` 时从自身 `resources/datapack-sandbox-cli.jar` 成功启动 `dps-jsonl` serve，并声明 render 能力。
- Linux 冷缓存验证补齐了 `junit-bom-6.1.0.pom` 的精确 SHA-256，未放宽 dependency verification。

实现产物和使用方式以 `docs/rendering-notebook.zh-CN.md`、`docs/rendering-notebook.md`、`jupyter/README.md` 和 `examples/jupyter/datapack-sandbox-demo.ipynb` 为准。本节记录的是实际完成状态；前文继续保留设计依据、边界和决策历史。
