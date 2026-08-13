# 使用 REPL 调试

## 适用场景

修改 pack 时，如果需要持久内存世界、命令补全、快速前后 diff 或结构化检查，使用 REPL。它很适合找出正确的复现路径；序列稳定后应迁入 Manifest 或 QuickTest，让 CI 能重复执行。

## 前置条件

准备至少一个数据包目录或 zip。`repl` 要求一个或多个 `--pack`，并支持 `--version`、`--watch`、`--unsupported`；它不提供 `run` 的完整 artifact/limit 选项。

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar repl `
  --version 26.2 `
  --pack .\my-pack `
  --watch
```

启动卡片显示 profile、pack 数、watch/trace 状态、game time、玩家和实体数；prompt 也会标出启用的 `watch`/`trace` 模式。

## 最小调试会话

```text
status
function demo:main
diff last
inspect scores
inspect outputs
trace on
rerun last
snapshot build/repl-state.json
```

`function demo:main` 修改保留的世界；`diff last` 比较该操作紧邻的前后 snapshot。启用 trace 后，`rerun last` 在当前世界再次执行相同输入，并打印新的 trace。

## 输入与补全

行编辑器提供 history、语法高亮、TAB completion、inline hint 与多行说明。补全会依据 profile，并使用已加载资源和当前世界数据。不是已识别 REPL 命令的输入会当作 Minecraft 命令执行；开头 `/` 可省略。

| 命令 | 用途 |
| --- | --- |
| `help` / `help <command>` | 列出命令或查看具体语法 |
| `status` | 重绘 session dashboard |
| `load` | 运行 `#minecraft:load` |
| `tick [n]` | 推进世界，默认一 tick |
| `function <id>` | 运行已加载数据包函数 |
| `player <name>` | 创建沙盒玩家 |
| `event player ...` | 注入支持的玩家行为/输入事件 |
| `load fixture <file>` | 应用 Manifest 风格 world fixture |
| `reload` | 重载 pack 资源并保留世界 |
| `reset world` | 新建稀疏世界与默认 `Steve` |
| `trace on|off|status` | 控制实时 command trace 输出 |
| `diff last` | 查看上次 world-changing 操作的 diff |
| `rerun last` | 重复该操作 |
| `snapshot [file]` | 打印或保存完整状态 |
| `inspect <kind> ...` | 查询世界/资源/事件模型 |
| `exit` | 关闭会话 |

无需死记所有 inspect 参数，优先使用 `help inspect` 与补全。

## Reload 与 watch 语义

`reload` 重新解析配置的 pack 并重建 datapack view，同时保留当前 `SandboxWorld`。`--watch` 会在处理下一行前检查 pack 修改时间，发现变化时自动执行同类 reload。

注意几个边界：

- Reload 会替换函数/资源，但保留 score、storage、block、entity、player、time、outputs 和 traces。
- `reset world` 用相同 pack 配置建立新世界。
- Reload 错误只打印诊断，不会退出交互进程；修复文件后再提交一行即可。
- Watch 是按输入触发的；文件变化本身不会执行函数。

修改 load tag 或初始化逻辑后，要明确选择 `reload` + `load`（保留旧状态）还是 `reset world` + `load`（验证干净启动）。

## 检查沙盒

Inspection group 包括：

- `world`：time、weather、difficulty、game mode、border、spawn、tick state；
- `scores`、`scoreboard`、`teams`、`bossbars`；
- storage、gamerule、scheduled function、random sequence、forced chunk；
- block、biome、entity、player、inventory item、recipe、advancement progress；
- resource index、active/overridden resource、registry 和 raw registry entry；
- outputs 与 player-event traces。

大型 pack 应缩小到单个玩家、资源 id 或 registry group。资源检查会暴露覆盖顺序与缺失引用，常能直接解释函数/predicate 为什么像是“没有加载”。

## Trace、diff 与 rerun

Trace 开关影响后续操作；旧 trace 仍留在 world record。每项包含 command/root、可用时的 source location、success/diagnostic、command count、emitted outputs 与 snapshot diffs。

`diff last` 和 `rerun last` 指向最近一次改变世界的 REPL 输入，不是最近一次 help/inspect。Rerun 不会回滚，而是在当前状态再次应用操作。需要证据时保存 snapshot；需要干净基线时使用支持 checkpoint 的宿主或 Manifest。

## 交互式加载 fixture

```text
load fixture fixtures/arena.json
player Alex
function demo:setup
inspect player Alex
inspect block 0 64 0
```

Fixture 文件使用 Manifest world 语法，可设置玩家、方块、实体、分数、storage、时间、team、bossbar 等稀疏状态。Fixture 内相对路径按自身目录解析。完整目录见 [QuickTest Fixture](/reference/quicktest-fixtures)。

## 实用编辑循环

1. 用 `--watch` 启动，执行最窄函数并检查直接结果。
2. 状态错误或调用链不清楚时才打开 trace。
3. 用 `diff last` 定位变化 JSON path，用 `inspect outputs` 看用户可见行为。
4. 修复 pack；下一次提交输入会触发 watch reload。
5. 验证从零初始化前执行 `reset world`。
6. 把最终 fixture、steps 与 assertions 写入 `.dps.json`。

## 限制

- Watch 使用修改时间，不是事务式文件系统 snapshot；编辑器连续保存多个文件时可能看到中间状态。
- 进程退出后不会保留 REPL world，除非显式保存 snapshot；snapshot 是证据，不是原版存档。
- `rerun last` 会累积状态，不等同于测试隔离。
- 交互控制台不是机器协议；自动化使用 `run`、`check` 或 `serve`。

## 相关页面

- [CLI 运行工作流](/workflows/cli)
- [Manifest 回归测试](/workflows/manifest-tests)
- [命令支持](/runtime/command-support)
- [世界模型](/runtime/world-model)
- [报告与可观测性](/reference/reports-observability)
