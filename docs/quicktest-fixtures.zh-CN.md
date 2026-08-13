# QuickTest Fixture 参考

## 适用场景

被测函数依赖玩家、方块、实体、score、storage、世界时间、team、bossbar、registry 或 Java 存档片段时，在行为前配置 fixture。它显式、确定地表达这些依赖，无需启动原版世界。

## 前置条件

依赖 `testkit` 并创建 `SandboxQuickTest` scenario。Fixture call 会立刻修改当前 sandbox，不是延迟配置；应放在消费它们的 `load()`、`function()`、command、tick 或 event 之前。

## 最小可运行示例

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

SandboxQuickTest.singleFunctionText(source, version = "26.2")
    .world {
        player("Alex", x = 0.0, y = 64.0, z = 0.0, xp = 3)
        block(0, 63, 0, "minecraft:stone")
        score("Alex", "runs", 0)
        storage("demo:state", "{ready:true}")
    }
    .function()
    .assertScore("Alex", "runs", 1)
    .requirePassed()
```

在 `sandbox:main` 运行前，player、block、objective/score、storage 已存在。

## Fixture 生命周期

推荐链路：

```text
create scenario → apply world/setup/import → run behavior → assert → report
```

多次 `.world { ... }` 按调用顺序应用；后面的操作可以替换同一 block、entity key、score、storage value 或 world property。行为也会继续修改 fixture state，因此不要跨测试或线程共享一个可变 scenario。

## 世界级状态

`SandboxWorldSetup` 可设置：

| 范围 | 示例 |
| --- | --- |
| 时钟 | game time、day time |
| 身份/策略 | seed、difficulty、default game mode |
| 环境 | weather/duration、world spawn、world border |
| Tick 状态 | tick rate 与 freeze 相关模型状态 |
| 空间索引 | forced chunk、biome override |
| 数据系统 | gamerule、random sequence、score、storage |

稀疏模型中只有显式设置的值存在。在一个坐标设置 biome 或 forced chunk 不会生成地形或启动原版 chunk ticking。

## Block、region 与 structure

### 单个 block

用 `block(x, y, z, id, ...)` 设置精确状态。可选 block property/NBT 表示建模的 block state/block-entity data，并在支持处按当前 profile 校验。

```kotlin
.world {
    block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
    block(1, 64, 0, "minecraft:red_wool")
}
```

### Region

Region helper 用一个状态填充显式 cuboid。它比重复数百次调用清楚，但边界仍应保持小，避免掩盖测试意图并扩大 snapshot。

### Structure

Structure fixture 可放置可复用的显式 structure block/entity 集合。Placement 是确定性的，不调用原版 structure-placement engine、processor、terrain adaptation 或 worldgen。若测试建模的 `place structure` 命令本身，要区分 fixture setup 与该命令的 output/payload。

## Entity 与 player

Entity fixture 可设置 type、UUID/name/tag identity、position/dimension、health、NBT、equipment、effect、attribute 与支持的特殊实体状态。Player fixture 还建模 game mode、XP/level、food、selected slot、inventory/ender item、effect、recipe、stat、spawn 和支持的 last input。

```kotlin
.world {
    player("Steve", x = 0.0, y = 65.0, z = 0.0, xp = 5)
    entity("minecraft:pig", x = 1.0, y = 64.0, z = 0.0, tags = listOf("fixture"))
}
```

普通 command/player event 可使用创建的玩家，selector 也按 fixture state 解析。Entity 不会因为创建就获得原版 AI/physics。

## Inventory、equipment、effect、attribute

Player inventory 与 entity equipment 可接 active profile 支持的 item id、count、slot/container、components、NBT。Effect 包括 id、duration、amplifier、visibility；attribute 包括 base/current 模型值。只创建测试所需状态，然后用 `assertItem`、`assertEntityEquipment`、`assertEntityEffect`、`assertEntityAttribute`，不要比较完整 player/entity snapshot。

## Scoreboard、storage、team、bossbar

- `score(target, objective, value, criteria)` 确保 objective/score 存在。
- `storage(id, snbt/json)` 预置 command-visible storage。
- Team fixture 创建 member 与 display/color 等 modeled option。
- Bossbar fixture 创建 id、name、value/max、color/style、visibility、player membership。
- 契约涉及 objective metadata/display slot 时使用对应 scoreboard helper。

在 load/function 测试前直接 fixture 这些状态，可避免 setup command 干扰被测函数的 coverage 与 trace。

## 复用 setup object

创建并配置 `SandboxWorldSetup`，再用 `.setupWorld(setup)` 应用到相同确定环境的 scenario。Setup 是普通可变配置；共享前先完成构建，测试应用期间不要并发修改。

若 CLI 与 JVM 测试要共用文件，把 setup 写成 Manifest-style fixture JSON。JSON `world` 覆盖同类概念，并支持 `extends`、`fixture`、`fixtures` 分层。

## 导入 Java 存档片段

显式指定 chunk 与 dimension：

```kotlin
test.importSave(
    path = Path.of("fixtures/world"),
    chunks = listOf(ChunkPos(0, 0), ChunkPos(1, 0)),
    dimension = "minecraft:overworld",
    includeBlocks = true,
    includeBlockEntities = true,
    includeEntities = false,
)
```

Importer 只读请求的 Java Anvil data，并将选择的模型内容复制到稀疏世界；它不会启动该存档、像原版服务端一样跑 datafixer、生成缺失 chunk，或隐式导入 player/network state。测试数据只保留小而经过审查的 chunk selection；大 world directory 是慢且不透明的 fixture。

## Matrix fixture

`SandboxQuickTestMatrix` 在各版本 scenario 上镜像主要 behavior/fixture/assertion surface。Matrix fixture 会应用到每个 profile 的隔离 sandbox。资源内容/布局不同时使用按版本的 pack list；除非测试有意 profile-specific，不要放某个 profile 无效的 fixture value。

## Manifest 等价写法

```json
{
  "world": {
    "time": 100,
    "players": [{ "name": "Alex", "position": [0, 64, 0], "xp": 3 }],
    "blocks": [{ "pos": [0, 63, 0], "id": "minecraft:stone" }],
    "scores": [{ "target": "Alex", "objective": "runs", "value": 0 }],
    "storage": { "demo:state": { "ready": true } }
  }
}
```

同一 setup 需要被 CLI `check`、`run --world`、REPL fixture loading 或 Serve `applyWorldFixture` 读取时使用此表示。

## 诊断 fixture 问题

| 现象 | 检查项 |
| --- | --- |
| Selector 匹配不到 | Player/entity name、tag、type、dimension、position |
| Score command 失败 | Objective 是否创建，target 拼写是否一致 |
| Storage path 缺失 | Seeded JSON/SNBT shape 与 namespaced storage id |
| Block NBT 被拒绝 | Block id、active version、schema-valid 顶层字段 |
| Save import 为空 | Dimension、chunk 坐标、inclusion flag、Anvil file |
| Function 看到旧状态 | Fixture 是否在行为前应用，是否被 reset 替换 |

调试时检查 `test.sandbox.snapshotJson()` 或 targeted world access；最终把 observation 转成 assertion，不要永久留下 ad-hoc inspection。

## 限制

- 世界是稀疏的；未设置 block、biome、entity、chunk 不代表原版生成状态。
- NBT/components 与 profile 有关，只有建模字段/操作有意义。
- Save import 是显式片段，不是完整世界迁移。
- Fixture helper 创建状态，不模拟通常产生该状态的 gameplay sequence；该序列属于被测行为时使用 step/event。

## 相关页面

- [世界模型](/runtime/world-model)
- [QuickTest 总览](/guide/code-test-api)
- [QuickTest 断言](/reference/quicktest-assertions)
- [Manifest 参考](/reference/manifest)
