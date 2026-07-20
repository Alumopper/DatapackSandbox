# 渲染与 Jupyter Kernel

Datapack Sandbox 可以把当前模拟世界渲染为 PNG，也可以在 Jupyter Notebook
中持续运行原生 `mcfunction` 单元格。它使用 clean-room JVM runtime，不会启动
Minecraft 客户端或原版服务器。

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
