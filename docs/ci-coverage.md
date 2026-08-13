# CI and Coverage

## When to use this page

Use this workflow when datapack regressions must run before merge or release with deterministic profiles, stable exit codes, machine-readable evidence, and optional line/function coverage gates.

## Prerequisites

CI needs Java 25 and a known CLI jar. Check out manifests, packs, fixtures, and golden files together so relative paths stay inside the workspace. Pin the Datapack Sandbox release used to produce the jar; pin each scenario's Minecraft profile with `version` or `versions`.

## Minimal CI command

```powershell
New-Item -ItemType Directory -Force build/dps | Out-Null
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples `
  --strict `
  --validate-schema `
  --coverage `
  --coverage-file build/dps/coverage.json `
  --report-file build/dps/report.json `
  --trace-file build/dps/trace.jsonl `
  --event-trace-file build/dps/events.jsonl `
  --outputs-file build/dps/outputs.jsonl
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Always propagate the Java process exit code. Artifact upload steps should use an “always” condition so reports from failed checks are retained.

## A GitHub Actions job

```yaml
jobs:
  datapack-regression:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - name: Build CLI
        run: ./gradlew :cli:fatJar
      - name: Run manifests
        run: |
          mkdir -p build/dps
          java -jar cli/build/libs/datapack-sandbox-cli.jar check cases \
            --strict --validate-schema --coverage \
            --coverage-file build/dps/coverage.json \
            --report-file build/dps/report.json \
            --trace-file build/dps/trace.jsonl \
            --outputs-file build/dps/outputs.jsonl
      - name: Upload Datapack Sandbox evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: datapack-sandbox
          path: build/dps
```

For a consumer repository, replace the build step with retrieval of the pinned released jar and verify its checksum according to that repository's supply-chain policy.

## Manifest discovery and strictness

`check <input>...` accepts manifest files and directories. Directories are recursively searched for `.dps.json` files, and results are reported per manifest. Keep generated reports outside the discovery directory so a later job does not accidentally treat artifacts as inputs.

| Option | CI effect |
| --- | --- |
| `--validate-schema` | Reject malformed fields and shapes before execution |
| `--strict` | Schema validation plus unsupported-as-error and missing-resource failure |
| `--fail-fast` | Stop after the first failed manifest |
| `--verbose` | Print deterministic resource summaries and manifest outputs |
| `--snapshot-on-fail` | Include full failed state in console/report evidence |
| `--snapshot-diff-on-fail` | Include the smaller initial-to-final state diff |
| `--seed <n>` | Override manifest seeds for a controlled diagnostic run |

Use `--fail-fast` for a quick presubmit or expensive cases; omit it in scheduled/full jobs when collecting every failure is more valuable.

## Coverage semantics

Line coverage counts executable, non-empty, non-comment `.mcfunction` lines that the sandbox executes. Function coverage counts loaded functions invoked at least once. Coverage is cumulative for a run/attempt and is profile-specific because the loaded resource set can differ by version.

A manifest can own the threshold:

```json
{
  "version": "26.2",
  "packs": ["pack"],
  "coverage": {
    "minimumLine": 90,
    "minimumFunction": 80,
    "include": "demo:*",
    "exclude": "demo:generated/*"
  },
  "steps": [{ "function": "demo:main" }]
}
```

The CLI can also impose or override a gate for ad-hoc `run`/`check` jobs:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases `
  --coverage `
  --minimum-line-coverage 85 `
  --minimum-function-coverage 75 `
  --coverage-include "demo:*" `
  --coverage-exclude "demo:generated/*" `
  --coverage-file build/dps/coverage.json
```

Include/exclude values are resource-id glob filters. Start from the namespace (`demo:*`) rather than immediately excluding every helper: a narrow denominator can make a percentage pass while important loaded code remains unmeasured. Commit thresholds close to the Manifest when they describe the scenario's contract; use CLI thresholds for a repository-wide policy.

## Interpret artifacts

| File | Keep when | What it answers |
| --- | --- | --- |
| `report.json` | Always | Which manifest/version failed and with which state/resources/coverage? |
| `coverage.json` | Coverage is enabled | Which functions/lines were hit or missed? |
| `trace.jsonl` | Call chains matter | Which command ran, from which source, and what did it change/emit? |
| `events.jsonl` | Player behavior is tested | Which trigger/criterion matched or failed? |
| `outputs.jsonl` | User-visible output matters | Which chat/title/sound/structured event was produced? |
| snapshot/diff | State regression needs a baseline | What modeled state exists or changed? |

`check --report-file` writes an array of manifest results. A multi-version result contains one attempt per profile, with its own packs, messages, snapshot/diffs, resources, and coverage. Consumers should ignore unknown JSON fields so reports can gain modeled details compatibly.

## Exit codes and retry policy

| Code | Meaning | Typical CI response |
| --- | --- | --- |
| `0` | All checks and thresholds passed | Continue |
| `1` | Assertion or coverage threshold failed | Fail the job; inspect report/diff |
| `2` | Invalid CLI/Manifest input | Fail; fix schema/path/generator output |
| `3` | Unsupported, version, resource, command, interrupt, or missing-context diagnostic | Fail; inspect diagnostic and support boundary |

These failures are deterministic for the same inputs and release. Blind retries usually hide a real problem. Retry only infrastructure acquisition/upload failures, not a completed sandbox check.

## Safety and reproducibility

- Pass `--max-commands`, `--max-function-depth`, `--max-ticks-per-run`, `--max-output-events`, and `--max-snapshot-bytes` for untrusted generated cases.
- Do not put secrets in fixtures, commands, output text, NBT, or report paths; artifacts intentionally preserve rich state.
- Keep golden snapshots under review and update them only after examining a rendered `diff`.
- Run the same gate on supported CI operating systems if paths, zip creation, or generated files differ by platform.
- Treat renderer images as clean-room diagnostics, not vanilla pixel-golden assertions.

## Repository-level gates

Inside this repository, `:cli:smokeCliJarExamples` validates all example manifests with the built jar. `:cli:smokeCliJar` also checks schemas, documentation reference tables, CLI examples, rendering, and distribution behavior. `releaseCheck` is the full module/publication gate.

## Limitations

- Coverage proves that sandbox model code paths executed; it does not prove unsupported or partially modeled behavior equals vanilla.
- Only loaded functions participate. Version-specific pack maps and filters can change the denominator.
- A full snapshot may be large or contain incidental modeled state; prefer targeted assertions and diffs for durable contracts.
- Datapack Sandbox does not replace the small number of tests that specifically require networking, real clients, redstone/physics, or a vanilla server.

## Related pages

- [Manifest Regression Tests](/en/workflows/manifest-tests)
- [CLI Reference](/en/reference/cli)
- [Reports and Observability](/en/reference/reports-observability)
- [Command Support](/en/runtime/command-support)
