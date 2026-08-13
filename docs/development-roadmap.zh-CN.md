# 开发路线图

## 适用场景

贡献者需要了解已完成能力、后续优先级、验收标准和洁净室边界时，使用本页；普通数据包作者通常无需阅读。

## 前置条件

先阅读面向用户的命令、资源和版本参考；准备贡献代码时还应使用 JDK 25 和仓库 Gradle wrapper。

## 最小可运行示例

在 PowerShell 中运行 `.\gradlew.bat releaseCheck`，跑一遍路线图要求的模块、文档、schema、API、示例和发布制品门禁。

## 完整能力

本文记录 Datapack Sandbox 的开发计划与当前进度。项目定位是：不嵌入、不复刻 Mojang 原版服务端，而是在洁净室、确定性、可测试的前提下，尽量完整地模拟数据包能看到的资源、命令、输入、输出和状态变化。它的服务对象是单元调试、随手小测、命令生成器产物验证、CI 回归和多版本兼容检查。

当前已完成 1.0，发布版本 `1.1.0`。`core` 运行时、`cli` 工具、REPL、`.dps.json` 清单、quick-test API、输出事件、世界 fixture、常用命令模型、loot/predicate/advancement/player event，以及 Minecraft Java `1.20.4` 到 `26.2` 的版本 profile 都已就绪。本文档同时充当 1.0 的完成状态记录：后续开发应在这些稳定接口上增量演进，而不是另起一套并行系统。

开发节奏：按阶段推进，每完成一个可验证的闭环提交一次，并随功能进展更新版本号。

## 总体目标

- **资源覆盖**：函数、标签、战利品表、谓词、进度、配方、物品修饰器、结构、维度、世界生成 JSON、damage type、enchantment、chat type、trim 等资源都能加载、校验、索引和调试。
- **命令覆盖**：优先实现影响数据包可见状态、输出事件、断言结果和生成器测试的命令语义；对暂不模拟的原版副作用给出结构化诊断。
- **多测试入口**：完整数据包、zip 包、单个 `.mcfunction`、内联函数文本、多函数轻量包、命令文本、命令文件、清单批量测试和代码级 quick-test。
- **输入输出 debug 能力**：输入事件、玩家事件、命令输出、warning、trace、snapshot、diff、失败解释，都要能被 CLI、REPL、manifest 和 API 使用。
- **确定性**：随机、tick、loot、调度、选择器排序、snapshot 输出和诊断文本保持稳定，便于 CI 比较和生成器回归。
- **明确非目标**：不模拟网络连接、真实客户端 UI、完整权限系统、真实区块生成、完整红石、实体 AI、真实战斗物理、服务端线程模型，以及未经显式导入的世界内容。

## 阶段 1：资源覆盖与资源索引

这一阶段的目标是让沙盒理解更多数据包资源，并回答"加载了什么、为什么没加载、版本是否匹配"。

主要任务：

- **扩展 `DatapackLoader` 的读取范围**：按版本 profile 支持当前目录名和历史别名；新增资源建立 typed model，或至少建立稳定的 raw JSON model（包含 resource id、文件路径、版本、资源类型和原始 JSON）。
- **资源索引**：按 namespace、类型、id、来源 pack 和覆盖顺序查询。`resources --pack <path>` 已可导出实际加载索引，支持 type/id/namespace/source-pack/order/active/overridden 过滤和 JSON artifact。
- **Pack overlay 诊断**：后加载 pack 覆盖前一个资源时，在 verbose/trace 中显示覆盖关系。`datapack list [available|enabled]`、`run --report-file` 和 `check --report-file` 已把已加载 pack、资源覆盖条目与直接缺失引用写入结构化 artifact/payload。
- **资源格式校验**：JSON 解析、必填字段、resource location、版本目录布局、`pack.mcmeta` 格式范围、标签 `replace` 语义。function tag 和普通 tag 已对 `replace`、`required`、`values`、`id` 类型和 resource location 做带文件/版本的诊断；typed/raw 资源 id 从目录和文件名推导失败时，会报告资源类型、id、文件和版本。
- **检查入口**：REPL `inspect registry [group]` 和 CLI `resources --registry --registry-group <group>` 已可列出/导出当前 profile 的 registry group、条目和来源；`inspect resources [type]` 列出资源索引中的来源 pack、文件、active/overridden 和 overlay 关系。P0 资源矩阵测试覆盖 current directory 与 legacy alias zip 两种布局，function、function tag、普通 tag、loot table、predicate、advancement、recipe、item modifier 都已进入资源索引，function tag 以 `tag/function` 记录到与 `resources --pack`/report 同一套 resource index。

优先资源与现状：

- **P0**：function tags、普通 tags、loot table、predicate、advancement、recipe、item modifier——已完成加载、索引与回归。
- **P1**：damage type、chat type、dimension/dimension_type、worldgen configured/placed feature、structure、processor list——已从 raw inspect 接入对应命令的调试输出：
  - `damage` 命中已加载 `damage_type` 时暴露 `message_id`、`scaling`、`exhaustion`、`effects`、`death_message_type`、资源文件和版本；
  - `say`、`me`、`msg`/`tell`/`w`、`teammsg`/`tm` 命中已加载 `chat_type` 时暴露 `chat`/`narration` 装饰 JSON、资源文件和版本；
  - `summon`、`kill`、`loot spawn`、`teleport`/`tp`、`setworldspawn`、`spawnpoint` 命中已加载维度资源时暴露 dimension JSON、关联 dimension type JSON、资源文件和版本。
- **P2 版本相关注册表资源**：
  - `enchantment` 已接入 `enchant` 命令，输出完整 enchantment definition 与物品组件写入结果；
  - `cat_variant`、`chicken_variant`、`cow_variant`、`frog_variant`、`painting_variant`、`pig_variant`、`wolf_variant`、`wolf_sound_variant` 已接入 `summon` 的实体 `variant`/`sound_variant` 输出；
  - `trim_material`、`trim_pattern` 已接入 `give`、`item replace`、`item modify`、`loot`、`enchant` 等结构化 item 输出的 `minecraft:trim` 组件；
  - `equipment_asset`、`banner_pattern`、`instrument`、`jukebox_song` 已接入 item 输出的 `equippable`、`banner_patterns`、`instrument`、`jukebox_playable` 组件。
  - 额外的 raw JSON 资源矩阵测试直接复用 `ResourceCatalog.additionalRawJsonTypes`，保证 catalog 里列出的 P1/P2 资源都被目录/zip loader、raw resource map 和资源索引覆盖，而不是只测手写子集。

验收标准：

- 每种 P0 资源有 loader 测试、路径映射测试、zip/目录双形态测试和版本别名测试；`DatapackResourceIndexTest` 已用 P0 资源矩阵覆盖 current directory 与 legacy alias zip 布局。
- 加载失败包含文件、resource id、版本、资源类型和具体原因；P0 JSON 解析失败矩阵已覆盖 loot table、predicate、advancement、recipe、item modifier，advancement 语义校验失败也保留资源类型和 id。
- `check --verbose` 或 REPL `inspect resources` 能展示资源数量、重复覆盖和缺失引用；REPL 输出与 manifest/check 共用同一套资源摘要和 missing-reference 分析。

## 阶段 2：命令执行语义扩展

这一阶段优先补齐数据包高频命令，让生成器产物和真实数据包逻辑能在沙盒里跑出可断言的结果。

- **`execute`**：`if/unless` 覆盖 `blocks`、`biome`、`loaded`、`dimension`、`predicate`、`function` 等路径；`store` 支持 result/success 写入 score、storage、entity、block、bossbar，NBT 目标按 byte/short/int/long/float/double 类型和 scale 转换数值、整数类型使用窄化转换，嵌套 `if/unless` 条件失败写入 `0`，`function` 中 `return fail` 会作为失败传给 `store success`；`as` 只切换执行者，`at` 会把位置、维度和旋转移到目标实体，`positioned as` 只移动执行位置，`align` 对校验过的 `x`/`y`/`z` 轴取整，`rotated`/`facing` 更新旋转上下文供 `tp` 相对旋转和局部坐标使用，`anchored` 更新局部坐标基准点，`on target`/`on attacker` 解析 interaction 实体最后记录的右击/攻击玩家。常用 selector 选项（`name`、`gamemode`、`team`、`nbt`、`predicate`、`scores`、`advancements`、`level`、`x_rotation`/`y_rotation`、`sort`、`distance`、`x/y/z`、`dx/dy/dz`）已接入确定性过滤和排序：`scores={...}` 支持花括号内多 objective 整数范围，`nbt={...}` 使用包含式对象匹配，`predicate=<id>` 把候选实体作为 `this` 上下文交给已加载 predicate 引擎评估并支持 `!` 取反，`advancements={...}` 可按整体完成或 criterion 状态匹配，`sort=random` 使用稳定顺序。
- **`data`**：path 覆盖 list/object 匹配、append/prepend/insert、set/from/string/value；list 操作拒绝已存在的非列表目标，避免把错误 path 静默覆盖成新数组。所有写入都经过 NBT schema 或 sandbox state 规则校验；`merge`、`modify`、`remove` 记录结构化前后 NBT 输出。
- **`loot`**：source 覆盖 `mine`、`kill`、`fish`、`entity <table> <target>`、`block <table> <pos> [tool]`、`equipment <table> <target> <slot>`，block source 会把完整 sparse block 状态交给 `block_state_property` 检查 id 和 properties。loot entry 支持 item tag 展开（含嵌套 tag、optional 值、`expand=false` 整 tag 输出和 `expand=true` 展开候选），`copy_name` 从实体上下文复制名称，`copy_components` 从工具复制组件，deterministic enchantment function 写入附魔组件，`apply_bonus` 与附魔感知的 `random_chance_with_enchanted_bonus` 按工具附魔调整数量或概率，`reference` 复用 item modifier 函数链；常用 condition 覆盖 `table_bonus`、`killed_by_player`、带 constant/uniform/binomial/score provider 的 `value_check`。输出既可以进入玩家/方块/实体，也可以作为独立生成结果供 CLI 和 manifest 断言；`loot replace entity` 可写入玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽。
- **`item` 与 item modifier**：entity/block 槽位覆盖玩家背包、当前主手、`enderchest.*`、非玩家实体装备（含盔甲架）和 item display 的 `inventory.0`；`give`、`clear`、`item replace ... with` 支持 JSON/SNBT-lite NBT 与 components payload；`item modify` 应用常用 modifier 函数（`set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`copy_nbt`、`copy_components`（含 `include`/`exclude`）、`filtered`、`reference`、`sequence`）。container item-stack NBT 校验把 `count`/`Count` 与 `slot`/`Slot` 作为版本兼容别名处理。
- **其余命令**（均已记录结构化前后输出，供断言和 `execute store result` 使用）：
  - 世界状态：`weather`、`time set/add`、`difficulty`、`defaultgamemode`、`gamerule`、`worldborder set/add/center/damage/warning`、`tick rate/freeze/unfreeze/step/sprint`、`bossbar add/remove/set`、`random value/roll/reset`（序列状态进入 snapshot）；
  - 方块与世界修改：`setblock`（含方块实体 NBT 和 `keep`/`replace`）、`fill`、`clone`、`fillbiome`（显式覆盖可被 `execute if biome` 和 `location_check` 读取）、`forceload add/remove/remove all`、`setworldspawn`、`spawnpoint`；
  - 实体：`summon`（含 variant 元数据）、`teleport`/`tp`、`rotate`、`ride`、`kill`、`damage`（保留 `at`/`by`/`from` 上下文和 damage type 元数据）、`effect give/clear`、`enchant`、`attribute modifier add/remove/value get`（含 total 计算）、`gamemode`、`spectate`、`spreadplayers`；
  - 进度与合成：`advancement grant/revoke`（`from`/`through`/`until` 树展开 + criterion 更新输出）、`recipe give/take`（维护玩家 recipe 集合，报告 changed 数量与 id 列表）、`trigger`、`scoreboard objectives modify/setdisplay`（objective 元数据进入 `objectiveDetails` snapshot）、`team add/remove/list/join/leave/empty/modify`、`schedule`、`tag`；
  - 输出与聊天：`say`、`me`、`msg`/`tell`/`w`、`teammsg`/`tm`、`tellraw`、`title`、`particle`、`playsound`/`stopsound`——chat 类命令命中已加载 `chat_type` 时附带装饰 JSON 元数据；
  - 世界生成：`place structure`/`place template` 可展开沙盒结构 JSON（`blocks`/`entities` 或 palette-style `palette`/`palettes`）与 `worldgen/structure`、`structure`、`structures` 目录里的二进制结构 NBT，记录 `placed`、`format`、`sourceFormat`、`changedBlocks`、`skippedBlocks`、`processedBlocks`、`unsupportedProcessors`、`entities` 与变化坐标；结构 JSON 可引用 `processor_list`（`block_ignore`、`protected_blocks`、`jigsaw_replacement`、`capped`、`nop`、带 block/tag 谓词的 rule 替换）；`place template` 支持确定性 rotation、mirror、integrity、seed；`place jigsaw` 解析 template_pool 的 single/legacy/list/feature 元素、fallback pool、元素 processor 和 jigsaw connector，`maxDepth > 1` 时按确定性方向偏移展开子结构，输出 `jigsawConnections`、`jigsawPieces`、`jigsawChildChangedBlocks`、`totalChangedBlocks`；`place feature` 解析 simple_block、block_column、disk、vegetation_patch、tree、basalt_columns、delta_feature、lake、spring_feature、block_pile、glowstone_blob、forest_rock、netherrack_replace_blobs、chorus_plant、replace_single_block、replace_blob、selector、random_patch、flower 和 sparse-world ore；缺失或不支持的资源保留结构化 worldgen intent 输出（`placed=false`）。
- **结构化 no-op / unsupported**：不适合完整模拟的命令保留可诊断的 no-op 语义或 unsupported warning，包括 `debug`、`jfr`、`perf`、`publish`、`stop`、`transfer`、`datapack enable/disable`、原版 `reload`、`ban`/`ban-ip`/`banlist`/`pardon`/`pardon-ip`、`op`/`deop`/`kick`/`whitelist`、`save-all`/`save-off`/`save-on`、`setidletimeout`——它们接受参数、记录结构化输出，但不改变宿主状态。

验收标准：

- `docs/command-support.zh-CN.md` 中每个命令的支持状态都和实现保持同步。
- 每个新增命令路径至少包含成功、参数错误、版本差异和 unsupported 策略测试。
- 命令失败时能返回命令文本、函数文件、行号、调用栈和版本。

## 阶段 3：世界、实体与玩家状态建模

这一阶段把数据包能观察或修改的状态建成稳定、可序列化、可断言的内存模型。

- **sparse world**：方块状态、方块实体 NBT、biome override、强加载 chunk、世界边界、时间、天气、难度、gamerule、出生点、确定性随机序列。predicate `location_check` 的 block 条件可读取显式 sparse block 的 id、block tag、state/property 和方块实体 NBT；方块与 biome override 可通过 world fixture、manifest/QuickTest assertion 和 REPL `inspect block`/`inspect biome` 定点检查；random sequence 状态进入 snapshot。
- **fixture 铺设**：区域 fixture（`from`/`to` 闭区间批量铺设 sparse blocks，支持单点覆盖）、结构 fixture（origin + 相对 block/entity offset，展开后复用普通 snapshot 与断言）、Java Anvil 存档导入（按单 chunk、chunk 列表或 `from`/`to` block 范围导入 blocks、block entities 和 entities）——三者都已接入 QuickTest world builder、manifest `world.*` 和 JSON Schema。
- **实体模型**：类型、UUID、位置、旋转、维度、tag、score holder、attributes/attribute modifiers、effects、passengers/vehicle、equipment、health、custom NBT。`item replace/modify entity` 覆盖非玩家实体 `weapon.*`/`armor.*` 槽位，`attribute modifier` 进入 snapshot 与 `Attributes[].modifiers` NBT 投影，`effect give/clear` 覆盖 active effects，entity predicate 的 `equipment`、`effects`、`distance`（absolute/horizontal/xyz 轴向）、`nbt` 条件复用同一套模型；REPL `inspect entity`/`inspect entities` 可按 UUID、score holder、玩家名、实体类型或 tag 输出完整状态。不执行 AI tick，但保留数据包可读写字段并明确 no-AI 语义。
- **玩家模型**：inventory、selected slot、ender items、recipes、stats、xp points/levels、health、food、gamemode、spawn、advancement progress、last input。玩家 NBT 默认只读（NBT 视图投影当前非空主手 `SelectedItem`），新建玩家使用当前 `defaultGameMode`；fixture/manifest/world builder 可声明末影箱物品与 advancement progress，`item`/`loot replace entity` 可按 `selectedSlot` 读写当前主手与 `enderchest.*` 槽。
- **item stack**：兼容旧版 NBT 和新版 components；命令 item argument 可直接输入 JSON/SNBT-lite NBT 与 components payload（括号内空格保留用于解析）。matcher 支持 id、tag、count、components path、NBT path、slot、enchantment、custom data；item predicate 支持具体 id、`#` item tag 和 `enchantments`/`stored_enchantments` 直接匹配。

验收标准：

- `snapshotJson()` 输出稳定排序，不受 map/list 插入顺序影响。
- world fixture、manifest world、quick-test world builder 三者能力一致或差异明确记录。
- 所有新增状态都有 snapshot、assertion 和 inspect 路径：schedule 队列（`assertScheduledFunction`、CLI `scheduled:<id>`、REPL `inspect schedule`）、random sequence（`assertRandomSequence`、`random-sequence:<name>`、`inspect random`）、forced chunk（`assertForcedChunk`、`forced-chunk:<x>,<z>`、`inspect forced-chunks`）、世界级状态（time/weather/difficulty/defaultGameMode/seed/spawn/tick/worldBorder，`inspect world`）、gamerule（`assertGamerule`、`gamerule:<rule>`）、scoreboard objective 元数据（`objectiveDetails` snapshot、`scoreboard-objective:<name>`）、display slot（`scoreboardDisplay`、`scoreboard-display:<slot>`）、team 与 bossbar UI 状态（`assertTeam`/`assertBossbar`、`team:<name>`/`bossbar:<id>`）。

## 阶段 4：输入事件与玩家交互模拟

这一阶段让沙盒模拟数据包常见的输入来源，重点是 advancement、predicate、交互型数据包和命令生成器测试。

- **`PlayerEvent` 扩展**：item used/consumed、entity interacted、entity killed、block placed/broken、changed dimension、tick、damage、death、inventory changed；键盘/鼠标输入保留 device、code、action、坐标、tick、source。路线图自然命名（`entity_killed`、`block_placed`、`block_broken` 等）已作为事件别名接入 advancement 匹配、CLI completion、REPL/CLI/manifest 简写和 event trace。
- **事件上下文匹配**：`PlayerEventTraceExpectation`、QuickTest API 和 manifest `eventTrace` 可按 item/entity/block/recipe、from/to dimension、damage source/amount、input device/code/action 匹配；未匹配 advancement criterion 时给出可读失败原因，可用 `failedAdvancement`、`failedCriterion`、`failureContains` 定位。`item_consumed`、`inventory_changed`、`item_picked_up`/`item_added`、`changed_dimension`、`damage`/`death`、`recipe_unlocked` 会同步更新可观察玩家状态（背包、food、维度、health、recipe 集合）。
- **方块坐标事件**：带 `blockPos` 的 `block_placed`/`block_broken` 会更新 sparse world 方块状态，并在 event trace 暴露目标坐标；CLI/REPL 简写支持 `x y z`、`pos=x,y,z`、`blockPos=x,y,z`、`@x,y,z` 四种写法。
- **事件通道**：为事件建立 manifest step、CLI command、REPL command 和 quick-test API；事件可以触发 advancement、predicate、loot、scoreboard、storage 和输出命令，并在不需要客户端物理的范围内更新玩家状态。advancement reward 已记录结构化输出（触发玩家、advancement、XP、recipe、function、loot table 和实际生成物品）。
- **示例**：`examples/player-events` 提供玩家事件矩阵 full-stack manifest，覆盖 tick、背包变化、物品使用/消耗/拾取、键鼠输入、实体交互、伤害/死亡、击杀、location、维度切换、方块放置/破坏、recipe unlock、effects changed，并断言 advancement、event trace、玩家状态、物品和方块状态。

验收标准：

- 每类 P0 事件至少有一个 full-stack 示例和一个 manifest 测试；`examples/player-events/player-events.dps.json` 已接入示例 manifest 回归。
- 输入事件可被 `assertPlayerLastInput`、snapshot、`inspect player`、`inspect recipes` 和 `inspect advancement-progress` 检查。
- advancement 条件不满足时能解释缺少的上下文或失败字段。

## 阶段 5：输出、Trace 与 Debug 体验

这一阶段让用户能解释"数据包为什么这样输出、断言为什么失败、哪条命令改变了状态"。

- **`OutputEvent`**：保留 tick、command、channel、targets、plain text、segments、payload、source location、function stack；channel 覆盖 chat、title、sound、visual、warning、data、debug、worldgen。
- **命令 trace**：CLI `--trace` 记录每条命令的上下文、结果、错误和输出事件，`--trace-file` 写出 JSONL 供 CI 使用，`--trace-filter` 可按 command、function、selector、output、score/storage 变化过滤（支持 `selector=`/`target=`、`success=`、`error=`/`diagnostic=`、`error-code=`、`error-message=`、`output=`、`outputs=`、`output-channel=`、`output-payload=` 等）。quick-test `TraceExpectation` 与 manifest `trace` 断言支持同样的输出数量/文本/目标与 snapshot diff 匹配。REPL/CLI 命令目录会把 `place` 标为 `observed-noop` 并提供子命令补全，避免工具提示和核心执行语义不一致；quick-test 与 manifest 输出断言已覆盖 `place` 的 `worldgen` channel 与 `payloadPath` 匹配。
- **snapshot diff**：对比执行前后 world、score、storage、player、entity、block、outputs；manifest 失败时显示最小差异而不是只给最终 snapshot。断言失败会带候选：payload 断言失败显示对应 `payloadPath` 的实际值，segment style 断言失败显示解析后的 text/color/bold/italic 等字段，`assertSnapshotDiff`、manifest `diagnostic`/`snapshotDiff` 失败都会列出实际候选。
- **失败解释**：输出最近相关命令、相关 state path、实际值、候选输出事件和建议检查项；`run --report-file`/`check --report-file` 把失败 trace 提取为 `diagnosticCount`/`diagnostics` artifact 字段（错误码、消息、命令、root、来源文件和行号），供 CI 直接读取。

验收标准：

- `check --snapshot-on-fail` 保持兼容，新增 diff/trace 不破坏旧输出。
- `run`、`check`、REPL 和 quick-test report 都能读取同一套 trace model。
- 输出断言支持 text、contains、targets、payload path、segment style、count、order。

## 阶段 6：测试入口与使用场景

这一阶段针对不同用户场景提供最短路径，同时共用同一套核心运行时和断言系统。

### 单元调试

`SandboxQuickTest` fluent API 提供 `assertScore`、`assertStoragePath`、`assertPlayer`、`assertEntity`、`assertBlock`、`assertItem`、`assertOutput`、`assertTrace`、`assertRandomSequence`、`assertForcedChunk`、`assertGamerule`、`assertScheduledFunction`、`assertScoreboardObjective`、`assertScoreboardDisplay`、`assertTeam`、`assertBossbar` 等断言，并支持可复用 fixture（world setup、players、entities、blocks、storage、scoreboard、packs）。quick-test report 暴露 `resourceSummary`，与 `run`/`check` report、REPL `inspect resources` 共用 core 的资源数量、overlay、missing-reference 诊断模型；失败时输出最小 snapshot diff 和 trace 摘要。

### 随手小测试

CLI `run` 支持 `--world`（小型 JSON fixture）、`--assert`/`--assert-file`（一两个简单断言）、`--stdin`（从标准输入读函数或命令）、`--allow-command-failure`（预期失败后继续执行）。断言简写覆盖 score、storage、advancement、predicate、loot、player、world、gamerule、random sequence、scheduled function、snapshot、block、biome、team、bossbar、item、entity、diff、event-trace、trace、diagnostic、warning、unsupported 与 output 系列（`output-count`、`output-order`、`output-exact`、`output-matches`、`output-normalized-*`、`output-segment-*`、`output-payload`）。REPL 提供 `inspect` 系列（entity、item、recipes、advancement-progress、gamerule、scoreboard、team、bossbar、forced-chunks、world/worldborder、block/biome、event-traces、resources），以及 `trace on/off`、`diff last`、`rerun last`、`reset world`、`load fixture`。

### 命令生成器产物测试

提供专用模板：输入支持生成器输出的 command、command file、mcfunction text 或临时 pack（`examples/generator-template` 已有严格模式模板），环境声明依赖 pack、版本、world fixture、seed、默认玩家，断言覆盖输出文本、score/storage、NBT、实体数量、unsupported warning 数量。严格模式下 unknown/unsupported command、schema mismatch、资源缺失都会失败：`run --strict`/`check --strict` 把 unsupported command 设为 error 并启用直接缺失资源引用失败，`check --strict` 还会先做 manifest schema 校验。输出已规范化，避免空白、斜杠、换行差异造成误判。

验收标准：

- README/README.zh-CN 中已为 JVM 单元调试、随手小测试、命令生成器输出、full-stack、player-events、single-function、generator-output 和 multi-version 示例提供最短入口。
- `examples/` 至少包含 full-stack、player-events、single-function、generator-output、generator-template、multi-version 六类示例。
- CLI 和 quick-test 对同一清单行为输出一致结果。

## 阶段 7：Manifest 格式演进

这一阶段让 `.dps.json` 成为稳定的回归测试格式，覆盖输入、执行、断言、trace 和多版本矩阵。

- **Schema**：`schema/manifest/dps-manifest.schema.json` 是仓库唯一 schema，standalone jar 内置同一份；`schema --check schema/manifest/dps-manifest.schema.json` 可校验两者一致，并已接入 Gradle smoke。
- **`world`**：支持 fixture 引用、模板继承（`extends`）、局部覆盖、save import 范围、多个玩家和多个维度；`world.save`/`world.saves` 已通过 schema 和 manifest 回归测试覆盖 `from`/`to` 范围导入。
- **`steps`**：支持 `commands` 数组、`functionText`、`mcfunction`、`event`、`trace`、`snapshot`、`reset`。
- **`assertions`**：score、storage、player、entity、block、item、loot、predicate、advancement、random sequence、forced chunk、gamerule、scheduled function、scoreboard objective/display、output、trace、diagnostic、snapshot diff；支持 equals、contains、exists、missing、count、min/max、matches、path，output/segment 断言支持正则 `matches`，storage 与 NBT/components path expectation 支持 `contains`、`matches`、`missing`。
- **include**：公共世界 fixture、公共断言、公共 pack matrix 可复用，`include` 按来源文件相对路径合并 world/steps/assertions，公共/default packs 排在 case-local packs 之前。

验收标准：

- manifest 新增字段向后兼容。
- JSON Schema 能被编辑器使用，并在 CLI 中可选校验。
- manifest 失败消息包含 assertion index、path、expected、actual；断言失败前缀细化到断言 kind 的 JSON Pointer（如 `/assertions/0/output`）；include 合并进来的断言失败会保留来源文件路径和该文件内的 JSON Pointer（如 `common/base.dps.json/assertions/0/score`）。

## 阶段 8：版本 Profile 与原版资料更新流程

这一阶段保持多版本兼容，降低新增 Minecraft 版本时的维护成本。

- **固化更新流程**：pack format、data version、资源目录、命令根、注册表默认值、NBT schema 全部通过公开资料和 `vanilla-mcdoc` 生成，不分发 Mojang 服务端代码。
- **版本差异报告**：`version --docs/--json --output <file>` 可把 Markdown 表格、profile 元数据、完整 registry 条目和差异报告写入 UTF-8 文件；`version --docs --check <file>`（以及 `--locale zh-CN` 变体）在 CI 中校验中英文文档包含当前生成表格，防漂移任务已接入 Gradle `check`。
- **多版本测试矩阵**：同一行为在 `1.20.4`、中间版本和默认最新版本运行；对 pack format 不同的示例使用 per-version pack。

验收标准：

- 新增版本只需改 profile 数据和生成资源，核心逻辑尽量无需修改。
- `docs/version-profile.zh-CN.md` 可通过 `version --docs --locale zh-CN --output` 半自动更新，中英文表格通过 `version --docs --check` 防漂移。
- 版本不兼容错误清晰说明当前 pack format 和期望 format。

## 阶段 9：差分验证与可信度提升

这一阶段用可控方式提高沙盒行为与原版的接近度，同时避免引入原版服务端依赖。

- **Golden case**：对常见命令、资源、事件和输出保存稳定 expected snapshot。manifest `snapshot` 断言支持把最终 snapshot 根对象或选定 path 与内联 JSON 或仓库中的 golden JSON 比较，`examples/golden-snapshot` 已覆盖该回归模式；bugfix 增加回归用例。
- **可选外部差分**：用户本地提供原版服务端或第三方测试环境时，`diff --script --output <file> <manifest.dps.json>` 从 manifest/include 合并后的步骤导出可重放命令脚本，把 event、fixture、trace、snapshot、reset 等沙盒专用步骤保留为注释；`diff` CLI 可比较两份确定性 JSON snapshot/report，支持字段级 JSON Pointer 差异、JSON artifact 输出和 `--check` 作为 CI gate。该流程不作为核心构建依赖，不提交 Mojang 代码或产物。
- **行为等级**：`exact`（与原版可观察行为高度一致）、`modeled`（沙盒内确定性模型）、`observed-noop`（接受命令并记录输出/诊断）、`unsupported`（按策略 warn/error/ignore）。`commands` CLI 可按版本导出命令目录、行为等级和描述（plain/Markdown/JSON/`--output`/`--check`）；`ResourceCatalog` 集中维护资源类型与行为等级，`resources` CLI 可导出同一份目录和已加载资源索引，两者都接入中英文 standalone jar smoke 检查，避免 loader、文档和检查工具分叉。

验收标准：

- 每个命令和资源文档标注行为等级。
- 差分报告能指出字段级差异，而不是只给通过/失败。
- 任何 intentionally different 行为都在文档中说明原因。

## 阶段 10：性能、稳定性与发布质量

这一阶段让沙盒撑得住较大的数据包、批量清单和 CI 负载。

- **性能基准**：`benchmark` CLI 提供内置 smoke/CI 基准，覆盖 scoreboard 批量写入、大 storage merge、函数调用链、批量 manifest 执行，可选 `--pack` 测量 pack 加载、`--loot-table` 抽样 loot，并可写 JSON artifact。
- **缓存**：`DatapackLoader` 提供目录/zip 解析缓存（键为版本 profile + 内容指纹，命中返回深拷贝），`clearCache()` 供 REPL/watch 强制 reload；NBT schema 按 classpath 一次性加载并按 profile 复用；版本 profile、文档表和 registry 目录集中在不可变对象中；`ManifestSchemaValidator` 对 JSON Schema lazy 缓存。
- **错误边界**：`SandboxLimits` 可配置函数递归深度、sandbox 实例累计命令数、单次 `runTicks` 最大 tick 数、保留输出事件数和渲染后 snapshot 大小；CLI `run`/`check` 暴露 `--max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events`、`--max-snapshot-bytes` 直接收紧执行边界。
- **发布质量**：fat jar smoke（schema 导出与防漂移、示例 manifest、命令/资源/版本文档中英检查、资源索引、diff、benchmark、README 示例、run 断言简写、执行边界、diagnostic 断言）、Windows/Linux 命令测试。`releaseCheck` 聚合全部 JVM 模块检查、schema 可复现性、API/架构门禁、standalone CLI smoke、release jar/sources jar/javadoc jar、Maven POM 生成检查和非 snapshot 语义版本检查；CI 在 Linux 和 Windows 矩阵上运行。Maven 发布使用统一坐标 `moe.afox.dpsandbox`、版本 `1.1.0`，包含源码包、文档包、POM 元数据和带凭据校验的远端仓库配置。

验收标准：

- CI 至少运行 unit、manifest、examples、fat jar smoke 四类测试。
- 大型测试失败时不会无限执行或输出不可控日志。
- 发布前所有文档示例命令都能运行。
- 1.0 发布前可用 `releaseCheck` 在本地和 CI 统一验证 artifacts、Maven metadata、standalone jar smoke 和三平台执行入口。

## 优先级建议

- **P0（必须优先）**：资源索引与 P0 资源加载；`execute`、`data`、`loot`、`item` 的高频路径；trace、snapshot diff、结构化失败解释；manifest schema 与断言扩展；quick-test API 与 CLI 场景补齐。
- **P1（紧随其后）**：更多资源类型与版本差异；玩家事件扩展；存档导入增强；命令生成器测试模板；多版本 profile 更新流程。
- **P2（1.0 收口）**：worldgen/structure 的实用近似已完成（结构/模板/jigsaw/feature 的确定性落地，见阶段 2）；完整原版区块生成和高精度生态系统模拟属于明确非目标。可选外部差分已收口到 `diff --script` 脚本导出与 `diff --snapshot --check` 比较；高级缓存由内容指纹缓存、schema lazy cache 和静态 profile 目录覆盖，watch/reload 通过 `clearCache()` 保留显式失效入口；Maven 发布与三平台测试完成，`releaseCheck` 统一验证。

## 设计约束

- 所有新能力必须通过 `core` 暴露稳定模型，再由 CLI、REPL、manifest 和 quick-test 复用。
- 所有输出和 snapshot 必须确定性排序。
- 所有 unsupported/no-op 行为必须可配置为 warn、error 或 ignore。
- 新增 public API 应尽量保持 Kotlin/Java 友好，不要求用户依赖 CLI 才能测试。
- 新增格式必须向后兼容现有 `.dps.json`。
- 不应为了模拟原版而引入网络服务端、Mojang 服务端 jar 或不可分发代码。

## 推荐里程碑

1. `0.2`：资源索引、manifest schema、trace 基础、更多输出断言。
2. `0.3`：`execute`/`data`/`loot`/`item` 高频路径补齐，命令生成器测试模板可用。
3. `0.4`：玩家事件和 world fixture 大幅增强，examples 覆盖主要使用场景。
4. `0.5`：多版本 profile 更新流程稳定，P0/P1 资源覆盖完成。
5. `1.0`：核心 API 稳定、CLI 行为稳定、文档示例可验证、CI 覆盖完整，可作为数据包本地回归测试工具长期使用；当前发布版本为 `1.1.0`。

## 限制

路线图记录方向和验收标准，不构成发布时间承诺；当前行为应以生成检查通过的命令、资源、版本参考和公开 API 基线为准。

## 相关页面

- [命令支持状态](/runtime/command-support)
- [资源格式](/resources/resource-formats)
- [版本 Profile](/resources/version-profile)
- [排障手册](/guide/troubleshooting)
