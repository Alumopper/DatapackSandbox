# Datapack Sandbox 开发路线图

本文档描述 Datapack Sandbox 接下来的完整开发路线。目标不是嵌入或复刻 Mojang 原版服务端，而是在洁净室、确定性、可测试的前提下，尽可能完整地模拟数据包可见的资源、命令、输入、输出和状态变化，让它适用于单元调试、随手小测试、命令生成器产物验证、CI 回归和多版本兼容检查。

当前项目已经具备 `core` 运行时、`cli` 工具、REPL、`.dps.json` 清单、quick-test API、输出事件、世界 fixture、部分命令实现、loot/predicate/advancement/player event 支持，以及 Minecraft Java `1.20.4` 到 `26.2` 的版本 profile。后续路线应优先扩展这些已有接口，而不是另起一套并行系统。

每轮完成后，进行一次提交。

根据功能的进展，更新版本号。

## 总体目标

- 尽可能覆盖数据包资源：函数、标签、战利品表、谓词、进度、配方、物品修饰器、结构、维度、世界生成 JSON、damage type、enchantment、chat type、trim 等资源都应能加载、校验、索引和调试。
- 尽可能覆盖数据包命令：优先实现会影响数据包可见状态、输出事件、断言结果和生成器测试的命令语义；对暂不模拟的原版副作用给出结构化诊断。
- 支持多种测试入口：完整数据包、zip 包、单个 `.mcfunction`、内联函数文本、多函数轻量包、命令文本、命令文件、清单批量测试和代码级 quick-test。
- 强化输入与输出 debug：输入事件、玩家事件、命令输出、warning、trace、snapshot、diff、失败解释都应可被 CLI、REPL、manifest 和 API 使用。
- 保持确定性：随机、tick、loot、调度、选择器排序、snapshot 输出和诊断文本都要稳定，便于 CI 比较和生成器回归。
- 明确非目标：不模拟网络连接、真实客户端 UI、完整权限系统、真实区块生成、完整红石、实体 AI、真实战斗物理、服务端线程模型和未经显式导入的世界内容。

## 阶段 1：资源覆盖与资源索引

目标：让沙盒能理解更多数据包资源，并能解释“加载了什么、为什么没加载、版本是否匹配”。

主要任务：

- 扩展 `DatapackLoader` 的资源读取范围，按版本 profile 支持当前目录名和历史别名。
- 为新增资源建立 typed model 或至少建立稳定的 raw JSON resource model，包含 resource id、文件路径、版本、资源类型和原始 JSON。
- 增加资源索引能力，按 namespace、类型、id、来源 pack 和覆盖顺序查询；`resources --pack <path>` 已可导出实际加载索引，支持 type/id/namespace/source-pack/order/active/overridden 过滤和 JSON artifact。
- 扩展 pack overlay 诊断：当后加载 pack 覆盖前一个资源时，可在 verbose/trace 中显示覆盖关系；`run --report-file` 和 `check --report-file` 已把资源覆盖条目与直接缺失引用写入结构化 artifact，便于 CI 和生成器测试读取。
- 增加资源格式校验：JSON 解析、必填字段、resource location、版本目录布局、`pack.mcmeta` 格式范围、标签 `replace` 语义；function tag 和普通 tag 已对 `replace`、`required`、`values`、`id` 类型和 resource location 做带文件/版本的诊断，typed/raw JSON 资源 id 从目录和文件名推导失败时也会报告资源类型、id、文件和版本。
- 扩展 `inspect registry/resource` 或新增等价 CLI/REPL 命令，列出资源和来源。
  - P0 资源矩阵测试已覆盖 current directory 与 legacy alias zip 两种布局，并确认 function、function tag、普通 tag、loot table、predicate、advancement、recipe 和 item modifier 都进入资源索引；function tag 现在以 `tag/function` 记录到 `resources --pack`/report 使用的同一套 resource index。

优先资源：

- P0：function tags、普通 tags、loot table、predicate、advancement、recipe、item modifier。
- P1：damage type、chat type、dimension、dimension_type、worldgen configured/placed feature、structure、processor list。
- P2：enchantment、jukebox song、trim material/pattern、banner pattern、wolf variant、painting variant 等版本相关注册表资源。

验收标准：

- 每种 P0 资源有 loader 测试、路径映射测试、zip/目录双形态测试和版本别名测试；`DatapackResourceIndexTest` 已用 P0 资源矩阵覆盖 current directory 与 legacy alias zip 布局。
- 加载失败包含文件、resource id、版本、资源类型和具体原因；P0 JSON 资源解析失败矩阵已覆盖 loot table、predicate、advancement、recipe 和 item modifier，advancement 语义校验失败也会保留资源类型和 id。
- `check --verbose` 或 REPL `inspect resources` 能展示资源数量、重复覆盖和缺失引用；REPL 输出已复用 manifest/check 的同一套资源摘要和 missing-reference 分析。

## 阶段 2：命令执行语义扩展

目标：优先补齐数据包高频命令，让生成器产物和真实数据包逻辑能在沙盒中跑出可断言结果。

主要任务：

- 扩展 `execute`：
  - 补齐 `if/unless` 的 `blocks`、`biome`、`loaded`、`dimension`、`predicate`、`function` 等路径。
  - 补齐 `store` 的 result/success 到 score、storage、entity、block、bossbar 的边界行为。
  - `execute store success/result` 已按嵌套命令真实 success/result 写入；NBT 目标会按 byte/short/int/long/float/double 类型和 scale 转换数值，整数类型使用窄化转换语义；嵌套 `execute if/unless` 条件失败会写入 0，`function` 中 `return fail` 会作为失败传递给 `store success`。
  - 提升 `as/at/positioned/rotated/facing/anchored/in/align` 的上下文准确性。
  - 常用 selector 选项 `name`、`gamemode`、`team`、`nbt`、`predicate`、`scores`、`advancements`、`level`、`x_rotation/y_rotation`、`sort`、`distance`、`x/y/z`、`dx/dy/dz` 已接入确定性过滤和排序；`scores={...}` 支持花括号内多 objective 整数范围，`nbt={...}` 使用包含式对象匹配，`predicate=<id>` 会把候选实体作为 `this` 上下文并使用候选实体的位置/维度交给已加载数据包 predicate 引擎评估，支持 `!` 取反，`advancements={...}` 可按玩家进度匹配整体完成或 criterion 状态，等级和旋转范围可用于玩家/实体状态筛选，`sort=random` 使用稳定顺序，适合命令生成器回归和范围选择调试。
- 扩展 `data`：
  - 支持更完整的 data path，包括 list/object 匹配、append/prepend/insert、set/from/string/value；list 操作会拒绝已存在的非列表目标，避免生成器输出调试时把错误 path 静默覆盖成新数组。
  - 所有写入都经过 NBT schema 或 sandbox state 规则校验。
  - `data merge`、`data modify` 和 `data remove` 已记录结构化前后 NBT 输出，便于调试 storage/entity/block 写入、path 操作与 schema 校验后的结果。
- 扩展 `loot`：
  - 补齐 source：`mine`、`kill`、`fish`、`entity`、`block`、`equipment` 等适合测试的确定性模型；命令 source 已覆盖 `fish`、`mine`、`kill`、`entity <table> <target>`、`block <table> <pos> [tool]`、`equipment <table> <target> <slot>`，其中 block source 会把完整 sparse block 状态交给 `block_state_property` 检查 id 和 properties；loot entry 已支持 item tag 展开、嵌套 tag、optional tag value、`expand=false` 整 tag 输出和 `expand=true` 展开候选选择，并可通过 `copy_name` 从实体上下文复制名称、通过 `copy_components` 从工具复制组件、通过 deterministic enchantment function 写入附魔组件、通过 `apply_bonus` 和附魔感知的 `random_chance_with_enchanted_bonus` 按工具附魔调整数量或条件概率，也可通过 `reference` 复用 item modifier 函数链；常用 condition 已覆盖 `table_bonus`、`killed_by_player` 和带 constant/uniform/binomial/score provider 的 `value_check`，供输出断言调试。
  - 输出既能进入玩家/方块/实体，也能作为独立生成结果供 CLI 和 manifest 断言；`loot` 命令会记录结构化输出事件，`loot replace entity` 已可写入玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽。
- 扩展 `item` 和 item modifier：
  - 支持 entity/block slot 读写、modifier 应用、components/NBT 兼容差异；entity item 槽位已覆盖玩家背包、当前主手、`enderchest.*` 和非玩家实体装备；`give`、`clear` 与 `item replace ... with` 已支持 JSON/SNBT-lite NBT 和 components payload，`give`、`item replace` 与 `item modify` 会记录结构化输出便于 report/assertion 调试；`item modify` 已支持 `copy_nbt` 和 `copy_components` 从当前栈或可用实体/玩家上下文复制 NBT/组件，`copy_components` 可用 `include`/`exclude` 过滤。
- 扩展 `recipe`、`advancement`、`attribute`、`damage`、`effect`、`enchant`、`random`、`team`、`bossbar`、`worldborder` 的子命令覆盖；`recipe give/take` 已维护玩家 recipe 集合，并在结构化输出中报告 changed 数量和实际变更的 recipe id 列表，便于生成器断言 `*` 展开结果。
  - `summon` 已记录结构化创建输出，便于命令生成器、manifest assertion 和随手测试确认实体类型、位置、维度、tag 与输入 NBT。
  - `teleport`/`tp` 已记录结构化移动输出，便于调试传送前后坐标、维度和旋转变化。
  - `rotate` 已记录结构化前后旋转输出，便于调试 `execute rotated/facing`、局部坐标和传送旋转链路。
  - `ride` 已记录结构化 mount/dismount 输出，便于调试乘客、载具和实体关系变化。
  - `setblock` 已记录结构化前后方块输出，便于调试方块状态、方块实体 NBT 和 `keep`/`replace` 等模式的实际效果。
  - `fill` 已记录结构化区域输出，便于调试填充体积、实际变化位置、目标方块和填充模式。
  - `clone` 已记录结构化复制输出，便于调试源区域、目标区域、复制数量、移动源清除和实际变化位置。
  - `weather` 已记录结构化天气输出，便于调试天气状态、持续时间和 rain/thunder 派生状态。
  - `time set/add` 已记录结构化时间修改输出，便于调试 dayTime 前后变化和 `execute store` 相关查询链路。
  - `setworldspawn` 和 `spawnpoint` 已记录结构化出生点输出，便于调试世界出生点、玩家出生点、角度和目标玩家。
  - `difficulty` 和 `defaultgamemode` 已记录结构化前后输出，便于调试世界级默认状态变化。
  - `gamemode` 已记录结构化玩家模式前后输出，便于调试玩家状态变更和目标选择。
  - `forceload add/remove/remove all` 已记录结构化 chunk 修改输出，便于调试强加载区域和变化数量。
  - `fillbiome` 已记录结构化 biome 覆盖输出，便于调试区域、过滤条件、目标 biome 和实际变化位置；显式 biome 覆盖也会被 `execute if biome` 和 predicate `location_check` 的 biome 条件读取。
  - `tick rate/freeze/unfreeze/step/sprint` 已记录结构化 tick 状态和推进输出，便于调试 tick rate、冻结状态、gameTime 前后变化和命令生成器结果。
  - `worldborder set/add/center/damage/warning` 已记录结构化前后状态输出，便于调试边界尺寸、中心点、伤害参数和警告参数变化。
  - `bossbar add/remove/set` 已记录结构化前后状态输出，便于调试 UI 状态、玩家目标、字段输入和生成器输出。
  - `attribute modifier add/remove/value get` 已建模实体属性修饰器、total 计算、结构化输出和 `execute store` 结果，便于调试属性命令生成器和快照中的属性状态。
  - `advancement grant/revoke` 已展开 `from`、`through`、`until` 的 parent/child 树，并记录结构化 criterion 更新输出，便于调试进度树批量修改和 `execute store` 结果。
  - `random value/roll/reset` 已记录结构化序列状态输出，随机序列 state 会进入 snapshot，便于调试 deterministic random、reset seed 和 `execute store` 链路。
  - `gamerule <rule> <value>` 已记录结构化修改输出，便于调试规则值输入、前值和 query 链路。
  - `team add/remove/list/join/leave/empty/modify` 已记录结构化队伍状态输出，便于调试成员变化、显示名和选项输入。
  - `place feature|jigsaw|structure|template` 已作为 observed-noop 接受并记录结构化 worldgen 输出，便于命令生成器验证放置目标、位置和额外参数。
  - `transfer` 已作为 observed-noop 接受并记录 host、port、目标玩家和语法顺序，便于调试网络跳转类生成结果而不触发真实网络行为。
  - `publish` 和 `stop` 已作为 observed-noop 记录结构化 debug 输出，便于调试 LAN 发布和生命周期类生成结果而不影响宿主进程。
  - `debug`、`jfr`、`perf` 已作为 profiling observed-noop 记录 action 和参数，便于调试生成出来的 profiling 命令而不依赖宿主采样器。
  - `ban`、`ban-ip`、`banlist`、`pardon`、`pardon-ip`、`op`、`deop`、`kick`、`whitelist`、`save-all`、`save-off`、`save-on` 和 `setidletimeout` 已作为服务器管理/权限/存档生命周期 observed-noop 记录结构化 debug 输出，便于调试管理类命令生成结果而不改变宿主状态。
  - `damage` 已记录结构化生命值变化输出，并保留 `at` 位置、直接 `by` 实体和最终 `from` 实体上下文，便于 report/assertion 调试生成出来的战斗命令。
  - `kill` 已记录结构化目标输出，便于确认选择器命中、实体移除和 advancement 触发结果。
  - `enchant` 已覆盖玩家选中物品和非玩家实体主手装备的附魔组件写入，并记录结构化输出；`effect give/clear` 已记录结构化输出，便于 report/assertion 调试。
- 为不适合完整模拟的命令保留结构化 no-op 或 unsupported warning，例如 `debug`、`jfr`、`publish`、`stop`、网络和权限相关命令。

验收标准：

- `docs/command-support.zh-CN.md` 中每个命令的支持状态都和实现保持同步。
- 每个新增命令路径至少包含成功、参数错误、版本差异和 unsupported 策略测试。
- 命令失败时能返回命令文本、函数文件、行号、调用栈和版本。

## 阶段 3：世界、实体与玩家状态建模

目标：把数据包能观察或修改的状态建成稳定、可序列化、可断言的内存模型。

主要任务：

- 完善 sparse world：
  - 方块状态、方块实体 NBT、biome override、强加载 chunk、世界边界、时间、天气、难度、gamerule、spawn、deterministic random sequence；predicate `location_check` 的 block 条件已可读取显式 sparse block 的 id、block tag、state/property 和方块实体 NBT，random sequence state 已进入 snapshot，并可通过 world fixture、manifest assertion、QuickTest assertion 和 REPL `inspect random` 声明/检查。
  - 支持区域 fixture、结构 fixture、从 Java Anvil 存档按 chunk 或坐标范围导入；区域 fixture 已在 QuickTest world builder、manifest `world.regions` 和 JSON Schema 中接入，可用 `from`/`to` 闭区间批量铺设 sparse blocks，并允许单点 `blocks` 覆盖区域局部；结构 fixture 已在 QuickTest world builder、manifest `world.structures` 和 JSON Schema 中接入，可用 origin 加相对 block/entity offset 声明小型结构，展开后复用普通 block/entity snapshot 与断言；Java Anvil save import 已在 QuickTest world builder、manifest `world.save`/`world.saves` 和 JSON Schema 中接入，可按单 chunk、chunk 列表或 `from`/`to` block 范围导入 blocks、block entities 和 entities。
- 完善实体模型：
  - 类型、UUID、位置、旋转、维度、tag、score holder、attributes、attribute modifiers、effects、passengers/vehicle、equipment、health、custom NBT；`item replace/modify entity` 已覆盖非玩家实体 `weapon.*`/`armor.*` 装备槽读写、复制、snapshot 与 NBT 投影，`attribute modifier` 已进入 snapshot 与 `Attributes[].modifiers` NBT 投影，`effect give/clear` 已覆盖非玩家实体 active effects，entity predicate 的 `equipment`、`effects`、`distance` 和 `nbt` 条件也复用该模型，其中 `distance` 覆盖 `absolute`、`horizontal` 与 `x/y/z` 轴向范围；world fixture、manifest world 和 quick-test world builder 均可直接声明非玩家实体装备、active effects、attributes、dimension、health 与 passengers/vehicle，并通过 entity assertion/quick-test assertion 验证完整 NBT path。
  - 不执行 AI tick，但保留数据包可读写字段和明确的 no-AI 语义说明。
- 完善玩家模型：
  - inventory、selected slot、ender items、recipes、stats、xp points/levels、health、food、gamemode、spawn、advancement progress、last input。
  - 玩家 NBT 默认只读；可通过命令、fixture 或事件改变玩家状态；NBT 视图会投影当前非空主手 `SelectedItem`；新建玩家会使用当前 `defaultGameMode`；world fixture、manifest world 和 quick-test world builder 可声明玩家末影箱物品和 advancement progress，`item`/`loot replace entity` 可按 `selectedSlot` 读写当前主手并可读写 `enderchest.*` 槽，通过 snapshot、完整 NBT path、`assertPlayer`/manifest player assertion、`assertItem`/manifest item assertion 和 advancement assertion 检查。
- 完善 item stack：
  - 兼容旧版 NBT 和新版 components；命令 item argument 可直接输入 JSON/SNBT-lite NBT 与 components payload，并在括号 payload 内保留空格用于解析；block/entity container item stack NBT 校验已把 `count`/`Count` 和 `slot`/`Slot` 作为版本兼容别名处理。
  - 提供 matcher，支持 id、tag、count、components path、NBT path、slot、enchantment、custom data；item predicate 已支持具体 id、`#` item tag 以及 `enchantments`/`stored_enchantments` 直接匹配。

验收标准：

- `snapshotJson()` 输出稳定排序，不受 map/list 插入顺序影响。
- world fixture、manifest world、quick-test world builder 三者能力一致或差异明确记录。
- 所有新增状态都有 snapshot、assertion 和 inspect 路径。

## 阶段 4：输入事件与玩家交互模拟

目标：让沙盒可以模拟数据包常见输入来源，尤其适合 advancement、predicate、交互型数据包和命令生成器测试。

主要任务：

- 扩展 `PlayerEvent`：
  - item used、item consumed、entity interacted、entity killed、block placed、block broken、changed dimension、tick、damage、death、inventory changed。
  - keyboard/mouse input 保留 device、code、action、坐标、tick、source。
  - `entity_killed`、`block_placed`、`block_broken` 等路线图自然命名已作为事件别名接入 advancement 匹配、CLI completion、REPL/CLI/manifest 简写输入和 event trace。
  - `PlayerEventTraceExpectation`、QuickTest API 和 manifest `eventTrace` 已可按 item/entity/block/recipe、from/to dimension、damage source/amount、input device/code/action 匹配事件输入上下文。
  - player event trace 已记录未匹配 advancement criterion 的可读失败原因，并可用 `failedAdvancement`、`failedCriterion`、`failureContains` 断言定位缺失上下文或失败字段。
  - `item_consumed`、`inventory_changed`、`item_picked_up`/`item_added`、`changed_dimension`、`damage`/`death` 和 `recipe_unlocked` 已同步更新玩家可观察状态，包括背包数量、food、维度、health 和 recipe 集合，可通过 snapshot、inspect、manifest assertion 与 QuickTest assertion 检查。
  - 带 `blockPos` 的 `block_placed`/`block_broken` 玩家事件已可更新 sparse world 方块状态，并在 event trace 中暴露目标 block 坐标，便于事件驱动数据包测试直接断言方块变化。
  - CLI/REPL 玩家事件简写已支持 `x y z`、`pos=x,y,z`、`blockPos=x,y,z` 和 `@x,y,z` 四种 block 坐标输入，可用于随手小测、`--event-file` 和独立 `event` 命令。
  - `examples/player-events` 已提供玩家事件矩阵 full-stack manifest，覆盖 tick、背包变化、物品使用/消耗/拾取、键鼠输入、实体交互、伤害/死亡、击杀、location、维度切换、方块放置/破坏、recipe unlock 和 effects changed，并断言 advancement、event trace、玩家状态、物品和方块状态。
- 为事件建立 manifest step、CLI command、REPL command 和 quick-test API。
- 事件可以触发 advancement、predicate、loot、scoreboard、storage 和输出命令，也可以在不需要客户端物理的范围内更新玩家状态。
- 增加事件 trace：事件输入、匹配到的 advancement criteria、执行的 reward、失败原因。
  - advancement reward 已记录结构化输出，包含触发玩家、advancement、XP、recipe、function、loot table 和实际生成物品，便于把事件输入、criteria 命中和奖励副作用串起来调试。

验收标准：

- 每类 P0 事件至少有一个 full-stack 示例和一个 manifest 测试；`examples/player-events/player-events.dps.json` 已接入示例 manifest 回归。
- 输入事件可被 `assertPlayerLastInput`、snapshot、`inspect player` 检查。
- advancement 条件不满足时能解释缺少的上下文或失败字段。

## 阶段 5：输出、Trace 与 Debug 体验

目标：让用户能解释数据包“为什么这样输出、为什么断言失败、哪条命令改变了状态”。

主要任务：

- 扩展 `OutputEvent`：
  - 保留 tick、command、channel、targets、plain text、segments、payload、source location、function stack。
  - 输出 channel 覆盖 chat、title、sound、visual、warning、data、debug、worldgen。
- 增加命令 trace：
  - `--trace`：记录每条命令、上下文、结果、错误、输出事件。
  - `--trace-file`：写出 JSONL，适合 CI artifact。
  - `--trace-filter`：按 command、function、selector、output、score/storage 变化过滤。
  - CLI `run`/`check` 的 `--trace-filter` 已支持 `selector=`/`target=`、`success=`、`error=`/`diagnostic=`、`error-code=`/`diagnostic-code=`、`error-message=`/`diagnostic-message=`、文本型 `output=`、数量/布尔型 `outputs=`、`output-channel=` 和 `output-payload=`，并在 trace JSON 中保留每条命令产生的 `outputEvents`。
  - quick-test `TraceExpectation` 已支持按输出数量、输出文本/目标、是否产生 snapshot diff、diff path、diff kind 和 diff 渲染文本匹配，便于定位命令副作用。
  - manifest `trace` 断言已支持同样的输出数量、输出文本/目标和 snapshot diff 匹配字段，并已写入 JSON Schema，便于 `.dps.json` 回归测试直接定位命令副作用。
  - REPL/CLI 命令目录会把 `place` 标记为 `observed-noop` 并提供基础子命令补全，避免工具提示和核心执行语义不一致。
  - quick-test 与 manifest 输出断言已覆盖 `place` 的 `worldgen` channel 和 `payloadPath` 匹配，便于命令生成器回归测试验证放置目标、位置和 no-op 原因。
- 增加 snapshot diff：
  - 对比执行前后 world、score、storage、player、entity、block、outputs。
  - manifest 失败时可显示最小差异，而不是只输出最终 snapshot。
- 增强断言失败解释：
  - 输出最近相关命令、相关 state path、实际值、候选输出事件和建议检查项。
  - 输出 payload 断言失败会在候选输出中显示对应 `payloadPath` 的实际值，便于直接定位结构化输出 mismatch。
  - 输出 segment style 断言失败会在候选输出中显示已解析文本 segment 的 text/color/bold/italic/underlined/strikethrough/obfuscated，便于直接定位 tellraw/title 样式 mismatch。
  - QuickTest `assertSnapshotDiff` 失败会列出实际 snapshot diff 候选，便于在单元调试中直接看到真实状态变化路径和渲染值。
  - manifest `diagnostic` 断言失败会列出实际 diagnostic 候选，便于定位预期失败步骤、错误码、命令和消息。
  - manifest `snapshotDiff` 断言失败会列出实际 snapshot diff 候选，便于定位状态路径和 before/after 差异。
  - `run --report-file` 和 `check --report-file` 已把失败 trace 提取为 `diagnosticCount`/`diagnostics` artifact 字段，便于 CI 和命令生成器测试直接读取错误码、消息、命令、root、来源文件和行号。

验收标准：

- `check --snapshot-on-fail` 保持兼容，新增 diff/trace 不破坏旧输出。
- `run`、`check`、REPL 和 quick-test report 都能读取同一套 trace model。
- 输出断言支持 text、contains、targets、payload path、segment style、count、order。

## 阶段 6：测试入口与使用场景

目标：针对不同用户场景提供最短路径，同时共用同一套核心运行时和断言系统。

### 单元调试

- 强化 `SandboxQuickTest` fluent API：
  - `assertScore`、`assertStoragePath`、`assertPlayer`、`assertEntity`、`assertBlock`、`assertItem`、`assertOutput`、`assertTrace`。
  - 支持可复用 fixture：world setup、players、entities、blocks、storage、scoreboard、packs。
- quick-test report 已暴露 `resourceSummary`，与 `run`/`check` report 和 REPL `inspect resources` 共用 core 的资源数量、overlay、missing-reference 诊断模型。
- 增加 JUnit 辅助错误格式，失败时输出最小 snapshot diff 和 trace 摘要。

### 随手小测试

- 增强 `run`：
  - 支持 `--world` 传入小型 JSON fixture。
  - 支持 `--assert` 传入一两个简单断言。
  - 支持 `--stdin` 从标准输入读取函数或命令。
  - `--allow-command-failure` 已可让直接命令输入在预期失败后继续执行，配合 diagnostic/trace/output 断言检查错误码、错误消息和后续状态。
  - `--assert`/`--assert-file` 已支持 score、storage、advancement、player、world、gamerule、random sequence、snapshot、block、biome、team、bossbar、item、entity、diff、event-trace、trace、trace-output、diagnostic、warning、unsupported、output、output-count、output-order、output-exact、output-matches、output-command、output-channel、output-target、output-normalized、output-normalized-exact、output-normalized-matches、output-segment、output-segment-exact、output-segment-matches 和 output-payload 简写；`world:<field>=<value>` 可直接检查时间、天气、难度、seed 和默认游戏模式，`gamerule:<rule>=<value>`、`gamerule:<rule>?`、`gamerule:<rule>!` 可直接检查 gamerule snapshot 状态，`snapshot:<path>=<json>`、`snapshot:<path>?`、`snapshot:<path>!` 可直接检查最终 snapshot 路径，`block:<x>,<y>,<z>=<id>`、`block:<x>,<y>,<z>?`、`block:<x>,<y>,<z>!` 可直接检查 sparse world 方块，`biome:<x>,<y>,<z>=<id>` 可直接检查显式 biome 覆盖，`team:<name>?`、`team:<name>@<member>`、`team:<name>=N` 和 `bossbar:<id>:<field>=<value>` 可直接检查队伍/UI 状态，`event-trace:<player>:<type>@x,y,z[=N]` 可直接按 block event 坐标过滤，`diagnostic:<code>:<text>[=N]` 可直接检查预期 diagnostic 编码和消息片段，`output-command:<command>=N`、`output-channel:<channel>=N`、`output-target:<target>?` 这类简写可直接按命令、channel 或目标检查输出数量、存在或缺失，`output-count` 和 `output-order` 可直接检查匹配输出数量与全局输出顺序，`output-exact`、`output-matches`、`output-normalized-*` 和 `output-segment-*` 可覆盖精确、contains、normalized 与正则文本匹配，`output-payload` 支持 path 存在性和等值检查，`examples/generator-output` 已覆盖结构化输出 payload 断言，适合命令生成器结果的快速回归。
- 增强 REPL：
  - `inspect` 输出结构更稳定；`inspect event-traces` 已可直接打印玩家事件 trace JSON，并已接入 REPL/CLI 补全和命令目录，便于调试事件输入、block 坐标和 advancement 匹配。
  - `inspect resources` 已输出资源摘要、overlay 和 missing-reference，并保留按类型列出 resource index 条目的能力，便于随手小测时解释数据包实际加载结果。
  - 支持 `trace on/off`、`diff last`、`rerun last`、`reset world`、`load fixture`。

### 命令生成器产物测试

- 提供专用模板：
  - 输入：生成器输出的 command、command file、mcfunction text 或临时 pack；`examples/generator-template` 已提供可直接复制的严格模式模板，覆盖 `commands` 数组、内联 `functionText`、外部 `.mcfunction` 和依赖 pack。
  - 环境：声明依赖 pack、版本、world fixture、seed、默认玩家；模板使用可复用 `world.fixture.json` 并在 manifest 中做局部 storage 覆盖。
  - 断言：输出文本、score/storage、NBT、实体数量、unsupported warning 数量；模板覆盖 score、storage path、item、entityCount、block、world、trace 和结构化 output payload/segment style 断言。
- 提供严格模式：
  - unknown command、unsupported command、schema mismatch、资源缺失都可作为失败。
  - `run --strict`/`check --strict` 已把 unsupported command 设为 error，并自动启用直接缺失资源引用失败；`check --strict` 还会先做 manifest schema 校验，适合命令生成器产物的快速验收。
  - 输出规范化，避免生成器因为空白、斜杠、换行差异导致误判。

验收标准：

- README/README.zh-CN 中已为 JVM 单元调试、随手小测试、命令生成器输出、full-stack、player-events、single-function、generator-output 和 multi-version 示例提供最短入口。
- `examples/` 至少包含 full-stack、player-events、single-function、generator-output、generator-template、multi-version 六类示例。
- CLI 和 quick-test 对同一清单行为输出一致结果；quick-test report 已可读取同一套 core 资源摘要，便于代码级小测复用 CLI/report 的资源诊断。

## 阶段 7：Manifest 格式演进

目标：让 `.dps.json` 成为稳定的回归测试格式，可以覆盖输入、执行、断言、trace 和多版本矩阵。

主要任务：

- 增加 manifest schema 文档和 JSON Schema。
- 扩展 `world`：
  - 支持 fixture 引用、模板继承、局部覆盖、save import 范围、多个玩家和多个维度；`world.save`/`world.saves` 已通过 schema 和 manifest 回归测试覆盖 `from`/`to` 范围导入。
- 扩展 `steps`：
  - 支持 `commands` 数组、`functionText`、`mcfunction`、`event`、`trace`、`snapshot`、`reset`。
- 扩展 `assertions`：
  - score、storage、player、entity、block、item、loot、predicate、advancement、output、trace、diagnostic、snapshot diff。
  - 支持 equals、contains、exists、missing、count、min/max、matches、path；output assertion 已支持 plain/normalized text 的正则 `matches`，segment assertion 也可按原始或 normalized segment text 做正则匹配；storage 以及 player/entity/block/item 的 NBT/components path expectation 已支持 `contains`、`matches` 和 `missing`。
- 支持 manifest include：
  - 公共世界 fixture、公共断言、公共 pack matrix，减少重复；`include` 已按来源文件相对路径合并 world、steps、assertions，并会把公共/default packs 排在 case-local packs 之前，便于生成器批量用例复用依赖包和版本矩阵。

验收标准：

- manifest 新增字段向后兼容。
- JSON Schema 能被编辑器使用，并在 CLI 中可选校验。
- manifest 失败消息包含 assertion index、path、expected、actual。
  - 断言失败前缀已细化到断言 kind 的 JSON Pointer，例如 `/assertions/0/output`，便于从编辑器或 CI 日志直接定位。

## 阶段 8：版本 Profile 与原版资料更新流程

目标：保持多版本兼容，降低新增 Minecraft 版本时的维护成本。

主要任务：

- 固化版本 profile 更新流程：
  - pack format、data version、资源目录、命令根、注册表默认值、NBT schema。
  - 通过公开资料和 `vanilla-mcdoc` 生成 schema，不分发 Mojang 服务端代码。
- 增加版本 profile 差异报告：
  - 哪些资源目录变化、命令根变化、NBT 字段变化、注册表项变化。
  - `version --docs/--json --output <file>` 已可把 Markdown 表格、profile 元数据和差异报告写入 UTF-8 文件，便于文档更新、CI artifact 和本地脚本复核。
  - `version --docs --check <file>` 和 `version --docs --locale zh-CN --check <file>` 已可在 CI 中校验英文/中文文档是否包含当前生成表格，且英文/中文 standalone jar smoke task 已接入 Gradle `check`，防止 profile 文档过期。
- 增加多版本测试矩阵：
  - 同一行为在 `1.20.4`、中间版本和默认最新版本运行。
  - 对 pack format 不同的示例使用 per-version pack。

验收标准：

- 新增版本只需改 profile 数据和生成资源，核心逻辑尽量无需修改。
- `docs/version-profile.zh-CN.md` 可通过 `version --docs --locale zh-CN --output` 半自动更新，英文和中文 profile 表可通过 `version --docs --check` / `version --docs --locale zh-CN --check` 防漂移，表格包含 NBT schema 选择摘要。
- 版本不兼容错误清晰说明当前 pack format 和期望 format。

## 阶段 9：差分验证与可信度提升

目标：用可控方式提高沙盒行为和原版的接近度，同时避免引入原版服务端依赖。

主要任务：

- 建立 golden case：
  - 对常见命令、资源、事件和输出保存稳定 expected snapshot。
  - manifest `snapshot` 断言已支持把最终 snapshot 根对象或选定 path 与内联 JSON 或仓库中的 golden JSON 文件比较，`examples/golden-snapshot` 已覆盖该回归模式。
  - 对 bugfix 增加回归用例。
- 建立可选的外部差分流程：
  - 用户本地提供原版服务端或第三方测试环境时，生成同一输入脚本并比较可观察结果；`diff --script --output <file> <manifest.dps.json>` 已可从 manifest/include 合并后的步骤导出可重放命令脚本，并把 event、fixture、trace、snapshot、reset 等沙盒专用步骤作为注释保留给外部 harness 对齐。
  - `diff` CLI 已可比较两份确定性 JSON snapshot/report，支持从 report 抽取 `snapshot`、输出字段级 JSON Pointer 差异、写 JSON artifact，并用 `--check` 作为外部差分 CI gate。
  - 该流程不作为核心构建依赖，不提交 Mojang 代码或产物。
- 建立行为等级：
  - `exact`：与原版可观察行为高度一致。
  - `modeled`：沙盒内确定性模型，覆盖数据包测试所需行为。
  - `observed-noop`：接受命令并记录输出/诊断，不改变真实副作用。
  - `unsupported`：按策略 warn/error/ignore。
  - `commands` CLI 已可按版本导出命令目录、行为等级和描述，支持 plain、Markdown、JSON、`--output <file>` artifact 输出和 `--check <file>` 文档覆盖检查；standalone jar smoke task 已接入 Gradle `check`，便于文档生成和 CI 复核。
  - `ResourceCatalog` 已集中维护资源类型与行为等级，`DatapackLoader` 的额外 raw JSON 资源读取复用该目录，`resources` CLI 已可导出 plain、Markdown、JSON、`--output <file>` artifact，以及英文/中文 `--check <file>` 文档覆盖检查；同一命令带 `--pack` 时可导出已加载资源索引、来源文件、覆盖状态、加载顺序和按 type/id/namespace/source-pack/order/state 过滤后的 JSON artifact；英文/中文 standalone jar smoke task 已接入 Gradle `check`，避免 loader、文档和后续检查工具分叉。

验收标准：

- 每个命令和资源文档标注行为等级。
- 差分报告能指出字段级差异，而不是只给通过/失败。
- 任何 intentionally different 行为都在文档中说明原因。

## 阶段 10：性能、稳定性与发布质量

目标：让沙盒可以承受较大的数据包、批量清单和 CI 使用。

主要任务：

- 性能基准：
  - 大型 pack 加载、深函数调用、大量 scoreboard、巨大 storage、批量 manifest、loot 大量抽样。
  - `benchmark` CLI 已提供内置 smoke/CI 性能基准，覆盖 scoreboard 批量写入、大 storage merge、函数调用链、批量 manifest 执行、可选 pack 加载和可选 loot sampling，并可写 JSON artifact。
- 缓存：
  - 资源解析缓存、schema 缓存、版本 profile 缓存；保证不破坏 watch/reload。
  - `DatapackLoader` 已提供目录/zip 数据包解析缓存，缓存键使用版本 profile 与内容指纹；命中时返回深拷贝，并提供 `clearCache()` 供 REPL/watch 强制 reload 丢弃缓存。
- 错误边界：
  - 函数递归深度、最大命令数、最大 tick 数、最大输出事件数、最大 snapshot 大小。
  - `SandboxLimits` 已提供可配置的函数递归深度、sandbox 实例累计命令数、单次 `runTicks` 最大 tick 数、保留输出事件数和渲染后 snapshot 大小边界，用于阻止 runaway 单元测试和 CI 任务。
  - CLI `run` 和 `check` 已暴露 `--max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events` 和 `--max-snapshot-bytes`，可在随手小测、命令生成器验证和批量 manifest CI 中直接收紧执行边界。
- 发布质量：
  - fat jar smoke test、Windows/Linux/macOS 命令测试、README 示例测试。
  - standalone jar smoke 已覆盖 schema 导出、示例 manifest、资源索引、diff、benchmark、README 示例、run 断言简写、执行边界和预期失败命令的 diagnostic 断言。
  - Maven 发布准备：坐标、版本号、源码包、文档包。

验收标准：

- CI 至少运行 unit、manifest、examples、fat jar smoke 四类测试。
- 大型测试失败时不会无限执行或输出不可控日志。
- 发布前所有文档示例命令都能运行。

## 优先级建议

P0 必须优先完成：

- 资源索引和 P0 资源加载。
- `execute`、`data`、`loot`、`item` 的高频路径。
- trace、snapshot diff、结构化失败解释。
- manifest schema 和断言扩展。
- quick-test API 与 CLI 使用场景补齐。

P1 紧随其后：

- 更多资源类型和版本差异。
- 玩家事件扩展。
- 存档导入增强。
- 命令生成器测试模板。
- 多版本 profile 更新流程。

P2 按需求推进：

- worldgen/structure 更深入模拟。
- 可选外部差分验证。
- 高级性能缓存。
- Maven 发布和更完整的平台测试。

## 设计约束

- 所有新能力必须通过 `core` 暴露稳定模型，再由 CLI、REPL、manifest 和 quick-test 复用。
- 所有输出和 snapshot 必须确定性排序。
- 所有 unsupported/no-op 行为必须可配置为 warn、error 或 ignore。
- 新增 public API 应尽量保持 Kotlin/Java 友好，不要求用户依赖 CLI 才能测试。
- 新增格式必须向后兼容现有 `.dps.json`。
- 不应为了模拟原版而引入网络服务端、Mojang 服务端 jar 或不可分发代码。

## 推荐里程碑

1. `0.2`：资源索引、manifest schema、trace 基础、更多输出断言。
2. `0.3`：`execute/data/loot/item` 高频路径补齐，命令生成器测试模板可用。
3. `0.4`：玩家事件和 world fixture 大幅增强，examples 覆盖主要使用场景。
4. `0.5`：多版本 profile 更新流程稳定，P0/P1 资源覆盖完成。
5. `1.0`：核心 API 稳定、CLI 行为稳定、文档示例可验证、CI 覆盖完整，适合作为数据包本地回归测试工具长期使用。
