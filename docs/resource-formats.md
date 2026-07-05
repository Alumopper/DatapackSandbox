# Resource Formats

The loader reads datapack resources from directories or zip files. It validates
`pack.mcmeta` against the active `VersionProfile`.

For Minecraft Java `1.20.4`, use data pack format `26` and legacy plural
resource directories:

```json
{
  "pack": {
    "pack_format": 26,
    "description": "Example 1.20.4 datapack"
  }
}
```

- `data/<namespace>/functions/**/*.mcfunction`
- `data/<namespace>/loot_tables/**/*.json`
- `data/<namespace>/predicates/**/*.json`
- `data/<namespace>/advancements/**/*.json`
- `data/<namespace>/recipes/**/*.json`
- `data/<namespace>/item_modifiers/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

For Minecraft Java `26.2`, use data pack format `107.1` and current singular
resource directories:

```json
{
  "pack": {
    "pack_format": 107.1,
    "description": "Example 26.2 datapack"
  }
}
```

- `data/<namespace>/function/**/*.mcfunction`
- `data/<namespace>/loot_table/**/*.json`
- `data/<namespace>/predicate/**/*.json`
- `data/<namespace>/advancement/**/*.json`
- `data/<namespace>/recipe/**/*.json`
- `data/<namespace>/item_modifier/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

Compatibility profiles keep their own data pack formats. For example,
`26.1`/`26.1.1`/`26.1.2` use `101.1`, `1.21.11` uses `94.1`, and `1.20.5`
through `1.20.6` use `41`.

Legacy aliases are accepted only where the active `VersionProfile` allows them.
Format mismatches fail with `VERSION_MISMATCH`. JSON parse failures include the
file path, resource id, and version.

For newer packs that declare a supported range instead of a single
`pack_format`, the loader accepts `min_format`/`max_format` and
`supported_formats` when the active profile's data pack format is inside the
range.

## Recipes, Item Modifiers, and Tags

Recipes and item modifiers are loaded as raw JSON resources and included in the
resource index. The sandbox does not yet execute the full crafting system or
every item modifier function, but these resources are version-profile checked,
participate in pack overlay behavior, and can be inspected from API or REPL.

Current directories:

```text
data/<namespace>/recipe/**/*.json
data/<namespace>/item_modifier/**/*.json
```

Legacy aliases, when allowed by the active profile:

```text
data/<namespace>/recipes/**/*.json
data/<namespace>/item_modifiers/**/*.json
```

Tags are loaded from `data/<namespace>/tags/<registry>/**/*.json`. The loader
keeps the registry directory, tag id, `replace`, and `values`; values may be
strings or `{ "id": "...", "required": false }` objects, and tag references
keep the leading `#`.

REPL inspection:

```text
inspect recipe
inspect item_modifier
inspect tags [registry]
inspect resources [type]
```

The resource index records type, id, source pack, file path, active/overridden
state, and pack overlay relationships.

## SNBT and Data Paths

The runtime accepts SNBT-lite values such as:

```snbt
{foo:[1b,2s,{bar:"baz"}],flag:true}
```

Data paths support fields and numeric list indexes:

```text
foo.bar
foo[0].bar
```

The same path engine is used by `data`, predicates, loot functions, and
advancement conditions.
