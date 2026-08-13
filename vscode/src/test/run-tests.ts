import * as assert from "node:assert/strict";
import * as path from "node:path";
import { BoundedTextBuffer } from "../boundedTextBuffer";
import { buildCheckArgs, buildRunArgs } from "../commands";
import { completionEditPlan, completionReplacementEnd, prepareCommandLine } from "../completionUx";
import { DebugValueStore, findInitialTraceIndex } from "../debugValues";
import { appendEventPage } from "../eventPages";
import { discoverManifestPaths, inferFunctionContext, isManifest } from "../functionContext";
import { collectLogicalCommands, findFunctionReference, resourceIdAt, tokenAt } from "../mcfunctionDocument";
import { TraceEvent } from "../model";
import { parsePlayerEventPayload } from "../playerEvent";
import { parseManifestReport, parseRunReport } from "../reports";
import { matchingResourceEntries } from "../resourceNavigation";
import { parseServeResponse } from "../serveProtocol";

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
  ["collects mcfunction commands using runtime continuation rules", () => {
    assert.deepEqual(collectLogicalCommands("# comment\n  /execute as @a \\\n    at @s run say hi\n$say $(message)\n"), [
      { line: 1, endLine: 2, startCharacter: 2, command: "execute as @a at @s run say hi", leadingSlash: true, macro: false },
      { line: 3, endLine: 3, startCharacter: 0, command: "say $(message)", leadingSlash: false, macro: true },
    ]);
  }],
  ["finds function references for navigation", () => {
    assert.deepEqual(findFunctionReference("execute if entity @s run function #demo:load/main", 45), { id: "demo:load/main", tag: true, start: 34, end: 49 });
    assert.equal(findFunctionReference("say demo:load/main", 8), undefined);
  }],
  ["classifies mcfunction hover tokens", () => {
    assert.equal(tokenAt("execute as @s run say hi", 12)?.kind, "selector");
    assert.equal(tokenAt("execute as @s run say hi", 3)?.kind, "command");
    assert.equal(tokenAt("loot give @s loot demo:gift", 25)?.kind, "resource");
  }],
  ["keeps completion moving across inserted syntax", () => {
    assert.deepEqual(completionEditPlan("scoreboard", true), { text: "scoreboard ", retrigger: true });
    assert.deepEqual(completionEditPlan("type=", false), { text: "type=", retrigger: true });
    assert.deepEqual(completionEditPlan("NoGravity:", false), { text: "NoGravity:", retrigger: true });
    assert.deepEqual(completionEditPlan("{}", false), { text: "{}", cursorOffset: 1, retrigger: true });
    assert.deepEqual(completionEditPlan('{"text":""}', false), { text: '{"text":""}', cursorOffset: 9, retrigger: true });
    assert.deepEqual(completionEditPlan("minecraft:zombie", false), { text: "minecraft:zombie", retrigger: false });
  }],
  ["offers root completions on blank and macro lines but rejects slash commands", () => {
    assert.deepEqual(prepareCommandLine("    ", 4), { buffer: "", cursor: 0, offset: 4 });
    assert.equal(prepareCommandLine("  /score", 8), undefined);
    assert.deepEqual(prepareCommandLine("$function demo:main", 19), { buffer: "function demo:main", cursor: 18, offset: 1 });
    assert.equal(prepareCommandLine("# comment", 3), undefined);
  }],
  ["replaces the remainder of a partially edited completion", () => {
    assert.equal(completionReplacementEnd("scoreboard players", 5, "scoreboard "), 11);
    assert.equal(completionReplacementEnd("kill @e[type=]", 10, "type="), 13);
    assert.equal(completionReplacementEnd("summon zombie {NoGravity:", 18, "NoGravity:"), 25);
    const operator = "scoreboard players operation @s runs += @s runs";
    const operatorCursor = operator.indexOf("+") + 1;
    assert.equal(completionReplacementEnd(operator, operatorCursor, "+="), operatorCursor + 1);
  }],
  ["finds function tags and generic datapack resource ids for definitions", () => {
    assert.deepEqual(resourceIdAt("function #demo:load", 15), { id: "demo:load", tag: true, start: 9, end: 19 });
    assert.deepEqual(resourceIdAt("loot give @s loot demo:gift", 25), { id: "demo:gift", tag: false, start: 18, end: 27 });
  }],
  ["matches active resource definitions by id, tag, and function context", () => {
    const resources = [
      { type: "function", id: "demo:shared", file: "function.mcfunction", pack: "pack", active: true, behavior: "modeled" },
      { type: "loot_table", id: "demo:shared", file: "loot.json", pack: "pack", active: true, behavior: "modeled" },
      { type: "tag/function", id: "demo:shared", file: "tag.json", pack: "pack", active: true, behavior: "modeled" },
      { type: "predicate", id: "demo:shared", file: "old.json", pack: "pack", active: false, behavior: "modeled" },
    ];
    assert.deepEqual(matchingResourceEntries(resources, "demo:shared", false, false).map((entry) => entry.file), ["function.mcfunction", "loot.json"]);
    assert.deepEqual(matchingResourceEntries(resources, "demo:shared", false, true).map((entry) => entry.file), ["function.mcfunction"]);
    assert.deepEqual(matchingResourceEntries(resources, "demo:shared", true, true).map((entry) => entry.file), ["tag.json"]);
  }],
  ["constructs run arguments", () => { assert.deepEqual(buildRunArgs("main.mcfunction", "demo:main", "report.json", "26.2", ["pack"], ["errors"], true), ["run", "--version", "26.2", "--report-file", "report.json", "--trace-filter", "errors", "--pack", "pack", "--mcfunction", "demo:main=main.mcfunction", "--mcfunction-id", "demo:main", "--strict"]); }],
  ["omits version when CLI default is requested", () => { assert.deepEqual(buildRunArgs("main.mcfunction", "demo:main", "report.json", undefined, [], [], false), ["run", "--report-file", "report.json", "--mcfunction", "demo:main=main.mcfunction", "--mcfunction-id", "demo:main"]); }],
  ["bounds captured child-process output while preserving its tail", () => {
    const output = new BoundedTextBuffer(8);
    output.append("first-");
    output.append("second");
    assert.equal(output.toString(), "[earlier output omitted]\nt-second");
  }],
  ["does not mark an exactly full output buffer as truncated", () => {
    const output = new BoundedTextBuffer(4);
    output.append("full");
    assert.equal(output.toString(), "full");
  }],
  ["validates child-process output limits", () => {
    assert.throws(() => new BoundedTextBuffer(0), RangeError);
  }],
  ["constructs manifest arguments", () => { assert.deepEqual(buildCheckArgs("test.dps.json", "report.json", false, []), ["check", "test.dps.json", "--report-file", "report.json", "--snapshot-diff-on-fail"]); }],
  ["parses run reports", () => { assert.equal(parseRunReport('{"version":"26.2","passed":true}').passed, true); assert.throws(() => parseRunReport("[]")); }],
  ["parses manifest reports", () => { assert.equal(parseManifestReport('[{"path":"a.dps.json","passed":false}]')[0].path, "a.dps.json"); assert.throws(() => parseManifestReport("{}")); }],
  ["accepts documented and legacy serve hello envelopes", () => {
    const result = { protocol: "dps-jsonl", defaultVersion: "26.2", capabilities: { render: true }, versions: ["26.2"] };
    assert.equal(parseServeResponse({ id: null, ok: true, result })?.id, null);
    assert.equal(parseServeResponse({ ok: true, result })?.id, null);
  }],
  ["rejects id-less ordinary serve responses", () => {
    assert.equal(parseServeResponse({ ok: true, result: { version: "26.2" } }), undefined);
    assert.equal(parseServeResponse({ ok: false, error: { code: "INPUT_FORMAT" } }), undefined);
  }],
  ["appends incremental serve event pages", () => {
    assert.deepEqual(appendEventPage(["first"], { from: 1, total: 2, items: ["second"] }), ["first", "second"]);
  }],
  ["detects replaced or inconsistent serve event history", () => {
    assert.equal(appendEventPage(["stale"], { from: 1, total: 0, items: [] }), undefined);
    assert.equal(appendEventPage([], { from: 2, total: 2, items: [] }), undefined);
    assert.equal(appendEventPage([], { from: 0, total: 0, items: ["overflow"] }), undefined);
  }],
  ["normalizes player event JSON to Serve parameters", () => {
    assert.deepEqual(parsePlayerEventPayload('{"player":"Steve","type":"killed_entity","id":"minecraft:zombie"}'), {
      player: "Steve", type: "killed_entity", id: "minecraft:zombie",
    });
    assert.deepEqual(parsePlayerEventPayload("player Steve killed_entity minecraft:zombie"), {
      event: "player Steve killed_entity minecraft:zombie",
    });
    assert.throws(() => parsePlayerEventPayload("not-an-event"));
  }],
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
