# Serve JSONL 协议

## 适用场景

编辑器、Notebook kernel、语言工具或其他进程需要一个长期存在的 sandbox，并需要结构化请求、补全、渲染、事件分页、checkpoint 和可取消执行时启动 `serve`。普通一次性 shell 自动化使用 `run`/`check`；能在同一 JVM 内运行的集成可直接使用 Core API。

## 前置条件

构建或取得 standalone CLI JAR，然后把它作为子进程启动：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar serve --protocol jsonl
```

Transport 为 UTF-8 stdin/stdout，每行一个完整 JSON 对象。请求不能跨行 pretty-print。stdout 不混入人类日志，因此只用于协议；stderr 单独捕获。

## Envelope 与启动

服务会立即主动写一条 `id: null` 的 hello response：

```json
{"id":null,"ok":true,"result":{"protocol":"dps-jsonl","defaultVersion":"26.2","capabilities":{"render":true,"renderMimeType":"image/png","checkpoints":true,"functionSource":true,"interrupt":true,"eventTraces":true,"pagedEvents":true,"richOutput":true,"coverage":true,"commandDiagnostics":true},"versions":["1.20.4","26.2"]}}
```

示例省略了中间版本。开启相应功能前等待此行，校验 `protocol`，保存 `defaultVersion`/`versions`，并按 `capabilities` 开启可选 UI，不要假设所有 CLI 版本的能力面都一样。

每个请求包含应用自选的 `id`、`method` 与可选对象 `params`：

```json
{"id":"req-1","method":"state","params":{}}
```

ID 可为字符串或数字，响应会保留其 JSON 类型。未完成的请求应使用唯一 id，才能可靠关联队列操作与 out-of-band `interrupt`。

成功：

```json
{"id":"req-1","ok":true,"result":{"version":"26.2","gameTime":0,"entities":0,"players":0}}
```

失败：

```json
{"id":"req-2","ok":false,"error":{"code":"COMMAND_ERROR","message":"...","version":"26.2","command":"...","location":{"file":"demo.mcfunction","line":2,"command":"..."}}}
```

为向前兼容，consumer 应保留未知 result/error 字段，不应从 `message` 反向解析 code。

## 最小完整会话

收到 startup hello 后发送：

```jsonl
{"id":"1","method":"createFunctionSandbox","params":{"version":"26.2","defaultPlayerName":"Alex","mcfunctionId":"demo:main","mcfunctionText":"scoreboard objectives add runs dummy\nscoreboard players add #serve runs 1\nsay hello"}}
{"id":"2","method":"runFunction","params":{"id":"demo:main"}}
{"id":"3","method":"outputs","params":{"from":0}}
{"id":"4","method":"coverage","params":{}}
```

Tracked `runFunction` result 包含本次操作的命令数、output、trace、player-event trace、仅状态 snapshot diff 与当前精简 state。即使后续 query 失败也应保留它，因为这些变化已经提交到 sandbox。

## 创建与 reload

`createSandbox`、`createFunctionSandbox`、`open` 是别名，都会替换 active sandbox 与配置。

| 参数 | 形态 | 含义 |
| --- | --- | --- |
| `version` | string，默认服务 default | Minecraft profile id |
| `packs` | string 或 string[] | 数据包目录/ZIP 路径 |
| `functionSources` | array | `{id,text|content}` 或 `{id,path|file}`，可带 `sourceName` |
| `mcfunction` | string path | 单个 file-backed function |
| `mcfunctionText` | string | 单个 in-memory function |
| `mcfunctionId` | string，默认 `sandbox:main` | 单文件/文本 source 的 id |
| `defaultPlayerName` | string 或 null | 可选初始玩家；`serve` 中省略表示不创建 implicit player |
| `unsupported` | `warn`、`ignore`、`error` | unsupported feature 策略 |
| `limits` | object | `maxCommands`、`maxFunctionDepth`、`maxTicksPerRun`、`maxOutputEvents`、`maxSnapshotBytes` |

所有宿主路径都由 `serve` 进程规范化，相对路径按该进程的工作目录解析；除非编辑器文档恰好也在同一目录，否则不会相对文档解析。

`upsertFunctionSource` 用 `id` 加 `text`/`path` 二选一添加或替换 synthetic function，并保留 world。`reload` 按保存的配置重建资源，`keepWorld` 默认 `true`；`resetWorld` 在 fresh sparse world 上重建同一配置。Resource reload 对外部文件变化不是 transaction；应先保存编辑器内容，并在 reload 失败时保留上一份客户端视图。

## 方法

### 执行方法

| 方法与别名 | 关键 params | 说明 |
| --- | --- | --- |
| `load` | — | 运行 `#minecraft:load` |
| `tick`, `ticks` | `count`，默认 1 | 推进 modeled lifecycle |
| `runFunction`, `function` | `id` | 运行已加载函数 |
| `runCommand`, `command` | `command`，可选 `file`、`line`、`allowFailure` | 执行单条命令 |
| `runCommands`, `commands` | `commands`，可选 `file`、`allowFailure` | 跳过空行/注释，生成一基 line number |
| `runManifest` | `path`，可选 `strict` | 在 active sandbox 中运行 manifest，并用返回 sandbox 替换它 |
| `applyWorldFixture`, `world` | `world`/`fixture` 对象或 `path`，可选 `base` | 应用 manifest 风格 world setup |
| `injectPlayerEvent`, `event` | `event`，或 `player` + `type` + 可选 `id`/`detail` | 必要时先建玩家，再分发 event |

Tracked 方法只返回本次操作产生的 event，并附 `snapshotDiffs` 与 `state`。`allowFailure: true` 可为探索工具压制 command exception，但不会把命令变为成功；仍要检查新 trace。

### 状态与资源查询

| 方法 | 结果 |
| --- | --- |
| `snapshot` | 完整 modeled snapshot object |
| `snapshotString` | `{snapshot}` 中的稳定 snapshot 文本 |
| `state` | version、count、checkpoints 与资源摘要 |
| `resources` | 摘要、完整资源索引、function/loot/predicate/advancement ids |
| `functionSource` | `id`、可选 source file、重建后的 function source |
| `versions` | 带 Java/data/pack format 的详细 profile 列表 |
| `coverage` | coverage + `failures`/`passed`；可传 `minimumLine`、`minimumFunction`、`include`、`exclude` |
| `resetCoverage` | `{reset:true}` |
| `completions` | `buffer`/`cursor` 的 ranged suggestions、inline hint、multiline hints |
| `checkCommand` | 在复制的 validation world 中非破坏性返回 `{valid,severity,code?,message}` |
| `checkCommands` | 接受 `commands` 数组，在一个隔离 preview world 中依次检查并返回 `{checks:[...]}`；前面有效命令的 preview 状态对后面可见，active world 不变 |

### Checkpoint

`saveCheckpoint`、`restoreCheckpoint`、`deleteCheckpoint` 接受 `name`（默认 `default`），返回 action、是否变化、全部 name 与 current state；`checkpoints` 返回 `{names}`。仍遵守 Core 规则：名称 1–64 个安全 ASCII 字符、最多 32 个、只在 command boundary 操作、resource/budget 不进入 world 副本。

## 分页事件流

`outputs`、`traces`、`eventTraces` 接受零基 `from` cursor：

```json
{"id":"events-1","method":"outputs","params":{"from":12}}
```

```json
{"id":"events-1","ok":true,"result":{"from":12,"total":15,"outputs":[{},{},{}]}}
```

成功处理 array 后，把 `total` 保存为下次 `from`。负数 cursor 被 clamp 到 0；超过结尾时 array 为空并返回当前 `total`。Cursor 属于 active sandbox；`create*`/`open` 或 `resetWorld` 后重置。

## Partial error 与 interrupt

Tracked 操作抛出 `SandboxException` 时，failure 包含 `error.partial`：

```json
{"id":"5","ok":false,"error":{"code":"COMMAND_ERROR","message":"...","partial":{"commandsCompleted":2,"outputs":[],"traces":[],"eventTraces":[],"snapshotDiffs":[],"state":{}}}}
```

Partial payload 是已完成工作的权威记录。展示错误前先应用其 event/diff/state；如果不 restore checkpoint 就直接重试整个操作，前面命令可能执行两次。

`interrupt` 绕过普通单线程请求队列，设置 cooperative cancellation 并返回：

```json
{"id":"cancel-1","ok":true,"result":{"requested":true,"boundary":"command"}}
```

取消在 command/tick boundary 生效，不回滚已完成命令。普通请求串行运行；客户端虽可排队，但 UI 通常应限制同时只有一个 mutating operation。

## 渲染 PNG

::: warning 调用方必须传入客户端资源
`render`/`screenshot` 不扫描 `.minecraft`、不下载客户端，也不从 active `version` 推导 asset path。需要真实 Minecraft model/texture 时必须显式传 `minecraftAssets`；`resourcePacks`、`playerSkins` 同样是运行 `serve` 的机器上的路径。
:::

```json
{"id":"render-1","method":"render","params":{"minecraftAssets":"D:/Minecraft/versions/26.2/26.2.jar","resourcePacks":["D:/packs/base.zip","D:/packs/override"],"playerSkins":{"Alex":"D:/skins/alex.png"},"width":960,"height":540,"cameraPlayer":"Alex","strictAssets":true}}
```

Camera 优先级为 `cameraPlayer`、`cameraEntity`、带可选 `yaw`/`pitch`/`dimension` 的固定 `position:[x,y,z]`，最后才是 auto。其他字段为 `fieldOfView`、`renderDistance`、`transparentBackground`、`showHud`、`showDebugOverlay`。

省略 `minecraftAssets` 时只使用确定性 fallback，绝不会下载或定位 JAR。响应含 `mimeType:"image/png"`、`encoding:"base64"`、`data`、尺寸、asset sources、diagnostics、scene counts、timings，以及明确的 approximate-lighting/non-parity 标志。应尽快解码 `data`；编码后 PNG 不得超过 16 MiB。

## 进程所有权与恢复

- 一个进程持有一个 active sandbox。不同文档或安全域应启动独立进程。
- EOF、无效 JSON、子进程退出、写入失败都视为 session loss：拒绝未完成的请求，保留未保存的编辑器文本，重启并等待 hello，然后重建 sandbox，再执行 fixture/checkpoint 恢复策略。
- 不要把不受限制的 `serve` 子进程直接暴露给不可信远程客户端。Pack、manifest、function、world、render 参数都可能读取进程可访问的宿主路径。
- 父集成应约束路径，并让子进程只拥有工作区所需的最小文件权限。

## 限制

- 每个请求行最多 1,048,576 个字符；超限时无法完整解析 envelope，因此 failure 的 `id` 为 null。
- 普通 pending queue 最多等待 64 个请求；满时返回 `INPUT_FORMAT`。
- Base64 编码前的 PNG 上限 16 MiB。
- 创建时设置的 sandbox command/output/snapshot limits 仍然生效。
- JSONL 没有 binary frame，大图存在 base64 内存开销。

## 相关页面

- [CLI 参考](/reference/cli)
- [Jupyter](/integrations/jupyter)
- [Manifest 参考](/reference/manifest)
- [Renderer API](/reference/renderer-api)
- [报告与可观测性](/reference/reports-observability)
