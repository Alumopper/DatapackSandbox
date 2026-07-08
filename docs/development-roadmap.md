# Datapack Sandbox Development Roadmap

This roadmap describes the documentation-facing development direction for Datapack Sandbox. It mirrors the Chinese roadmap at a higher level and gives English readers the project structure, priority model, and verification expectations.

## Goals

Datapack Sandbox is a clean-room runtime for deterministic local datapack tests. The project aims to make datapack behavior testable without launching a full Minecraft server, while still keeping resource validation, command behavior, and observable output close enough to vanilla semantics for practical regression testing.

The documentation site is organized around four audiences:

| Audience | Primary docs |
| --- | --- |
| Library users writing JVM tests | [Code Test API](/en/guide/code-test-api) |
| Datapack authors checking runtime support | [Command Support](/en/runtime/command-support) and [Resource Formats](/en/resources/resource-formats) |
| Tool authors integrating the sandbox | [Runtime World Model](/en/runtime/world-model) and [Version Profiles](/en/resources/version-profile) |
| Contributors extending behavior | This roadmap and implementation-linked command/resource matrices |

## Phase 1: Resource Coverage

Improve resource indexing and validation for functions, tags, advancements, loot tables, predicates, recipes, and related JSON/SNBT inputs.

Expected documentation outcomes:

- Resource behavior levels remain explicit.
- Manifest examples cover common and failure cases.
- `pack_format`, `supported_formats`, `min_format`, and `max_format` behavior is documented with warnings and version-profile guidance.

## Phase 2: Command Semantics

Expand command modeling from parse-level checks to observable runtime behavior.

Expected documentation outcomes:

- The command support matrix stays synchronized with implementation.
- Each modeled command records behavior level, limitations, trace visibility, and output behavior.
- Text-producing commands expose both rendered output and raw message content where applicable.

## Phase 3: World, Entity, and Player State

Keep the runtime model intentionally sparse, deterministic, and test-oriented while covering the state that datapacks commonly inspect or mutate.

Expected documentation outcomes:

- The runtime world model explains what is stored, what is approximated, and what is intentionally out of scope.
- Fixture and Java save import behavior is documented with clear boundaries.
- Snapshot and diff assertions remain stable enough for regression tests.

## Phase 4: Player Events

Expose reproducible player interaction events for testing advancement, predicate, item, block, damage, and input-driven logic.

Expected documentation outcomes:

- REPL, CLI, manifest, and code-test entry points use the same event vocabulary.
- Event traces document success, matching context, and relevant metadata.

## Phase 5: Test API Ergonomics

Make the code API easy to discover from IDE completion.

Expected documentation outcomes:

- String parameters that represent fixed option sets also have enum overloads.
- Assertions document required parameters, optional filters, count semantics, and failure reporting.
- Examples prefer practical test workflows instead of only low-level runtime construction.

## Phase 6: Version Profiles

Version profiles should keep Minecraft format decisions explainable and reproducible.

Expected documentation outcomes:

- Profile tables list pack/resource formats and vanilla-data assumptions.
- Incompatibilities should be warnings when the sandbox can still continue.
- Array format values such as `[104, 1]` are documented as dotted format values.

## Release Quality

Before a release, the documentation should satisfy these checks:

- VitePress builds successfully.
- CLI-generated command, resource, and version-profile tables pass their drift checks.
- Code API examples compile or are covered by focused tests.
- Chinese and English navigation expose equivalent core pages.
