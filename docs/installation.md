# Install and Obtain

## When to use this page

Start here when deciding which Datapack Sandbox artifact belongs in a project. The standalone CLI is the normal choice for datapack authors and CI. `testkit` belongs in Kotlin/Java tests, `core` in applications that own the sandbox lifecycle, and `renderer` in JVM applications that also produce images. The VS Code extension, Jupyter kernel, and browser playground are separate integrations built on those runtime surfaces.

## Prerequisites

- Running released JVM artifacts requires Java 25. Confirm with `java -version` before debugging class-file errors.
- Building the repository's JVM modules from source also requires a JDK 25 toolchain.
- Building the documentation or web playground requires Node.js and npm; the repository lockfile is installed with `npm ci`.
- The documented release is `1.1.0`. Built-in Minecraft profiles span `1.20.4` through `26.2`, and `26.2` is the default.

## Choose an artifact

| Need | Artifact | How it is consumed |
| --- | --- | --- |
| Run packs, manifests, REPL, viewport, or JSONL service | CLI fat jar | `java -jar datapack-sandbox-cli.jar ...` |
| Add fluent tests to a JVM test suite | `testkit` | Gradle/Maven test dependency |
| Build a custom JVM host | `core` | Gradle/Maven application dependency |
| Render PNG/GIF or compile a live scene | `renderer` + `core` | Gradle/Maven application dependencies |
| Edit and debug datapacks inside VS Code | VS Code extension `0.4.1` | Install `Alumopper.datapack-sandbox-vscode` from Marketplace; the matching CLI jar is bundled |
| Run persistent notebook cells | Jupyter kernel | Install the Python wheel; the matching CLI jar is bundled |
| Embed local execution in a website | `@datapack-sandbox/vitepress-playground` | npm dependency and bundled Worker |

The CLI fat jar is an application distribution, not a replacement for the JVM libraries and not a stable classpath dependency.

## Minimal runnable example

From a Windows PowerShell checkout, build the standalone jar and verify both the application and its profile catalog:

```powershell
.\gradlew.bat :cli:fatJar
java -jar .\cli\build\libs\datapack-sandbox-cli.jar --help
java -jar .\cli\build\libs\datapack-sandbox-cli.jar version
```

The artifact is written to `cli/build/libs/datapack-sandbox-cli.jar`. A practical smoke test is:

```powershell
java -jar .\cli\build\libs\datapack-sandbox-cli.jar check `
  .\examples\single-function\single-function.dps.json `
  --validate-schema
```

A successful check proves that Java can start the jar, the bundled manifest schema is readable, and a real repository example can execute.

## Install the CLI

### Use a released jar

Download the CLI artifact from the project release you intend to use and keep the filename or wrapper script stable in CI. Invoke it with `java -jar`; no Mojang server jar is needed. Record `version` output with build logs when profile drift matters.

### Build from source

```powershell
.\gradlew.bat :cli:fatJar
.\gradlew.bat :cli:smokeCliJar
```

`fatJar` creates the executable. `smokeCliJar` additionally exercises the standalone distribution, schema, checked documentation tables, examples, rendering, and concrete CLI samples. Use `releaseCheck` when validating every module and publication artifact.

## Add JVM dependencies

Configure the project repository together with Maven Central and Mojang's library repository:

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

Use only the modules the host needs:

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
    implementation("moe.afox.dpsandbox:renderer:1.1.0") // only for images/live scenes
}
```

`testkit` brings in `core` transitively. Inside this repository or an included multi-project build, use `project(":testkit")`, `project(":core")`, or `project(":renderer")` instead of published coordinates. Keep all Datapack Sandbox modules on the same version.

## Install integrations

### VS Code and Jupyter

Install the published [Datapack Sandbox extension from VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=Alumopper.datapack-sandbox-vscode), or run:

```powershell
code --install-extension Alumopper.datapack-sandbox-vscode
```

Marketplace is the recommended channel because VS Code can update the extension normally. Release `0.4.1` bundles its matching CLI jar; only set `datapackSandbox.cliJarPath` when deliberately testing a different local build. The release VSIX remains available for offline or pinned installation. See [VS Code Extension](/en/guide/vscode-extension).

The Jupyter package can be installed from its release wheel or built from `jupyter/`; the wheel also bundles the CLI jar, while `DPS_CLI_JAR` can explicitly select another build. See [Jupyter](/en/integrations/jupyter).

### Web playground

```bash
npm install @datapack-sandbox/vitepress-playground
```

Import both components and package CSS. The browser runtime is a local Worker and does not use the JVM jar. Rendering assets are also not bundled: the user must explicitly import a matching client JAR when vanilla models and textures are required. See [Playground](/en/guide/playground).

## Verify and upgrade

After replacing an artifact, run these checks in increasing scope:

1. `java -jar ... version` confirms startup and the built-in profile set.
2. `java -jar ... schema --output build/dps-manifest.schema.json` confirms schema export.
3. `java -jar ... check <your-cases> --strict` checks the real project.
4. JVM consumers run their test suite; web consumers rebuild so the Worker bundle and generated profiles are refreshed.

Upgrade the CLI, JVM module coordinates, editor backend, and browser package deliberately. A manifest can pin `version`, but that field selects a Minecraft behavior profile—it does not select the Datapack Sandbox release.

## Troubleshooting installation

| Symptom | Check |
| --- | --- |
| `UnsupportedClassVersionError` | `java -version` must report Java 25 |
| Main class or jar not found | Use the exact `cli/build/libs/datapack-sandbox-cli.jar` path |
| Dependency cannot resolve | Verify all three repositories and the `1.1.0` coordinate |
| Editor/kernel starts a different runtime | Clear an unintended `datapackSandbox.cliJarPath`/`DPS_CLI_JAR` override to return to the bundled jar |
| Web Worker is unavailable | Serve the site over HTTP(S), include the package Worker in the build, and review CSP/CORS |
| Rendered assets are missing | Explicitly supply/import a matching client JAR or `assets/` directory |

## Limitations

- This is a clean-room runtime. No install path bundles Mojang server jars or vanilla server code.
- The CLI jar is intended for process invocation, not as a stable library dependency.
- Building from source uses the repository's pinned toolchains; an older local Java or Node installation is not silently substituted.
- Minecraft client assets are user-supplied for both JVM and browser rendering.

## Related pages

- [Getting Started](/en/guide/getting-started)
- [Run with the CLI](/en/workflows/cli)
- [QuickTest Overview](/en/guide/code-test-api)
- [Playground](/en/guide/playground)
