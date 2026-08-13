# Manifest Regression Tests

## When to use this page

Use a `.dps.json` Manifest when one regression needs a complete pack, predefined world state, ordered actions, several assertions, reusable fixture/baseline files, or the same scenario across Minecraft profiles. Use `run` for a disposable one-shot and QuickTest when the test must live inside a Kotlin/Java suite.

## Prerequisites

- Keep a Manifest and all referenced packs, fixtures, generated `.mcfunction` files, and golden JSON in a stable project tree.
- Current-format packs use singular paths such as `data/<namespace>/function`, `loot_table`, and `advancement`.
- Add `$schema` during authoring for editor validation, and still run CLI validation in CI.
- Pin `version` or `versions` for repeatability rather than relying on the current default.

## Minimal runnable example

`examples/single-function/single-function.dps.json` demonstrates an in-memory function without requiring a pack resource:

```json
{
  "version": "26.2",
  "packs": ["pack"],
  "steps": [
    { "functionText": "say manifest ok", "source": "<example>" }
  ],
  "assertions": [
    { "output": { "command": "say", "contains": "manifest ok", "count": 1 } }
  ]
}
```

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check `
  examples/single-function/single-function.dps.json `
  --validate-schema `
  --report-file build/single-function-report.json
```

## Build a useful case

Start from one observable contract and express it in four layers:

1. **Profile and resources** — `version`/`versions` plus `packs` select behavior and loaded content.
2. **Fixture** — `world` creates only the players, blocks, entities, scores, storage, and other state the contract needs.
3. **Steps** — an ordered array reproduces behavior exactly.
4. **Assertions** — targeted checks describe what a user or following function can observe.

```json
{
  "$schema": "../../schema/manifest/dps-manifest.schema.json",
  "version": "26.2",
  "unsupported": "error",
  "failOnMissingResources": true,
  "seed": 42,
  "packs": ["pack"],
  "world": {
    "players": [{ "name": "Steve", "position": [0, 64, 0] }],
    "scores": [{ "target": "#case", "objective": "runs", "value": 0 }],
    "storage": { "demo:state": { "ready": false } }
  },
  "steps": [
    { "load": true },
    { "function": "demo:main" },
    { "ticks": 2 }
  ],
  "assertions": [
    { "score": { "target": "#case", "objective": "runs", "equals": 1 } },
    { "storage": { "id": "demo:state", "path": "ready", "equals": true } },
    { "output": { "command": "tellraw", "target": "Steve", "contains": "ready", "count": 1 } }
  ]
}
```

Prefer the smallest fixture that proves the contract. Unrelated entities or complete snapshots make failures harder to understand and baselines harder to maintain.

## Step ordering and failure tests

Steps execute strictly from top to bottom. A step selects one action such as load, ticks, function, commands, inline/file function, player/block mutation, event, snapshot, trace, reset, or loot. `source` names generated command content so diagnostics point back to a useful origin.

```json
{
  "steps": [
    {
      "commands": [
        "scoreboard objectives add generated dummy",
        "scoreboard players set #generated generated 1"
      ],
      "source": "<generator:setup.commands>"
    },
    { "mcfunction": "generated/body.mcfunction" },
    { "command": "function demo:missing", "allowFailure": true }
  ],
  "assertions": [
    { "diagnostic": { "step": 2, "code": "RESOURCE_NOT_FOUND", "count": 1 } }
  ]
}
```

Use `allowFailure` only when the error itself is the expected result and constrain it with a diagnostic/trace assertion. Otherwise a failing command should stop that attempt.

## Reuse baselines and fixtures

`include` recursively loads shared Manifest files. Included sections apply before the current file:

- default scalar values come from includes, then the current file overrides them;
- packs, steps, and assertions append in include-to-current order;
- each world section applies sequentially, so later same-name/same-position state wins.

Keep broad environment setup in an included baseline, but keep the behavior steps and expected results near the case. For world-only reuse, `world.extends`, `world.fixture`, and `world.fixtures` apply external fixture files before local fields.

```json
{
  "include": ["../shared/strict-base.dps.json"],
  "world": {
    "fixtures": ["../fixtures/players.json", "../fixtures/arena.json"],
    "weather": "clear"
  },
  "steps": [{ "function": "demo:arena/start" }],
  "assertions": [{ "entityCount": { "tag": "participant", "min": 1 } }]
}
```

## Path resolution

Every relative path belongs to the file that declares it, not necessarily the top-level Manifest or current working directory. This applies to includes, packs, nested fixture references, save imports, `mcfunction`, and snapshot `equalsFile`. Moving an included baseline therefore does not silently rebase paths declared by the including case.

CLI input paths are resolved from the process working directory; after the root Manifest is found, its internal paths use the declaring file's directory.

## Run across versions

Use `versions` with a version-keyed `packs` object when generated resources differ but fixture, steps, and assertions stay the same:

```json
{
  "versions": ["1.20.4", "26.2"],
  "packs": {
    "1.20.4": ["pack-1_20_4"],
    "26.2": ["pack-26_2"]
  },
  "steps": [{ "function": "demo:main" }],
  "assertions": [
    { "output": { "command": "say", "contains": "multi version ok", "count": 1 } }
  ]
}
```

Each profile creates an isolated attempt. Reports retain the selected version and resolved pack paths per attempt, so one failing profile does not hide the others unless `--fail-fast` stops discovery.

## Add coverage and golden state carefully

Coverage can be part of the Manifest contract:

```json
{
  "coverage": {
    "minimumLine": 90,
    "minimumFunction": 80,
    "include": "demo:*",
    "exclude": "demo:generated/*"
  }
}
```

For a durable state baseline, assert a selected snapshot path when possible. `equalsFile` resolves relative to the declaring Manifest:

```json
{
  "assertions": [
    { "snapshot": { "path": "scores.golden", "equalsFile": "expected-snapshot.json" } },
    { "snapshotDiff": { "path": "/scores/golden", "kind": "added", "count": 1 } }
  ]
}
```

Review a CLI `diff` before replacing a golden file. Full snapshots can gain modeled fields, whereas selected score/storage/output contracts are usually more stable.

## Diagnose a failed Manifest

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases/demo.dps.json `
  --strict `
  --verbose `
  --snapshot-on-fail `
  --snapshot-diff-on-fail `
  --trace-file build/demo-trace.jsonl `
  --event-trace-file build/demo-events.jsonl `
  --outputs-file build/demo-outputs.jsonl `
  --report-file build/demo-report.json
```

Read failures in this order: schema/path diagnostic, resource summary and missing references, failed assertion message, snapshot diff, then the narrow command/event trace. This avoids starting with a very large complete snapshot.

## Move an interactive reproduction into a Manifest

1. Replace REPL-created state with a `world` fixture.
2. Copy world-changing commands/functions/events into `steps` in the same order.
3. Convert `inspect` observations into the narrowest assertions.
4. Add a fixed version and seed.
5. Run `--validate-schema`, then `--strict`.
6. Store report/trace artifacts in CI only as evidence; keep the Manifest itself readable.

## Limitations

- `version` and `versions` are mutually exclusive; omitting both selects the release's default profile.
- Include cycles, missing paths, and schema-unknown fields fail validation.
- `--validate-schema` validates structure, not whether modeled behavior is sufficient; `--strict` adds unsupported and missing-resource failures.
- The world remains sparse and clean-room modeled. A passing Manifest is not proof of unmodeled vanilla systems.

## Related pages

- [Manifest Reference](/en/reference/manifest)
- [QuickTest Fixtures](/en/reference/quicktest-fixtures)
- [QuickTest Assertions](/en/reference/quicktest-assertions)
- [CI and Coverage](/en/workflows/ci-coverage)
