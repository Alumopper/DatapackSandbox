# Version Profiles

The sandbox supports multiple Minecraft Java datapack profiles. The default
profile is `26.2`, `26.1.2` remains available for compatibility, and the
minimum supported legacy datapack profile is `1.20.4`.

| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |
|---|---:|---:|---:|---|---|
| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.20.5` | 21 | 3837 | 41 | `1.20.5:1.20.5` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.20.6` | 21 | 3839 | 41 | `1.20.6:1.20.6` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21` | 21 | 3953 | 48 | `1.21:1.21` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.1` | 21 | 3955 | 48 | `1.21.1:1.21.1` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.2` | 21 | 4080 | 57 | `1.21.2:1.21.2` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.3` | 21 | 4082 | 57 | `1.21.3:1.21.3` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.4` | 21 | 4189 | 61 | `1.21.4:1.21.4` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.5` | 21 | 4325 | 71 | `1.21.5:1.21.5` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.6` | 21 | 4435 | 80 | `1.21.6:1.21.6` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.7` | 21 | 4438 | 81 | `1.21.7:1.21.7` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.8` | 21 | 4440 | 81 | `1.21.8:1.21.8` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.9` | 21 | 4554 | 88 | `1.21.9:1.21.9` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.10` | 21 | 4556 | 88 | `1.21.10:1.21.10` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `1.21.11` | 21 | 4671 | 94.1 | `1.21.11:1.21.11` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `26.1` | 25 | 4786 | 101.1 | `26.1:26.1` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `26.1.1` | 25 | 4788 | 101.1 | `26.1.1:26.1.1` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `26.1.2` | 25 | 4790 | 101.1 | `26.1.2:26.1.2` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |
| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements` |

Runtime NBT validation loads a versioned `mcdoc-nbt-schema-v2` resource. Each
profile resolves its schema by profile id, with the default profile kept as a
fallback for older generated resources.

Command root recognition is also scoped by profile. A command root that exists
in the active vanilla profile but is not implemented by the sandbox follows the
configured unsupported policy (`warn`, `ignore`, or `error`). A root command
that is not present in the active profile fails as `INPUT_FORMAT`.

Use the CLI to list the active built-in profiles:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version
```

Generate the profile table used by this document:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs --output docs/version-profile-table.md
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs --check docs/version-profile.md
```

Use `--check` in CI to fail when the generated English Markdown table no
longer appears in the checked-in document.

Compare two profiles to inspect pack format, NBT schema, resource directory,
command-root, and registry differences:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
```

Add `--json` to either command form for scriptable profile metadata or diff
reports:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json 1.20.4 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json --output build/profile-diff.json 1.20.4 26.2
```

Check manifests can run a version matrix:

```json
{
  "versions": ["1.20.4", "26.1.2", "26.2"],
  "packs": {
    "1.20.4": ["./packs/demo-1_20_4"],
    "26.1.2": ["./packs/demo-26_1_2"],
    "26.2": ["./packs/demo-26_2"]
  },
  "steps": [
    { "load": true }
  ],
  "assertions": []
}
```

The sandbox uses clean-room runtime code. Mojang's `server.jar` is only a local
reference source for generated reports such as command trees and registries; it
is not embedded in the standalone CLI jar.

Reference sources:

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://github.com/misode/mcmeta/tree/summary/versions
- https://minecraft.wiki/w/Commands
- https://minecraft.wiki/w/Loot_table
- https://minecraft.wiki/w/Predicate
- https://minecraft.wiki/w/Advancement_definition
- https://c4k3.github.io/wiki.vg/Data_Generators.html
