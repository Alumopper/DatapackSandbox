# 渲染与 Jupyter Kernel

Datapack Sandbox 可以把当前模拟世界渲染为 PNG，也可以在 Jupyter Notebook
中持续运行原生 `mcfunction` 单元格。它使用 clean-room JVM runtime，不会启动
Minecraft 客户端或原版服务器。

JVM 与浏览器现在使用一致的展示实体渲染路径：`block_display` 会烘焙实际
blockstate/model，`item_display` 会解析新版 item definition、挤出 generated item
model 的像素轮廓并应用模型 `display` 变换，`text_display` 会生成可读文字。渲染器支持完整 4×4 transformation、
四种 billboard、物品展示上下文、亮度覆盖、视距/裁剪范围、阴影、换行与对齐、
背景/透明度、文字阴影、see-through 深度行为，以及按 tick 推进的视觉和传送插值。

这仍不是原版客户端的像素级复刻。自定义字体 provider、多层或特殊物品模型、
发光轮廓、客户端 light map 与后处理仍属于明确边界。要使用对应版本的原版模型、
item definition、字体图集和纹理，需要传入本地 client JAR 或资源包；缺失资源会
使用确定性的 fallback 材质。

## GitHub Release 安装

Release 中推荐发布以下文件：

- `datapack_sandbox_kernel-<version>-py3-none-any.whl`：Jupyter 内核，推荐用户安装；
- `datapack_sandbox_kernel-<version>.tar.gz`：源码分发包；
- `datapack-sandbox-vscode-<version>.vsix`：VS Code 扩展；
- `datapack-sandbox-cli.jar`：独立 CLI，便于手动运行和排查。

Wheel 和 VSIX 已经分别内置 CLI JAR，但不包含 Minecraft 客户端资源、服务端
JAR 或资源包。建议同时上传 `SHA256SUMS.txt`，不要把 Minecraft 资产上传到 Release。

安装 wheel：

```powershell
$py = "C:\Users\<user>\AppData\Local\Programs\Python\Python310\python.exe"
$wheel = "C:\Downloads\datapack_sandbox_kernel-1.0.1-py3-none-any.whl"
& $py -m pip install --upgrade $wheel jupyterlab
& "C:\Users\<user>\AppData\Local\Programs\Python\Python310\Scripts\datapack-sandbox-kernel.exe" --user
```

验证内核：

```powershell
& $py -c "from jupyter_client.kernelspec import KernelSpecManager; print(KernelSpecManager().find_kernel_specs())"
```

输出必须包含 `datapack-sandbox`。

## VS Code 中运行 Notebook

安装 Microsoft **Python** 和 **Jupyter** 扩展，以及 Datapack Sandbox VSIX。
在 VS Code 中选择与安装 wheel 相同的 Python 解释器，然后打开
`examples/jupyter/datapack-sandbox-demo.ipynb`，点击右上角 **Select Kernel**，
选择 **Datapack Sandbox (MCFunction)**。

也可以先启动标准 JupyterLab：

```powershell
& $py -m jupyterlab --no-browser
```

把终端中的 URL 添加为 External Jupyter Server，再选择上述 kernel。不要把 Java
SDK 作为 Jupyter Server 的解释器；Java 25 只由内核内部的 CLI 使用。

## 单元格执行、截图和重置

第一个配置单元格应先应用待处理配置：

```text
%dps version 26.2
%dps config autoRender true
%dps reset --apply
```

之后执行 MCF：

```mcfunction
setblock 0 0 2 minecraft:stone
summon minecraft:zombie 2 0 4
```

每个成功执行的 MCF 单元格都会输出可读摘要和内嵌 PNG。`%dps render` 可以手动
渲染，`%dps render build/state.png` 会额外保存 PNG。配置单元格本身不会自动渲染。

如果版本、datapack 或资源配置发生变化，必须再次执行 `%dps reset --apply`；否则
内核会返回 `RESET_REQUIRED`。

Notebook 的显示输出不会再直接打印完整 JSON；结构化结果保存在 Jupyter message
metadata 中。渲染器仍然是近似原版的 clean-room 渲染，不承诺原版客户端像素级一致。

## 补全和语法检查

Jupyter kernel 已实现 completion 请求，但 IDEA/VS Code Notebook 编辑器不一定会把
自定义 `mcfunction` kernel 当作完整语言服务。VS Code 中的 Spyglass/Datapack
Sandbox 语法补全和诊断主要适用于独立的 `.mcfunction` 文件；Notebook 负责执行和
截图。需要完整编辑体验时，用 VS Code 编辑 `.mcfunction`，再在 Notebook 中运行。

## 从源码构建

```powershell
./gradlew.bat jupyterKernelPackage
```

该任务会先构建 CLI fat JAR，再生成并验证 wheel 与 sdist。发布前应运行：

```powershell
./gradlew.bat jupyterKernelTest
python jupyter/scripts/verify_distribution.py jupyter/dist/*.whl jupyter/dist/*.tar.gz
```
## JVM 桌面实时视窗

standalone JAR 提供基于 GLFW/OpenGL 3.3 的 GPU 实时视窗。命令、输入、检查点和 20 TPS 世界修改在同一个 JVM 会话中串行执行；相机帧只更新矩阵并绘制缓存的 GPU 场景缓冲，不会重复执行沙盒命令：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar viewport `
  --version 26.2 `
  --minecraft-assets "D:\.minecraft\versions\26.1.2\26.1.2.jar" `
  --command "setblock 0 0 2 minecraft:stone"
```

点击场景捕获鼠标，使用 WASD 水平飞行、Space/Shift 升降、滚轮调速，按 Esc 释放鼠标。工具栏支持播放/暂停、单步 tick、恢复自动镜头、保存和返回检查点、重置沙盒及高质量 PNG 导出。按 `T`、`/` 或点击 **Command** 可打开命令控制台；输入期间会显示当前世界对应的补全与非破坏性命令检查，Tab 接受补全、上下键选择候选，Enter 执行。命令输出、错误及状态变化摘要会保留在视窗底部。

默认窗口为 1200×720，HUD 会放大显示。点击 **Settings** 或按 F10 可即时调整鼠标灵敏度、飞行速度、UI 缩放和视野角；也可使用 `--mouse-sensitivity`、`--move-speed`、`--ui-scale`、`--field-of-view` 指定启动值。

HUD 使用系统 TrueType 等宽字体生成抗锯齿 OpenGL 图集。顶栏、状态和控制台没有整块不透明底色；各按钮、文字行和命令输入框使用类似原版聊天框/F3 的半透明黑色衬底。控制台闲置时按实际消息行数收缩，进入命令模式后才展开候选和检查结果。

JVM 视窗只支持键鼠，不提供触屏摇杆。输入通过与 JVM 测试一致的 `PlayerInput`/`PlayerEvent` 记录，但不会隐式模拟原版碰撞、物理、红石或实体 AI。实时画面使用 OpenGL；**Export PNG** 仍走完整软件渲染质量路径，因此不会用实时截图替代质量导出。

## JVM 检查点与 GIF 动图

JVM API 可通过 `sandbox.saveCheckpoint("before-branch")` 记录完整建模世界，并通过
`sandbox.restoreCheckpoint("before-branch")` 重复回到该状态。检查点包含玩家、实体、计分板、
storage、输出和 trace；不会复制不可变的数据包资源，也不会回退单调递增的执行安全预算。

`SandboxGifRecorder` 用于记录多帧并导出 GIF：

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

GIF 编码器由 JVM 和浏览器 Worker 共用；RGBA 帧、延迟和循环参数相同时，输出字节保持一致。
