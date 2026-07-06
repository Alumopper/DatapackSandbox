# 版本 Profile

沙盒支持多个 Minecraft Java 数据包 profile。默认 profile 是 `26.2`，`26.1.2` 仍可用于兼容测试，最低支持的旧数据包 profile 是 `1.20.4`。

| Profile | Java | Data version | Data pack format | NBT schema | 资源目录 |
|---|---:|---:|---:|---|---|
| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.20.5` | 21 | 3837 | 41 | `1.20.5:1.20.5` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.20.6` | 21 | 3839 | 41 | `1.20.6:1.20.6` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21` | 21 | 3953 | 48 | `1.21:1.21` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.1` | 21 | 3955 | 48 | `1.21.1:1.21.1` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.2` | 21 | 4080 | 57 | `1.21.2:1.21.2` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.3` | 21 | 4082 | 57 | `1.21.3:1.21.3` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.4` | 21 | 4189 | 61 | `1.21.4:1.21.4` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.5` | 21 | 4325 | 71 | `1.21.5:1.21.5` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.6` | 21 | 4435 | 80 | `1.21.6:1.21.6` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.7` | 21 | 4438 | 81 | `1.21.7:1.21.7` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.8` | 21 | 4440 | 81 | `1.21.8:1.21.8` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.9` | 21 | 4554 | 88 | `1.21.9:1.21.9` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.10` | 21 | 4556 | 88 | `1.21.10:1.21.10` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `1.21.11` | 21 | 4671 | 94.1 | `1.21.11:1.21.11` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `26.1` | 25 | 4786 | 101.1 | `26.1:26.1` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `26.1.1` | 25 | 4788 | 101.1 | `26.1.1:26.1.1` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `26.1.2` | 25 | 4790 | 101.1 | `26.1.2:26.1.2` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |
| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名 `functions`、`loot_tables`、`predicates`、`advancements` |

## Profile 控制的内容

`VersionProfile` 控制：

- Minecraft 版本 id。
- Java 版本提示。
- Data version。
- Data pack format。
- 当前版本允许的资源目录。
- 当前版本的命令根识别。
- NBT schema 资源选择。

运行时 NBT 校验会加载版本化的 `mcdoc-nbt-schema-v2` 资源。每个 profile 会按 profile id 解析 schema；旧生成资源缺失时默认 profile 会作为 fallback。

命令根识别也受 profile 影响。一个命令根如果存在于当前原版 profile，但沙盒没有实现，会按 unsupported 策略处理：`warn`、`ignore` 或 `error`。一个命令根如果不存在于当前 profile，则按 `INPUT_FORMAT` 失败。

## CLI 查看

使用 CLI 查看内置 profile：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version
```

生成本文档使用的 profile 表格：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs --output docs/version-profile-table.md
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs --check docs/version-profile.md
java -jar cli/build/libs/datapack-sandbox-cli.jar version --docs --locale zh-CN --check docs/version-profile.zh-CN.md
```

`--check` 可用于 CI：如果生成的 Markdown 表格不再出现在已提交文档中，命令会失败。默认检查英文表格；加上 `--locale zh-CN` 可检查中文本地化表格。Gradle `check` 生命周期会通过 standalone jar smoke task 同时运行英文和中文文档检查。

比较两个 profile，查看 pack format、NBT schema、资源目录、命令根和 registry 差异：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
```

给任一形式加上 `--json` 可以输出适合脚本和 CI artifact 使用的 profile 元数据或差异报告：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json 1.20.4 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json --output build/profile-diff.json 1.20.4 26.2
```

## 多版本清单

`check` 清单可以运行版本矩阵：

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

不同版本的数据包可以独立指定目录，这样每个版本都能使用正确的 `pack_format` 和资源目录布局。

## 参考来源

沙盒运行时代码是洁净室实现。Mojang `server.jar` 只作为本地生成 command tree、registry 等报告的参考源，不会嵌入 standalone CLI jar。

主要参考：

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://github.com/misode/mcmeta/tree/summary/versions
- https://minecraft.wiki/w/Commands
- https://minecraft.wiki/w/Loot_table
- https://minecraft.wiki/w/Predicate
- https://minecraft.wiki/w/Advancement_definition
- https://c4k3.github.io/wiki.vg/Data_Generators.html
