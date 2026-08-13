# Renderer JVM API 参考

## 适用场景

当 JVM 应用需要把当前 `DatapackSandbox` world 输出为 PNG、录制 GIF，或为移动相机编译不可变 scene 时依赖 `renderer`。它适合文档插图、CI artifact、编辑器预览和 JVM 实时视窗，不适合作为原版截图像素对比器。

## 前置条件

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
    implementation("moe.afox.dpsandbox:renderer:1.1.0")
}
```

JVM runtime 与 renderer 都需要 Java 25。

::: warning 客户端资源始终需要手动指定
Datapack Sandbox **不会**内置、下载、定位客户端资源，也不会从 `sandbox.profile` 推导资源路径。宿主应用必须显式传入 client JAR、解压后的 `assets/` 目录、resource pack 与玩家皮肤。请从你有权使用的来源获取这些文件，并保证它们与要渲染的 profile 匹配。

不传资源时仍会使用确定性的 procedural fallback 完成渲染，适合检查结构，但不能据此认为真实纹理已加载。
:::

## 最小可运行示例

```kotlin
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.RenderCamera
import moe.afox.dpsandbox.render.RenderRequest
import moe.afox.dpsandbox.render.SandboxRenderer
import java.nio.file.Path

val sandbox = createFunctionSandboxFromString(
    version = "26.2",
    functionText = "setblock 0 64 0 minecraft:diamond_block",
)
sandbox.runFunction("sandbox:main")

val clientAssets = Path.of(
    requireNotNull(System.getenv("DPS_CLIENT_JAR")) {
        "Set DPS_CLIENT_JAR to a matching Minecraft client JAR or assets directory"
    },
)
val renderer = SandboxRenderer(
    RenderAssets(minecraftAssets = clientAssets),
)
val frame = renderer.render(
    sandbox,
    RenderRequest(width = 960, height = 540, camera = RenderCamera.Auto),
)

frame.writePng(Path.of("build/render/world.png"))
println("sources=${frame.metadata.assetSources}")
println("diagnostics=${frame.metadata.diagnostics}")
```

`writePng` 会创建缺失的父目录。需要写入数据库、HTTP response 或自定义 encoder 时，可用 `pngBytes()` 获得 defensive copy。

## 配置资源解析

```kotlin
val assets = RenderAssets(
    minecraftAssets = Path.of("D:/Minecraft/versions/26.2/26.2.jar"),
    resourcePacks = listOf(
        Path.of("D:/packs/base-visuals.zip"),
        Path.of("D:/packs/project-overrides"),
    ),
    playerSkins = mapOf(
        "Alex" to Path.of("D:/skins/alex.png"),
    ),
)
```

| 输入 | 可接受形态 | 解析行为 |
| --- | --- | --- |
| `minecraftAssets` | client JAR/ZIP、解压根目录或 `assets/` 目录本身 | 原版 model、blockstate、texture、metadata 的基础来源 |
| `resourcePacks` | ZIP 或目录 | 按列表顺序应用；后项覆盖前项和 vanilla assets |
| `playerSkins` | 玩家名到可读 PNG 路径 | 名称大小写不敏感；缺失时用 procedural player texture |

构造 `RenderAssets` 时，每个传入路径都必须存在。目录可以是包含 `assets/` 的解压根目录，也可以直接是 `assets/`。ZIP/JAR 只解析 `assets/<namespace>/...` 下的 entry；拒绝父目录穿越，并限制编码文件大小。

Resource pack 是覆盖层，不是 vanilla base 的替代物。若 pack model 引用了原版 parent/texture，还要传 `minecraftAssets`。资源集合不变时复用同一 `SandboxRenderer`，可利用共享的 asset byte cache；文件变化且需要立即刷新时重新创建 renderer。

## 选择相机

| Camera | 行为 | 失败条件 |
| --- | --- | --- |
| `RenderCamera.Auto` | 确定性取景可见 scene bounds | 空 scene 时使用稳定 overview |
| `RenderCamera.Player(name)` | 使用玩家 eye position 与 rotation | 玩家必须存在 |
| `RenderCamera.Entity(uuid)` | 使用实体 position 与 rotation | UUID 必须可解析 |
| `RenderCamera.Fixed(...)` | 使用显式 position/yaw/pitch/dimension | 调用方负责取景与 dimension 正确性 |

```kotlin
import moe.afox.dpsandbox.core.Position

val request = RenderRequest(
    camera = RenderCamera.Fixed(
        position = Position(8.0, 70.0, 8.0),
        yaw = 135.0,
        pitch = 25.0,
        dimension = "minecraft:overworld",
    ),
)
```

只考虑 camera 所在 dimension 的对象。

## RenderRequest 参数

| 字段 | 默认值 | 约束/用途 |
| --- | --- | --- |
| `width`, `height` | `1280 × 720` | 各 64–8192；总像素不超过 16 × 1024 × 1024 |
| `camera` | `Auto` | 上述四类相机 |
| `fieldOfViewDegrees` | `70.0` | 10–150 度 |
| `nearPlane` | `0.05` | 有限正数 |
| `renderDistance` | `128.0` | 有限且大于 near plane |
| `transparentBackground` | `false` | 保留背景 alpha |
| `showHud` | `false` | 绘制 renderer 的精简 HUD |
| `showDebugOverlay` | `false` | 在 frame 中添加调试信息 |
| `strictAssets` | `false` | 把缺失/无效资源从 warning/fallback 升级为异常 |

无效几何参数立即抛 `IllegalArgumentException`。传给 `RenderAssets` 的路径不存在时抛 `SandboxException(INPUT_FORMAT)`。Strict 模式下，资源缺失为 `RESOURCE_NOT_FOUND`，无效 asset data 为 `INPUT_FORMAT`。

## 读取 frame metadata

`RenderedFrame.metadata` 记录解释图像所需的上下文：

- 尺寸、camera description、dimension；
- 可见 block/entity 和 triangle 数量；
- 规范化的 asset source 描述；
- 带 severity、code、message、可选 resource 的结构化诊断；
- world capture、asset resolve、scene build、rasterize、PNG encode 和总耗时（纳秒）；
- `lightingModel = "approximate"` 与 `visualParity = false`。

Render diagnostic 与 `sandbox.world.outputs` 分离；渲染不能修改 sandbox 的诊断流或 modeled state。Renderer 会比较 capture 前后 snapshot，若自己的操作改变 world 则直接失败。

## 录制 GIF

所有帧复用同一 renderer，确保资源栈一致：

```kotlin
import moe.afox.dpsandbox.render.SandboxGifRecorder

val recorder = SandboxGifRecorder(
    renderer = renderer,
    request = RenderRequest(width = 480, height = 270),
    frameDelayMillis = 100,
    maximumFrames = 120,
)

repeat(20) {
    sandbox.runTicks(1)
    recorder.capture(sandbox)
}
recorder.export(repeat = 0).writeGif(Path.of("build/render/timeline.gif"))
```

所有帧尺寸必须相同。Frame delay 范围 10–655,350 ms，`maximumFrames` 范围 1–1000、默认 120；`repeat = 0` 由 GIF encoder 解释为持续循环。`export` 不清空帧，新录制前用 `clear()`。

若已有 frame 集合，可直接调用 `encodeGif(List<GifAnimationFrame>, repeat)`。`RenderedAnimation` 提供 defensive bytes，以及 width、height、frame count、总 duration、repeat metadata。

## 构建 JVM 实时视窗

静态渲染每次都会 capture、resolve、build、rasterize、encode；移动相机应只编译一次几何：

```kotlin
import moe.afox.dpsandbox.render.SandboxRealtimeRenderer

val realtime = SandboxRealtimeRenderer(assets)
val scene = realtime.compile(sandbox)
var camera = scene.suggestedCamera

val liveFrame = realtime.render(
    scene = scene,
    camera = camera,
    width = 960,
    height = 540,
    fieldOfViewDegrees = 70.0,
    renderDistance = 128.0,
    showHud = true,
)
println("triangles=${liveFrame.triangles}, renderNanos=${liveFrame.renderNanos}")
```

`compile` 不做相机 frustum culling，捕获所有可见 world geometry，并返回带 suggested camera、bounds、visible counts、triangle count 的不可变 `CompiledRealtimeScene`。反复 `render` 只在已捕获 scene 上移动 camera，返回未编码的 ARGB `BufferedImage`；sandbox 改变后需要重新 compile。

`compileGpu` 为 JVM GPU backend 生成 vertex、index、material 与 texture atlas buffers。Datapack Sandbox 提供 scene buffers，不提供窗口工具包或完整 OpenGL/Vulkan render loop；device、input、presentation、资源释放由宿主负责。

## 资源诊断清单

Frame 出现 fallback 颜色时：

1. 检查 `metadata.assetSources` 是否列出预期 client JAR/目录和 resource packs。
2. 检查客户端资源版本是否与 `sandbox.profile.id` 匹配。
3. 自定义 pack 引用原版 parent/texture 时保留 `minecraftAssets` 基础层。
4. 逐项检查 diagnostic 的 `code` 与 `resource`，不要只看最终 PNG。
5. 本地资源集完整后，在 CI 中开启 `strictAssets`。

## 限制

- Renderer 是 clean-room 近似实现；lighting 为 approximate，`visualParity` 明确为 false。
- Procedural fallback 让缺失资源保持可见和确定性，但不复现原版美术。
- 图像内存随分辨率增长；GIF 还随帧数增长，compiled scene 随几何/纹理增长。
- 资源获取、许可、版本匹配、存储和访问控制由宿主负责。
- Capture frame/scene 时不要并发修改同一个 sandbox。

## 相关页面

- [渲染与实时视窗](/guide/rendering-notebook)
- [Core API](/reference/core-api)
- [Serve JSONL](/reference/serve-jsonl)
- [Playground API](/reference/playground-api)
- [报告与可观测性](/reference/reports-observability)
