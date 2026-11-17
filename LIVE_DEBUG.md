# Live debugging over JSONL

Run the CLI with Java 25: `java -jar datapack-sandbox-cli.jar serve --protocol jsonl`.
The greeting advertises `capabilities.liveDebug: true` and `debugProtocolVersion: 1`.
Requests retain the normal `{id, method, params}` envelope and independent responses.

Create a sandbox and apply its world fixture before starting. Install breakpoints before
load initialization so they also apply to `#minecraft:load`.

| Method | Parameters | Result |
| --- | --- | --- |
| `debug.setBreakpoints` | `breakpoints: [{function, line}]`, one-based physical command lines | Replaces all command breakpoints |
| `debug.start` | `load` (default true), `functions`, `stopOnEntry` | Starts one execution worker |
| `debug.continue` | none | Resumes the suspended command |
| `debug.step` | `mode: into / over / out` | Pauses before the next command at the selected physical depth |
| `debug.pause` | none | Requests suspension at the next command boundary |
| `debug.state` | none | Reads a copy of the frozen pause snapshot and active/paused status |
| `debug.inspect` | `frame` (activation ID), `command` (`data get` or `scoreboard players get`) | Reads a value using the frame's entity, position, rotation and dimension on a detached pause snapshot |
| `debug.stop` | none | Cancels execution and wakes a paused worker |

Events have `{event, body}` and no request ID. `debug.stopped` includes the reason,
command, physical frames with activation IDs and execution context, and a world
snapshot. The callback runs before command side effects. `debug.error` includes the
diagnostic, generated location and final snapshot; `debug.terminated` follows both
successful execution and errors. Cancellation sets `cancelled` in the termination event.

While execution owns the world, use the debug controls and frozen snapshot. Other
world queries and mutations are rejected. Standard input EOF cancels the session,
wakes a suspended execution, and joins its worker. Source statements, logical stacks,
and variable addresses are responsibilities of the MCFPP adapter.

The same patch restores the local bundled 26.3 profile (data version 5023, data pack
format 121), `compute`, native floating point data writes, and inline numeric predicates.
Build with `./gradlew :cli:fatJar`; the artifact is `cli/build/libs/datapack-sandbox-cli.jar`.
