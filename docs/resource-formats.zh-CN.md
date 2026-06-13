# 资源格式

加载器可以从目录或 zip 文件中读取数据包资源：

对于 Minecraft Java `26.1.2`，`pack.mcmeta` 应使用数据包格式
`101.1`：

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

当当前 `VersionProfile` 允许时，会接受旧式复数目录别名。JSON 解析失败时，错误信息会包含文件路径、资源 id 和版本。

## SNBT 与 Data Path

运行时接受 SNBT-lite 值，例如：

```snbt
{foo:[1b,2s,{bar:"baz"}],flag:true}
```

Data path 支持字段和数字列表索引：

```text
foo.bar
foo[0].bar
```

`data` 命令、谓词、战利品函数和进度条件都使用同一套 path 引擎。
