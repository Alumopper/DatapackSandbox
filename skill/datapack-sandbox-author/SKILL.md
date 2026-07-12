---
name: datapack-sandbox-author
description: Design, implement, debug, and validate complex Minecraft Java datapack features with the Datapack Sandbox CLI. Use when Codex must create or extend datapacks, author `.mcfunction` and JSON resources, build `.dps.json` regression manifests, investigate command/resource support, analyze trace and snapshot reports, or establish an agentic datapack development loop without launching a vanilla server.
---

# Datapack Sandbox Author

Build complex datapacks as testable systems. Treat Datapack Sandbox as a fast development oracle, while respecting its modeled/partial/unsupported boundary.

## Establish the toolchain

1. Locate the datapack workspace and applicable `AGENTS.md` files.
2. Locate the CLI JAR with `scripts/dps_agent.py`; prefer `DPS_CLI_JAR`, then a repository-local built or bundled JAR.
3. Run `doctor` before editing. If no JAR exists in a Datapack Sandbox source checkout, build `:cli:fatJar` first.
4. Read [references/cli-playbook.md](references/cli-playbook.md) when invocation, reports, or diagnostics are unfamiliar.

```powershell
python <skill>/scripts/dps_agent.py --workspace . doctor
```

## Convert the request into observable behavior

Write a compact acceptance map before implementation:

- triggers and public entry functions;
- persistent state in scoreboard, storage, advancements, or entity/block NBT;
- outputs and rewards visible to players;
- timing, scheduling, and reset behavior;
- invalid, repeated, and multiplayer paths;
- target Minecraft profile(s).

Prefer assertions on state and outputs over assertions on implementation details. Ask only when product behavior is genuinely ambiguous; otherwise state assumptions and proceed.

## Check support before committing to architecture

Query the selected profile and loaded pack instead of relying on memory:

```powershell
python <skill>/scripts/dps_agent.py --workspace . catalog --version 26.2 --pack ./pack
```

Inspect generated command/resource catalogs. If a required behavior is partial or unsupported:

1. redesign around modeled primitives when semantics remain acceptable;
2. isolate the unsupported edge behind one function;
3. record the simulation boundary in the final handoff;
4. never claim vanilla parity from a sandbox-only pass.

Read `selected-profile.json` and use its exact `dataPackFormat` in `pack.mcmeta`; preserve fractional formats such as `107.1`. Treat helper metadata warnings as release blockers unless `supported_formats` intentionally covers the selected profile.

Read [references/authoring-patterns.md](references/authoring-patterns.md) for resource layout and system decomposition.

## Implement in vertical slices

Build one end-to-end behavior at a time:

1. Create the public trigger or entry function.
2. Add the minimum internal state transition.
3. Add one observable output or reward.
4. Add a focused `.dps.json` scenario proving it.
5. Run the scenario before expanding the feature.

Keep public functions thin. Put state-machine transitions, validation, rewards, and cleanup in namespaced internal functions. Make initialization and repeated execution intentionally idempotent.

Treat execution context as part of the API. A manifest `function` step uses the sandbox's synthetic server context; invoke player-bound entries through a player event or a command such as `execute as Steve run function <id>`.

Use singular resource directories for current profiles such as `data/<namespace>/function`, `advancement`, `loot_table`, and `predicate`. Consult the live profile or existing pack before changing legacy layouts.

## Build a three-layer test suite

Use all applicable layers:

1. **Function smoke** — execute one function or command sequence quickly.
2. **Subsystem manifest** — fixture + trigger + focused assertions for one mechanic.
3. **Scenario manifest** — full player journey, timing, rewards, reset, and negative paths.

Add matrix coverage with `versions` only when cross-version support is required. Keep deterministic seeds for random behavior. Include repeated-trigger and no-op tests for systems that must not duplicate rewards or state.

See [references/manifest-testing.md](references/manifest-testing.md) for reusable manifest patterns.

## Run the diagnostic loop

Run strict checks and preserve reports:

```powershell
python <skill>/scripts/dps_agent.py --workspace . check ./tests --strict
```

When a check fails, inspect in this order:

1. schema/input diagnostics;
2. missing or overridden resources;
3. first failed trace event;
4. per-command output events;
5. snapshot diff around the first unexpected transition;
6. assertion messages last.

Fix the earliest incorrect transition, not the final assertion symptom. Narrow large traces with `--trace-filter` or a focused manifest. Re-run the smallest failing case before the full suite.

## Apply release gates

Before finishing:

1. Run every affected focused manifest with `--strict`.
2. Run the complete manifest directory with schema validation.
3. Inspect missing references and overridden resources.
4. Confirm pack metadata matches every target profile.
5. Confirm generated reports contain no unexpected failed traces.
6. Re-run load/init behavior when the pack owns persistent objectives or storage, and confirm reload does not corrupt or duplicate state.
7. Summarize modeled boundaries and validation commands.

Do not launch or vendor a Mojang server unless the user explicitly requires external vanilla differential testing. Do not edit generated reports as evidence; regenerate them from source.
