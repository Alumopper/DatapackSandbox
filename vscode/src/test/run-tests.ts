import * as assert from "node:assert/strict";
import * as path from "node:path";
import { buildCheckArgs, buildRunArgs } from "../commands";
import { DebugValueStore, findInitialTraceIndex } from "../debugValues";
import { discoverManifestPaths, inferFunctionContext, isManifest } from "../functionContext";
import { TraceEvent } from "../model";
import { parseManifestReport, parseRunReport } from "../reports";

const tests: Array<[string, () => void]> = [
  ["infers current-format function id and pack root", () => {
    const root = path.parse(process.cwd()).root;
    const file = path.join(root, "workspace", "pack", "data", "demo", "function", "tools", "main.mcfunction");
    const result = inferFunctionContext(file);
    assert.equal(result.id, "demo:tools/main");
    assert.equal(result.packRoot, path.join(root, "workspace", "pack"));
  }],
  ["falls back for standalone functions", () => { assert.equal(inferFunctionContext(path.join(process.cwd(), "My Test.mcfunction")).id, "sandbox:my_test"); }],
  ["discovers only manifest suffixes", () => { assert.equal(isManifest("example.dps.json"), true); assert.equal(isManifest("example.json"), false); }],
  ["discovers and sorts manifest paths", () => { assert.deepEqual(discoverManifestPaths(["z.dps.json", "notes.json", "a.dps.json"]), ["a.dps.json", "z.dps.json"]); }],
  ["constructs run arguments", () => { assert.deepEqual(buildRunArgs("main.mcfunction", "demo:main", "report.json", "26.2", ["pack"], ["errors"], true), ["run", "--version", "26.2", "--report-file", "report.json", "--trace-filter", "errors", "--pack", "pack", "--mcfunction", "demo:main=main.mcfunction", "--mcfunction-id", "demo:main", "--strict"]); }],
  ["omits version when CLI default is requested", () => { assert.deepEqual(buildRunArgs("main.mcfunction", "demo:main", "report.json", undefined, [], [], false), ["run", "--report-file", "report.json", "--mcfunction", "demo:main=main.mcfunction", "--mcfunction-id", "demo:main"]); }],
  ["constructs manifest arguments", () => { assert.deepEqual(buildCheckArgs("test.dps.json", "report.json", false, []), ["check", "test.dps.json", "--report-file", "report.json", "--snapshot-diff-on-fail"]); }],
  ["parses run reports", () => { assert.equal(parseRunReport('{"version":"26.2","passed":true}').passed, true); assert.throws(() => parseRunReport("[]")); }],
  ["parses manifest reports", () => { assert.equal(parseManifestReport('[{"path":"a.dps.json","passed":false}]')[0].path, "a.dps.json"); assert.throws(() => parseManifestReport("{}")); }],
  ["runs to the first configured breakpoint", () => {
    const file = path.resolve("data/demo/function/main.mcfunction");
    const traces: TraceEvent[] = [1, 2, 3].map((line) => ({ tick: 0, command: `line ${line}`, root: "demo:main", success: true, commandsExecuted: 1, outputs: 0, source: { file, line } }));
    assert.equal(findInitialTraceIndex(traces, new Map([[file, new Set([3])]]), false), 2);
    assert.equal(findInitialTraceIndex(traces, new Map(), false), -1);
    assert.equal(findInitialTraceIndex(traces, new Map(), true), 0);
  }],
  ["exposes nested JSON as expandable debug variables", () => {
    const values = new DebugValueStore();
    const root = values.add({ entity: { id: "minecraft:pig", position: [1, 2, 3] }, count: 1 });
    const entity = values.variables(root).find((variable) => variable.name === "entity");
    assert.ok(entity && entity.variablesReference > 0);
    const position = values.variables(entity.variablesReference).find((variable) => variable.name === "position");
    assert.ok(position && position.variablesReference > 0);
    assert.deepEqual(values.variables(position.variablesReference).map((variable) => variable.value), ["1", "2", "3"]);
  }],
];

let failures = 0;
for (const [name, test] of tests) {
  try { test(); console.log(`PASS ${name}`); } catch (error) { failures += 1; console.error(`FAIL ${name}`); console.error(error); }
}
if (failures) process.exitCode = 1;
