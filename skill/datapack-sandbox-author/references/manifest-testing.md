# Manifest Testing Patterns

## Contents

- Focused subsystem scenario
- Player event scenario
- Timing boundary
- Failure and diagnostic scenario
- Cross-version matrix
- Assertion selection

## Focused subsystem scenario

```json
{
  "version": "26.2",
  "packs": ["../pack"],
  "seed": 7,
  "world": {
    "players": [{ "name": "Steve" }],
    "scores": [{ "target": "Steve", "objective": "feature_state", "value": 0 }]
  },
  "steps": [
    { "function": "demo:feature/start" }
  ],
  "assertions": [
    { "score": { "target": "Steve", "objective": "feature_state", "equals": 1 } },
    { "trace": { "contains": "scoreboard players set" } }
  ]
}
```

Use the bundled schema as the authority for exact assertion fields. Validate every new manifest with `--validate-schema` rather than guessing field names.

## Player event scenario

```json
{
  "version": "26.2",
  "packs": ["../pack"],
  "steps": [
    {
      "player": {
        "name": "Steve",
        "inventory": [{ "id": "minecraft:carrot_on_a_stick", "count": 1 }]
      }
    },
    {
      "event": {
        "player": "Steve",
        "type": "item_used",
        "item": "minecraft:carrot_on_a_stick"
      }
    }
  ],
  "assertions": [
    { "advancement": { "player": "Steve", "id": "demo:use_item", "done": true } }
  ]
}
```

Use player events for advancement/predicate/reward chains instead of calling the reward function directly. Add a separate direct-function test only for internal diagnosis.

## Timing boundary

Create paired checks around the transition:

```json
{
  "steps": [
    { "function": "demo:feature/start" },
    { "ticks": 19 }
  ],
  "assertions": [
    { "score": { "target": "#system", "objective": "timer", "min": 1 } }
  ]
}
```

Add another scenario at tick 20 asserting completion. Separate scenarios produce clearer failures than one manifest with many resets.

## Failure and diagnostic scenario

For intentional command failures, use `allowFailure` and assert the diagnostic supported by the schema. Keep negative cases separate from happy-path scenarios so strict checks remain readable.

## Cross-version matrix

```json
{
  "versions": ["1.21.11", "26.2"],
  "packs": {
    "1.21.11": ["../pack-1.21"],
    "26.2": ["../pack-26.2"]
  },
  "steps": [{ "function": "demo:smoke" }],
  "assertions": [{ "output": { "contains": "ready" } }]
}
```

Use a matrix only for behavior intended to be portable. A single unsupported resource should not make unrelated profile coverage ambiguous.

## Assertion selection

Prefer the closest stable observable:

- scoreboard/storage assertions for state transitions;
- player/entity/block assertions for owned game state;
- advancement and loot assertions for resource pipelines;
- output assertions for user-visible messages;
- trace assertions for dispatch and call-chain evidence;
- snapshot diff assertions for broad regression boundaries.

Avoid giant golden snapshots as the only test. Pair broad snapshots with focused assertions that explain failures.
