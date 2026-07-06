# 运行时世界模型

沙盒世界是内存中的稀疏模型：

- 初始世界是完全虚空。方块只有在命令、fixture 或存档导入后才存在。
- `setblock`、`fill`、`clone` 只修改沙盒保存的方块状态和方块实体 NBT；不执行物理、红石、邻居更新、掉落或 block entity tick。
- 方块可以包含 block state 和初始方块实体 SNBT，例如 `minecraft:chest{Items:[...]}`。
- 运行时默认创建名为 `Steve` 的玩家。玩家可被 selector 匹配，并暴露可读的原版风格 NBT。
- 实体保存确定性 NBT 视图，包含生成的 `id`、`UUID`、`Pos`、`Tags`，以及 vanilla mcdoc schema 允许的字段。非玩家实体 NBT 可通过 `data` 修改，未知顶层字段会被拒绝。
- 方块实体由生成的 mcdoc block-to-block-entity 映射识别。其 NBT 视图包含原版风格 `id`、`x`、`y`、`z` 和 schema 字段。未知自定义顶层字段不会被当作任意 JSON 保存。
- 玩家 NBT 可读但通过 `data` 写入会失败；NBT 视图包含 `SelectedItemSlot`、`Inventory`、当前非空主手 `SelectedItem` 和 `EnderItems`。移动玩家应使用 `tp`/`teleport`、manifest/API fixture 或玩家事件。
- Tick 会执行 scheduled function、tick tag 和沙盒玩家事件。实体 AI 和重力不模拟；沙盒不会自动注入 `NoAI`。

## 世界状态字段

snapshot 会包含用于测试的确定性状态，包括：

- `blocks`：稀疏方块与方块实体 NBT。
- `entities`：非玩家实体、位置、旋转、tag、属性、乘骑关系和 NBT。
- `players`：玩家位置、维度、游戏模式、背包、末影箱物品、advancement progress、XP、生命值、饥饿值、效果、recipe、输入事件和出生点。
- `scores`、`storage`、`gamerules`。
- `gameTime`、`dayTime`、`weather`、`difficulty`、`defaultGameMode`、`seed`。
- `worldSpawn`、`worldBorder`、`forcedChunks`、`biomes`。
- `outputs`：命令输出、warning、title、sound、visual 等事件。

这些字段用于测试断言和调试；它们不等价于完整原版存档格式。

## 测试世界 Fixture

`.dps.json` 清单和 quick-test API 都可以在执行步骤前定义初始世界。支持的 fixture 包括：

- `blocks`：方块 id、state properties 和校验后的方块实体 NBT。
- `regions`：用闭区间 `from`/`to`、方块 id、可选 state properties 和可选 NBT 批量铺设稀疏世界区域。
- `entities`：实体类型、UUID、位置、维度、health、tag、旋转、vehicle/passengers、装备、active effects、attributes 和校验后的实体 NBT。
- `players`：位置、维度、游戏模式、背包、末影箱物品、advancement progress、XP、生命值、饥饿值；新建玩家会使用当前 `defaultGameMode`。
- `scores`、`storage`、`gamerules`、`gameTime`、`dayTime`、`weather`。

示例清单：

```json
{
  "version": "26.2",
  "packs": ["./pack"],
  "world": {
    "regions": [
      { "from": [0, 63, 0], "to": [3, 63, 3], "id": "minecraft:stone" }
    ],
    "blocks": [
      { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
    ],
    "entities": [
      {
        "type": "minecraft:pig",
        "uuid": "00000000-0000-0000-0000-000000000101",
        "pos": [1, 64, 0],
        "dimension": "minecraft:the_nether",
        "health": 8.0,
        "vehicle": "00000000-0000-0000-0000-000000000102",
        "tags": ["fixture"],
        "equipment": {
          "weapon.mainhand": { "id": "minecraft:iron_sword" }
        },
        "effects": [
          { "id": "minecraft:strength", "duration": 80, "amplifier": 2 }
        ],
        "attributes": {
          "minecraft:max_health": 12.0
        }
      },
      {
        "type": "minecraft:cow",
        "uuid": "00000000-0000-0000-0000-000000000102",
        "pos": [1, 64, 1],
        "tags": ["fixture_vehicle"],
        "passengers": ["00000000-0000-0000-0000-000000000101"]
      }
    ],
    "players": [
      { "name": "Alex", "position": [2, 65, 3], "xp": 5 }
    ],
    "storage": {
      "demo:env": { "ready": true }
    }
  },
  "steps": [],
  "assertions": [
    {
      "entity": {
        "type": "minecraft:pig",
        "tag": "fixture",
        "dimension": "minecraft:the_nether",
        "health": 8.0,
        "vehicle": "00000000-0000-0000-0000-000000000102",
        "nbt": { "path": "Health", "equals": 8.0 },
        "equipment": { "slot": "weapon.mainhand", "id": "minecraft:iron_sword" },
        "effect": { "id": "minecraft:strength", "duration": 80, "amplifier": 2 },
        "attribute": { "id": "minecraft:max_health", "equals": 12.0 }
      }
    }
  ]
}
```

## Java 存档导入

世界 fixture 可以从现有 Minecraft Java Edition 存档导入选定 chunk：

```json
{
  "world": {
    "save": {
      "path": "./saves/MyWorld",
      "dimension": "minecraft:overworld",
      "chunks": [[0, 0], [1, 0]],
      "includeBlocks": true,
      "includeBlockEntities": true,
      "includeEntities": true
    }
  }
}
```

如果按方块范围更方便，可以用 `"from": [x, y, z]` 和 `"to": [x, y, z]` 代替 `chunks`；沙盒会把范围展开成 chunk 坐标。

导入器读取 Java Anvil `.mca` 文件，路径包括：

- 主世界：`region/` 和 `entities/`
- 下界：`DIM-1/region/` 和 `DIM-1/entities/`
- 末地：`DIM1/region/` 和 `DIM1/entities/`
- 自定义维度：`dimensions/<namespace>/<id>/region/` 和 `entities/`

支持 GZip、zlib、uncompressed 和 LZ4 chunk 压缩。导入内容包括方块状态、方块实体和实体 NBT，并继续经过当前版本 profile 的 NBT 校验。

不会导入 lighting、heightmap、POI、scheduled block tick、chunk ticket、playerdata、region metadata 或完整原版世界生命周期状态。

## Vanilla mcdoc 数据

运行：

```powershell
.\gradlew.bat generateVanillaNbtSchemas --no-daemon --console=plain
```

该任务会下载 `SpyglassMC/vanilla-mcdoc` 到 `build/vanilla-mcdoc`，安装官方 `@spyglassmc/mcdoc` 解析器，解析 mcdoc AST，并生成 `build/generated/vanilla-mcdoc/resources/vanilla-nbt-schemas.json`。

`:core` 的资源管线依赖该任务，所以运行时会优先从 classpath 加载生成 schema；只有资源缺失时才回退到旧的保守内置 schema。

`generateVanillaMcdocIndex` 任务仍保留为人工查看和实验用的词法索引，但它不是 NBT 校验来源。更细粒度的 `since`/`until` 版本属性裁剪仍是后续工作；当前运行时消费 upstream vanilla mcdoc snapshot，并在顶层字段保持严格未知字段诊断。

## 客户端/服务端嵌入对比

引入真实 Minecraft 客户端或服务端对这个项目更重：会带来渲染、资源、认证/会话、native/platform、服务端线程和世界生命周期约束。对数据包本地测试而言，有限洁净室模拟更轻、更确定、更适合 CLI 和测试环境，也更容易对缺失上下文做严格诊断。

原版运行时仍适合作为命令树、registry、generated reports 和边界行为的参考源。当前实际取舍是：沙盒负责快速确定性执行，vanilla/mcdoc 数据负责校验和测试参考。
