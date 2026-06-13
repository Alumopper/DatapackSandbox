# Resource Formats

The loader reads datapack resources from directories or zip files:

For Minecraft Java `26.1.2`, `pack.mcmeta` should use data pack format
`101.1`:

```json
{
  "pack": {
    "pack_format": 101.1,
    "description": "Example datapack"
  }
}
```

- `data/<namespace>/function/**/*.mcfunction`
- `data/<namespace>/loot_table/**/*.json`
- `data/<namespace>/predicate/**/*.json`
- `data/<namespace>/advancement/**/*.json`

Legacy plural aliases are accepted where the active `VersionProfile` allows
them. JSON parse failures include the file path, resource id, and version.

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
