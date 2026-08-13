# 渲染、动图与实时视窗

## 适用场景

当测试结果需要一张 PNG、一个可重复生成的 GIF，或需要在持久沙盒中交互查看场景时使用本页。渲染器读取当前 `SandboxWorld` 的显式状态，不启动 Minecraft 客户端或原版服务器。

## 前置条件

先按 [安装与获取](/workflows/installation) 准备 standalone JAR。客户端资源必须由你显式指定：Datapack Sandbox 不会随包分发、联网下载、扫描 `.minecraft`，也不会根据 `--version` 自动选择 client JAR。没有外部资产也能渲染，但缺失模型和纹理会产生诊断并使用确定性 fallback；要获得对应版本的原版外观，请提供本地 client JAR 或包含 `assets/` 的目录，资源包只作为可选覆盖层。

## 最小可运行示例

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --command "setblock 0 64 2 minecraft:stone" `
  --screenshot-file build/state.png
```

渲染可以与 snapshot、trace、outputs、assertions 和 run report 同时输出。

## 完整能力

### PNG 与资产层

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack ./my_pack `
  --function demo:main `
  --minecraft-assets "$env:APPDATA/.minecraft/versions/26.2/26.2.jar" `
  --resource-pack ./my-resource-pack.zip `
  --camera-player Steve `
  --screenshot-file build/state.png `
  --screenshot-width 1280 `
  --screenshot-height 720
```

`--minecraft-assets` 接受你手动指定的 client JAR 或含 `assets/` 的目录；只读取资产，不加载类。重复的 `--resource-pack` 按声明顺序叠加。如果资源包中的模型继承原版 parent 或引用原版纹理，仍需同时提供基础 client 资产。相机可以绑定玩家、实体或固定坐标，并可控制 FOV、裁剪距离、透明背景、HUD 与 debug overlay。`--require-render-assets` 会因缺失资产失败，`--strict` 也会把缺失或无效资产视为错误。

### GIF 与 JVM API

`SandboxGifRecorder` 从同一沙盒按顺序捕获 RGBA 帧，再导出确定性的 GIF。完整的依赖、`RenderAssets`、`RenderRequest`、camera、frame 和 GIF 类型见 [Renderer JVM API 参考](/reference/renderer-api)。

```kotlin
val gif = SandboxGifRecorder(
    renderer = renderer,
    request = RenderRequest(width = 480, height = 270),
    frameDelayMillis = 200,
)
gif.capture(sandbox)
sandbox.executeCommand("setblock 1 0 2 minecraft:diamond_block")
gif.capture(sandbox)
gif.export().writeGif(Path.of("branch.gif"))
```

渲染前会复制不可变 `WorldView`，成功或失败都不会修改沙盒。GIF 编码器由 JVM 与浏览器 Worker 共享；输入帧、延迟和循环参数相同时输出字节一致。

### JVM 实时视窗

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar viewport `
  --version 26.2 `
  --minecraft-assets "D:\.minecraft\versions\26.2\26.2.jar" `
  --command "setblock 0 0 2 minecraft:stone"
```

视窗使用 GLFW/OpenGL 3.3。点击场景捕获鼠标，WASD 飞行，Space/Shift 升降，滚轮调速，Esc 释放。工具栏支持播放/暂停、单 tick、重新取景、检查点、重置和高质量 PNG 导出；`T`、`/` 或 **Command** 打开带补全和非破坏性检查的命令控制台。

命令、输入、检查点和 20 TPS 世界修改在同一 JVM 会话中串行执行，镜头帧只更新矩阵和 GPU 缓冲，不会重新执行命令。实时画面使用 OpenGL；**Export PNG** 始终走完整软件渲染路径。

### Jupyter Kernel

原有的 Jupyter 安装、`%dps` magics、持久会话、配置优先级、中断与错误恢复说明已迁移到独立的 [Jupyter 指南](/integrations/jupyter)。旧章节标题保留在这里，已有链接仍能找到新的入口。

### Serve 与浏览器

编辑器进程可通过 [`render` JSONL 方法](/reference/serve-jsonl#渲染)获得 Base64 PNG 和元数据，并在每次请求的 `minecraftAssets` 中显式传入宿主机路径。浏览器不能读取 JVM 路径或自动访问本机 `.minecraft`；请用组件的文件选择器/拖放导入 client JAR，或调用 [`importArchive('client-jar', ...)`](/reference/playground-api#客户端资源导入)，再显示 WebGL2 实时视窗或导出软件渲染的 PNG/GIF。

## 限制

- 输出只反映稀疏世界模型中已经存在的方块、实体、玩家、时间和天气；不会推断地形生成、粒子、红石更新、实体 AI、原版 light map 或后处理。
- 元数据固定声明 `visualParity: false` 和 `lightingModel: approximate`。不得把结果描述成原版像素级截图。
- 自定义字体 provider、多层或特殊物品模型、发光轮廓及其他未建模客户端效果不在一致性范围内。
- PNG 宽高分别为 64–8192，且总像素受 16,777,216 上限约束；Serve 响应还受 16 MiB 编码大小限制。
- JVM 实时视窗只支持键鼠，不提供触摸摇杆；浏览器视窗另有触屏控制。

## 相关页面

- [Renderer JVM API 参考](/reference/renderer-api)
- [Jupyter 指南](/integrations/jupyter)
- [CLI 参考](/reference/cli)
- [Playground API 参考](/reference/playground-api)
- [报告与可观测性](/reference/reports-observability)
