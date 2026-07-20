# Datapack Sandbox Jupyter example

Build and install the development Kernel, then open the notebook:

```powershell
./gradlew.bat prepareJupyterKernel
python -m pip install -e "./jupyter[test]"
datapack-sandbox-kernel install --user
jupyter lab examples/jupyter/datapack-sandbox-demo.ipynb
```

The example intentionally works without Mojang assets by using deterministic
fallback textures. Run `%dps assets <client.jar>` in the first code cell to use
assets from a locally installed Minecraft client.
