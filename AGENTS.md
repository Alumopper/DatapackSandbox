# AGENTS.md

## Repo Shape
- Gradle multi-project: `:core` is the embeddable sandbox/API; `:cli` is the standalone Clikt app and depends on `:core`.
- Kotlin/JVM uses Gradle wrapper 9.5.1, Kotlin 2.4.0, and Java 25 toolchains in both modules; Java/classfile errors usually mean missing JDK 25.
- This is a clean-room runtime; do not add Mojang server jars or vendored vanilla server code.
- Built-in Minecraft profiles cover `1.20.4` through `26.2`; `26.2` is the default latest profile.

## Commands
- Use `./gradlew` on Unix and `./gradlew.bat` from PowerShell/cmd on Windows.
- Build standalone CLI jar: `./gradlew :cli:fatJar` -> `cli/build/libs/datapack-sandbox-cli.jar`.
- Focused tests: `./gradlew :core:test --tests "moe.afox.dpsandbox.core.SandboxQuickTestTest"` or `./gradlew :cli:test --tests "moe.afox.dpsandbox.cli.RunCommandTest"`.
- Module verification: `./gradlew :core:check` and `./gradlew :cli:check`; `:cli:check` also builds/runs all standalone jar smoke tasks.
- Full release gate: `./gradlew releaseCheck` runs `:core:check`, `:cli:check`, fat jar creation, and release artifact/POM verification without publishing.
- Example manifest smoke only: `./gradlew :cli:smokeCliJarExamples` or, after `:cli:fatJar`, `java -jar cli/build/libs/datapack-sandbox-cli.jar check --validate-schema examples`.
- Docs: `npm ci` then `npm run docs:build`; docs deploy workflow uses Node 24 and `DOCS_BASE=/DatapackSandbox/`.

## Generated And Checked Artifacts
- Root `package.json` is for VitePress docs and Spyglass mcdoc schema tooling only; there is no npm test/lint pipeline.
- `core:processResources` depends on `generateVanillaNbtSchemas`, which can run `npm install` and fetch `SpyglassMC/vanilla-mcdoc`; outputs under `build/generated/vanilla-mcdoc` are generated.
- `docs/dps-manifest.schema.json` is source for the CLI-bundled schema via `cli:processResources`; after schema changes run `./gradlew :cli:smokeCliJarSchemaDocs`.
- Version/command/resource reference docs are checked by jar smoke tasks (`version --docs --check`, `commands --check`, `resources --check`) for both English and zh-CN files.
- VitePress rewrites paired docs: `docs/*.zh-CN.md` feed Chinese routes and matching plain `docs/*.md` files feed `/en/...`; update pairs for user-facing docs.

## Datapack And Test Conventions
- Current-format examples use singular resource directories (`data/<ns>/function`, `loot_table`, `advancement`, etc.); legacy plural aliases are profile-specific, not the default shape for new `26.2` examples.
- Prefer `SandboxQuickTest.singleFunctionText(...)` for focused runtime behavior; use `.dps.json` manifests for full pack/resource regression.
- Manifest examples live in `examples/*/*.dps.json`; pack fixtures live in `core/src/test/resources/packs`, and CLI tests often reference them via module-relative paths.
- Use `--strict` on CLI `check` when you want schema validation, missing resources, and unsupported features to fail instead of warn.
- There is no ktlint/detekt/prettier config; Gradle `test`/`check` and docs build are the verified quality gates.

## CI And Release Notes
- The only checked-in GitHub Actions workflow is docs deployment; do not assume `releaseCheck` is currently enforced by GitHub Actions.
- Publishing tasks require Maven credentials from `MAVEN_USERNAME`/`MAVEN_PASSWORD`, `MAVEN_USER`/`MAVEN_PASS`, `NEXUS_USERNAME`/`NEXUS_PASSWORD`, `NEXUS_USER`/`NEXUS_PASS`, or `nexusUsername`/`nexusPassword`.
