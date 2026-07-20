# Datapack Sandbox Jupyter Kernel

This package runs native `mcfunction` cells against a persistent Datapack
Sandbox JVM session. It displays command output, snapshot diffs, and optional
PNG renders directly below each notebook cell.

Development usage from a checkout:

```powershell
./gradlew.bat :cli:fatJar
$env:DPS_CLI_JAR = "../cli/build/libs/datapack-sandbox-cli.jar"
python -m pip install -e ./jupyter jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab
```

For a GitHub Release, download the `datapack_sandbox_kernel-1.0.1-py3-none-any.whl`
asset and install it with the Python interpreter used by Jupyter:

```powershell
python -m pip install --upgrade datapack_sandbox_kernel-1.0.1-py3-none-any.whl jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab --no-browser
```

The wheel bundles the standalone Datapack Sandbox CLI JAR, but not Minecraft
client assets. Select **Datapack Sandbox (MCFunction)** as the notebook kernel.
Successful MCF cells display a readable summary and an inline PNG by default;
use `%dps render output.png` to save a frame explicitly.

Minecraft assets are never bundled. Configure a user-owned client JAR or
resource pack with `%dps assets <path>` before rendering with vanilla textures.

## Release checklist

The GitHub Release should contain the versioned `.whl`, `.tar.gz`, VS Code
`.vsix`, standalone CLI JAR, and a `SHA256SUMS.txt` file. The wheel and VSIX
already include the CLI JAR. Never include Minecraft client/server JARs or
extracted `assets/` directories.

For each successful MCF cell, the kernel emits a readable summary and inline
PNG. Structured results are kept in Jupyter message metadata so VS Code does
not print a large raw JSON block. Completion support is implemented at the
kernel protocol level; full syntax highlighting and diagnostics are currently
provided by the VS Code extension for standalone `.mcfunction` files, not by
Notebook cell editors.
