# AGENTS.md

## Repo Shape
- Gradle multi-project: `:core` is the embeddable runtime, `:testkit` contains the QuickTest DSL, `:manifest` owns manifest models/runner/schema, `:cli` is the standalone Clikt app, and `:schema-generator` verifies/regenerates pinned vanilla NBT schemas.
- Kotlin/JVM uses Gradle wrapper 9.5.1, Kotlin 2.4.0, and Java 25 toolchains in all JVM modules; Java/classfile errors usually mean missing JDK 25.
- This is a clean-room runtime; do not add Mojang server jars or vendored vanilla server code.
- Built-in Minecraft profiles cover `1.20.4` through `26.2`; `26.2` is the default latest profile.

## Commands
- Use `./gradlew` on Unix and `./gradlew.bat` from PowerShell/cmd on Windows.
- Build standalone CLI jar: `./gradlew :cli:fatJar` -> `cli/build/libs/datapack-sandbox-cli.jar`.
- Focused tests: `./gradlew :testkit:test --tests "moe.afox.dpsandbox.core.SandboxQuickTestTest"` or `./gradlew :cli:test --tests "moe.afox.dpsandbox.cli.RunCommandTest"`.
- Module verification: `./gradlew :core:check`, `:testkit:check`, `:manifest:check`, and `:cli:check`; `:cli:check` also builds/runs all standalone jar smoke tasks.
- Full release gate: `./gradlew releaseCheck` runs all module checks, schema reproducibility, API/architecture checks, integration/example jar smoke, and release artifact/POM verification without publishing.
- Example manifest smoke only: `./gradlew :cli:smokeCliJarExamples` or, after `:cli:fatJar`, `java -jar cli/build/libs/datapack-sandbox-cli.jar check --validate-schema examples`.
- Docs: `npm ci` then `npm run docs:build`; docs deploy workflow uses Node 24 and `DOCS_BASE=/DatapackSandbox/`.

## Generated And Checked Artifacts
- Root `package.json` is for VitePress docs, docs asset synchronization, and Spyglass mcdoc schema tooling; the VS Code extension has its own npm test/build pipeline.
- Runtime NBT schemas are checked in under `schema/vanilla`; ordinary `:core` builds are offline. Regenerate reproducibly with `./gradlew :schema-generator:updateVanillaNbtSchemas` and verify with `:schema-generator:checkGeneratedVanillaNbtSchemas`.
- `schema/manifest/dps-manifest.schema.json` is the canonical CLI/VS Code manifest schema; after schema changes run `./gradlew :cli:smokeCliJarSchemaDocs`.
- Version/command/resource reference docs are checked by jar smoke tasks (`version --docs --check`, `commands --check`, `resources --check`) for both English and zh-CN files.
- VitePress rewrites paired docs: `docs/*.zh-CN.md` feed Chinese routes and matching plain `docs/*.md` files feed `/en/...`; update pairs for user-facing docs.

## Datapack And Test Conventions
- Current-format examples use singular resource directories (`data/<ns>/function`, `loot_table`, `advancement`, etc.); legacy plural aliases are profile-specific, not the default shape for new `26.2` examples.
- Prefer `SandboxQuickTest.singleFunctionText(...)` for focused runtime behavior; use `.dps.json` manifests for full pack/resource regression.
- User-facing manifests live in `examples/*/*.dps.json`; release-gated regression manifests live in `integration-tests/`; pack fixtures live in module test resources.
- Use `--strict` on CLI `check` when you want schema validation, missing resources, and unsupported features to fail instead of warn.
- Ktlint runs through every JVM module's Gradle `check`; public JVM API baselines live in `api/`, dependency locks are module-local, and `architectureCheck` prevents Kotlin God files from exceeding the checked limit.

## CI And Release Notes
- `.github/workflows/ci.yml` runs `releaseCheck` on Linux and Windows, verifies schema reproducibility, and checks the VS Code extension and docs; docs deployment remains a separate workflow.
- Publishing tasks require Maven credentials from `MAVEN_USERNAME`/`MAVEN_PASSWORD`, `MAVEN_USER`/`MAVEN_PASS`, `NEXUS_USERNAME`/`NEXUS_PASSWORD`, `NEXUS_USER`/`NEXUS_PASS`, or `nexusUsername`/`nexusPassword`.
