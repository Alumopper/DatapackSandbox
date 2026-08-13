# Reports and Observability Reference

## When to use this page

Use snapshots, diffs, outputs, command traces, player-event traces, diagnostics, coverage, or combined reports when you need to explain a failure, retain CI evidence, compare behavior across profiles, or feed sandbox state to another tool. Pick the smallest carrier that answers the question; a full trace/report is intentionally richer and more expensive than a snapshot diff.

## Prerequisites

All machine files are UTF-8. JSON files contain one root value; JSONL files contain one independently parseable object per line. Do not scrape colored console output as an API, and do not wrap JSONL lines before archiving them.

## Minimal complete capture

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --pack .\my-pack `
  --function demo:main `
  --snapshot-file build/snapshot.json `
  --snapshot-diff-file build/diff.json `
  --trace-file build/trace.jsonl `
  --event-trace-file build/events.jsonl `
  --outputs-file build/outputs.jsonl `
  --coverage-file build/coverage.json `
  --report-file build/report.json
```

For a first investigation, start with `diff.json` and failed entries in `trace.jsonl`; keep `report.json` as the self-contained CI artifact. Add `--trace` only when human-readable console trace output is also wanted.

## Choose an artifact

| Carrier | Format | Contains | Best for |
| --- | --- | --- | --- |
| Snapshot | JSON object | Complete modeled world plus version | Golden state, offline tools |
| Snapshot diff | JSON array | Added/removed/changed entries at JSON Pointer paths | Isolating one run's effects |
| Output stream | JSONL | Chat/title/actionbar/data/warning events and structured payloads | User-visible and semantic output assertions |
| Command trace | JSONL | Per-command source, executor, result, outputs, diffs, error | Function-chain/root-cause analysis |
| Player-event trace | JSONL | Normalized player event, advancement updates and criterion failures | Trigger/advancement debugging |
| Coverage | JSON | Function invocations, executable-line hits, totals/percentages/failures | CI gates and untested paths |
| Run report | JSON object | All major evidence for one `run` | Single downloadable artifact |
| Check report | JSON array | One result per manifest with per-version attempts | Regression matrix evidence |

The separate files are easier to stream and index. The report repeats data by design so it remains useful after the original process and console log are gone.

## Snapshots

`--snapshot` prints stable JSON; `--snapshot-file` writes it. A snapshot contains modeled time/weather/world settings, scores and objectives, storage, players/entities/blocks, schedules, outputs/traces where applicable, and the selected version. It is a sandbox state contract, not a vanilla level-save file.

Use a baseline only when the whole modeled state is part of the contract. Otherwise prefer targeted assertions or a diff, because new legitimate modeled fields can make a broad golden noisy.

Core equivalents:

```kotlin
val json = sandbox.snapshotJson()
val stableText = sandbox.snapshotString()
```

Snapshot output is bounded by `SandboxLimits.maxSnapshotBytes` (10,000,000 bytes by default). The limit is checked on stable UTF-8 JSON text.

## Snapshot diffs

`--snapshot-diff` prints changes from immediately before the requested run lifecycle to the final state; `--snapshot-diff-file` writes the array. Each entry has:

```json
{
  "path": "/scores/#runner/runs",
  "kind": "changed",
  "before": 0,
  "after": 1
}
```

Paths use JSON Pointer, so `~` and `/` within a key are escaped according to that syntax. Kinds are `added`, `removed`, and `changed`. Missing `before`/`after` fields depend on kind.

In the Core API, `SnapshotDiff.diff(before, after)` includes every serialized difference, while `SnapshotDiff.stateDiff(before, after)` excludes trace/output bookkeeping so a command's own observability records do not appear as the behavior being explained. CLI run reports and tracked Serve results use the state-oriented form.

## Output events

`--outputs-file` writes one `OutputEvent` per line. Important fields are:

| Field | Meaning |
| --- | --- |
| `tick` | Modeled game time when emitted |
| `command` | Output-producing operation/root label |
| `channel` | Semantic channel such as chat/actionbar/title/data/warning |
| `targets` | Resolved recipients or affected targets |
| `text` | Normalized plain text suitable for common assertions |
| `rawText` | Original text representation when retained |
| `segments` | Styled text runs (text/color/emphasis) |
| `payload` | Command-specific structured JSON |
| `source` | Function/file/line/command origin when available |

Prefer `payload` for automation because it preserves identifiers, counts, positions, and before/after values without parsing prose. Use `text` for what a user reads and `rawText` only when original component syntax is itself the contract. They are not interchangeable.

On the JVM, `world.outputs` retains events; `addOutputListener` streams newly recorded events without polling. Remove the listener when its owner is disposed.

## Command traces

Enable tracing whenever `--trace-file`, a report, or trace-dependent output is requested. Each `CommandTraceEvent` records:

- `tick`, normalized `command`, and root;
- source file/line/current command and function stack;
- executor and execution position;
- `success` and nested `commandsExecuted`;
- output count and the related output events;
- state diff entries in full trace mode;
- `errorCode` and `errorMessage` when execution failed.

One outer command can execute nested functions, so do not treat trace line count as command-budget usage. Follow source/function stack and `commandsExecuted` together.

Diagnostics in combined reports are projections of failed traces. They contain version where known, code, message, command/root, file/line, success, and commands executed. Use them for editor/CI summaries, then link back to the full trace for surrounding output and diffs.

## Trace filters

Pass `--trace-filter` multiple times; all filters must match. A filter without `=` performs a broad substring/exact-root search across command, source/function stack, diagnostics, outputs, and diffs.

| Key | Matching rule | Example |
| --- | --- | --- |
| `root` | Exact command root | `root=execute` |
| `command` | Exact command | `command=say ready` |
| `contains` | Command or error-message substring | `contains=demo:` |
| `function` | Function-stack id substring | `function=demo:tick` |
| `file`, `source` | Source-file substring | `file=data/demo/function` |
| `selector`, `target` | Command, executor, output target/payload | `target=Alex` |
| `success` | Strict `true`/`false` | `success=false` |
| `error`, `diagnostic` | Boolean presence or code/message/command/root match | `diagnostic=true` |
| `error-code`, `diagnostic-code` | Exact diagnostic enum, case-insensitive input | `error-code=RESOURCE_NOT_FOUND` |
| `error-message`, `diagnostic-message` | Error-message substring | `error-message=missing` |
| `outputs` | Exact count or boolean nonempty/empty | `outputs=true` |
| `output` | Count expression or output text/channel/target/payload match | `output=ready` |
| `output-channel` | Exact channel | `output-channel=warning` |
| `output-payload` | JSON path existence or `path=jsonValue` equality | `output-payload=position.x=10` |
| `diff`, `path`, `state` | Diff path/rendered-entry substring | `path=/storage/demo:state` |
| `score`, `scores` | Same, restricted to `/scores` | `score=runs` |
| `storage` | Same, restricted to `/storage` | `storage=phase` |

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack .\my-pack --function demo:main `
  --trace-file build/failed-execute.jsonl `
  --trace-filter root=execute `
  --trace-filter success=false
```

Filters change export/console selection, not what the sandbox records in memory. Quote shell-sensitive values, especially JSON payload comparisons.

## Player-event traces

`--event-trace-file` writes the complete `PlayerEventTraceEvent` list as JSONL. Each event keeps the normalized player/event context, resolved target information, success/error, advancement updates, and detailed criterion failures. This separates “the interaction reached the runtime” from “an advancement criterion did not match.”

When debugging a trigger:

1. Confirm the event trace exists and the normalized type/target/item/block fields are correct.
2. Read criterion failures before changing the advancement JSON.
3. Check updates and resulting player advancement progress.
4. Correlate commands triggered by rewards through command trace source/function stack.

## Coverage

Coverage accumulates function invocations and executable-line hits in the current sandbox. CLI options include:

- `--coverage` for a human summary;
- `--coverage-file` for JSON;
- `--minimum-line-coverage` / `--min-line-coverage` / `--min-coverage`;
- `--minimum-function-coverage` / `--min-function-coverage`;
- repeatable `--coverage-include` and `--coverage-exclude` filters.

Threshold failures contribute to the command's failure outcome. Report percentages together with the filter set: excluding helpers or generated functions changes the denominator and must be reviewable. Reset cumulative Core/Serve coverage explicitly when a new measurement window begins.

Coverage tells you what ran, not whether it was asserted correctly. Pair it with state/output assertions and inspect functions with invocations but weak line coverage.

## Combined reports

### `run --report-file`

The root is one object containing:

- `version`, `passed`, `gameTime`, `commands`, and entity count;
- `assertionFailures`;
- full `outputs`, selected `traces`, derived `diagnostics`, and `eventTraces`;
- final `snapshot` and state-only `snapshotDiffs` from the pre-run snapshot;
- resource summary/overlays/missing references;
- coverage.

Trace filters apply to the trace list and therefore to diagnostics derived from it. Do not infer that an empty filtered trace means no commands executed; use the top-level `commands` and recorded filter configuration in your CI job.

### `check --report-file`

The root is an array. Each manifest result contains path, pass status, messages, output/trace/diagnostic/event counts and arrays, plus `attempts`. Each attempt records version, resolved packs, pass/messages/events, optional snapshot, diffs, resource summary, and coverage. Matrix manifests therefore remain one logical result with separately auditable version attempts.

## CI consumption pattern

1. Use the CLI exit code as the primary gate; artifact parsing should not turn a failed command green.
2. Always upload the combined report on failure, then optionally the smaller JSONL streams.
3. Record the CLI version, target profile, command line, and coverage/trace filters beside artifacts.
4. Parse JSONL incrementally and cap downstream ingestion, even though the sandbox already caps outputs/snapshots.
5. Ignore unknown object fields, but fail clearly when a required field has the wrong type.
6. Redact or restrict artifacts when commands/output/storage contain user or secret data.

## Limitations

- Report schemas may gain modeled fields. Consumers should ignore unknown fields rather than reject the whole artifact.
- Full command state diffs require repeated snapshots and can be expensive on large sparse worlds.
- Output retention is capped by `maxOutputEvents`; snapshot/report creation is capped by `maxSnapshotBytes` where applicable.
- Traces describe sandbox-modeled behavior, not packets, server threads, entity AI, or other unmodeled vanilla internals.
- A filtered export is a view, not a complete audit log.

## Related pages

- [CI and Coverage](/en/workflows/ci-coverage)
- [QuickTest Assertions](/en/reference/quicktest-assertions)
- [Core API](/en/reference/core-api)
- [Serve JSONL](/en/reference/serve-jsonl)
- [Troubleshooting](/en/guide/troubleshooting)
