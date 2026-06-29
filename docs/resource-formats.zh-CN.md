# 资源格式

加载器可以读取目录形式或 zip 形式的数据包，并会按当前 `VersionProfile` 校验 `pack.mcmeta`。

## `pack.mcmeta`

Minecraft Java `1.20.4` 使用 data pack format `26`，资源目录是旧的复数形式：

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

Minecraft Java `26.2` 使用 data pack format `107.1`，当前资源目录是单数形式：

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

兼容 profile 会保留自己的 data pack format。例如 `26.1`、`26.1.1`、`26.1.2` 使用 `101.1`，`1.21.11` 使用 `94.1`，`1.20.5` 到 `1.20.6` 使用 `41`。

旧目录别名只会在当前 `VersionProfile` 允许时接受。格式不匹配会以 `VERSION_MISMATCH` 失败。JSON 解析失败会包含文件路径、resource id 和版本 profile。

新版 pack 如果不用单个 `pack_format`，也可以声明范围。加载器支持 `min_format`/`max_format` 和 `supported_formats`，只要当前 profile 的 data pack format 落在范围内即可。

## 函数

函数文件是 `.mcfunction`，路径会映射为 resource location：

```text
data/demo/function/reward.mcfunction -> demo:reward
```

函数内每行是一条命令。空行和注释会跳过。解析失败会带上文件路径、行号、版本和命令片段。

## Loot Table

当前目录：

```text
data/<namespace>/loot_table/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/loot_tables/**/*.json
```

loot table JSON 会加载为 typed model，并在执行时检查 context type。CLI 可直接运行：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar loot --pack ./pack --table demo:gift --context minecraft:advancement_reward --seed 42
```

`check` 清单可断言确定性 loot 输出，包括物品 id、count、NBT/components 和 seed。

## Predicate

当前目录：

```text
data/<namespace>/predicate/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/predicates/**/*.json
```

predicate 根可以是 object 或 array。array 语义是所有 predicate 都必须为 true。predicate 运行需要上下文；缺少必需上下文时默认严格失败，而不是静默返回 false。

## Advancement

当前目录：

```text
data/<namespace>/advancement/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/advancements/**/*.json
```

加载器会解析 parent、display、criteria、requirements、rewards 和 telemetry flags。运行时按玩家独立记录 progress，支持 grant/revoke/test、事件触发和 reward 执行。

## SNBT 与 Data Path

运行时接受常用 SNBT-lite：

```snbt
{foo:[1b,2s,{bar:"baz"}],flag:true}
```

Data path 支持字段和数字 list index：

```text
foo.bar
foo[0].bar
```

同一套 path 引擎会被 `data` 命令、predicate、loot function 和 advancement condition 复用。写入实体或方块实体 NBT 时，会使用 mcdoc schema 校验顶层字段。

## `.dps.json` 清单

清单可包含：

- `version` 或 `versions`
- `unsupported`
- `packs`
- `world`
- `steps`
- `assertions`

最小示例：

```json
{
  "version": "26.2",
  "packs": ["./pack"],
  "steps": [
    { "load": true },
    { "ticks": 20 },
    { "function": "demo:main" }
  ],
  "assertions": [
    { "score": { "target": "#clock", "objective": "ticks", "equals": 20 } }
  ]
}
```

`world` 可预置方块、实体、玩家、分数、storage、gamerule、time/weather，也可以从 Java 存档导入指定 chunk。`assertions` 可检查 score、storage、player、advancement、output、loot 等行为。
