# CI 与覆盖率

## 适用场景

当数据包回归必须在合并或发布前运行，并需要确定 profile、稳定退出码、机器可读证据和可选的行/函数覆盖门槛时，使用这套工作流。

## 前置条件

CI 需要 Java 25 和路径确定的 CLI jar。Manifest、pack、fixture、golden file 要一并检出，使相对路径留在 workspace 内。固定生成 jar 所用的 Datapack Sandbox 发布版本；每个场景则用 `version`/`versions` 固定 Minecraft profile。

## 最小 CI 命令

```powershell
New-Item -ItemType Directory -Force build/dps | Out-Null
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples `
  --strict `
  --validate-schema `
  --coverage `
  --coverage-file build/dps/coverage.json `
  --report-file build/dps/report.json `
  --trace-file build/dps/trace.jsonl `
  --event-trace-file build/dps/events.jsonl `
  --outputs-file build/dps/outputs.jsonl
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

始终把 Java 进程退出码传给 CI。Artifact 上传步骤应使用“始终执行”条件，失败检查产生的 report 才不会丢失。

## GitHub Actions job

```yaml
jobs:
  datapack-regression:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - name: Build CLI
        run: ./gradlew :cli:fatJar
      - name: Run manifests
        run: |
          mkdir -p build/dps
          java -jar cli/build/libs/datapack-sandbox-cli.jar check cases \
            --strict --validate-schema --coverage \
            --coverage-file build/dps/coverage.json \
            --report-file build/dps/report.json \
            --trace-file build/dps/trace.jsonl \
            --outputs-file build/dps/outputs.jsonl
      - name: Upload Datapack Sandbox evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: datapack-sandbox
          path: build/dps
```

下游仓库可把 build step 换成获取固定 release jar，并按自身 supply-chain 策略校验 checksum。

## Manifest 发现与严格度

`check <input>...` 接受 Manifest 文件或目录。目录会递归搜索 `.dps.json`，结果按 Manifest 报告。生成的 report 应放在发现目录之外，避免后续 job 把 artifact 当输入。

| 选项 | CI 效果 |
| --- | --- |
| `--validate-schema` | 执行前拒绝错误字段与形态 |
| `--strict` | Schema 校验 + unsupported-as-error + missing-resource failure |
| `--fail-fast` | 首个失败 Manifest 后停止 |
| `--verbose` | 打印确定性资源摘要和 Manifest output |
| `--snapshot-on-fail` | 把失败完整状态加入控制台/report 证据 |
| `--snapshot-diff-on-fail` | 加入更小的初始到最终状态 diff |
| `--seed <n>` | 为受控诊断运行覆盖 Manifest seed |

快速 presubmit 或昂贵 case 可用 `--fail-fast`；定时/完整 job 若更重视收集全部失败，则不要启用。

## 覆盖率语义

行覆盖率统计沙盒执行过的 `.mcfunction` 可执行行，空行与注释行不进入分母。函数覆盖率统计至少调用一次的已加载函数。覆盖在一次 run/attempt 内累计；不同 profile 的加载资源集合可能不同，因此 coverage 也是 profile-specific。

Manifest 可以自己拥有门槛：

```json
{
  "version": "26.2",
  "packs": ["pack"],
  "coverage": {
    "minimumLine": 90,
    "minimumFunction": 80,
    "include": "demo:*",
    "exclude": "demo:generated/*"
  },
  "steps": [{ "function": "demo:main" }]
}
```

临时 `run`/`check` job 也能用 CLI 施加或覆盖 gate：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases `
  --coverage `
  --minimum-line-coverage 85 `
  --minimum-function-coverage 75 `
  --coverage-include "demo:*" `
  --coverage-exclude "demo:generated/*" `
  --coverage-file build/dps/coverage.json
```

Include/exclude 是资源 id glob。先从 namespace（如 `demo:*`）开始，不要一开始排除所有 helper；过窄分母会让比例通过，却遗漏重要代码。描述场景契约的 threshold 应提交在 Manifest 附近；仓库全局策略再使用 CLI threshold。

## 解读 artifact

| 文件 | 何时保留 | 回答的问题 |
| --- | --- | --- |
| `report.json` | 始终 | 哪个 Manifest/version 失败，状态/资源/coverage 如何？ |
| `coverage.json` | 启用 coverage 时 | 哪些函数/行命中或遗漏？ |
| `trace.jsonl` | 调用链重要时 | 哪条命令从何处运行，改变/输出了什么？ |
| `events.jsonl` | 测试玩家行为时 | 哪个 trigger/criterion 匹配或失败？ |
| `outputs.jsonl` | 用户可见输出重要时 | 产生了哪条 chat/title/sound/结构化事件？ |
| snapshot/diff | 状态回归需要基线时 | 哪些模型状态存在或变化？ |

`check --report-file` 写 Manifest result 数组。多版本 result 每个 profile 有独立 attempt，包含 packs、messages、snapshot/diffs、resources、coverage。消费者应忽略未知 JSON 字段，使报告可以兼容地增加模型细节。

## 退出码与重试策略

| 码 | 含义 | 常见 CI 处理 |
| --- | --- | --- |
| `0` | 所有检查与 threshold 通过 | 继续 |
| `1` | Assertion 或 coverage threshold 失败 | 失败，检查 report/diff |
| `2` | CLI/Manifest 输入无效 | 失败，修 schema/path/generator output |
| `3` | Unsupported、version、resource、command、interrupt 或 missing-context 诊断 | 失败，检查 diagnostic/support boundary |

相同输入与发布版本下这些失败是确定性的。盲目重试通常只会隐藏问题；只重试基础设施获取/上传失败，不重试已完成的 sandbox check。

## 安全与可复现性

- 不可信生成 case 使用 `--max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events`、`--max-snapshot-bytes`。
- 不要把 secret 放入 fixture、command、output text、NBT 或 report path；artifact 会有意保留丰富状态。
- Golden snapshot 要纳入 review，只在检查过 `diff` 后更新。
- 路径、zip 或生成文件可能跨平台变化时，在支持的 CI OS 上跑同一 gate。
- Renderer 图像是 clean-room 诊断，不是原版像素 golden assertion。

## 仓库级 gate

本仓库中，`:cli:smokeCliJarExamples` 用构建 jar 验证所有 example Manifest；`:cli:smokeCliJar` 还检查 schema、受保护文档表、CLI 示例、渲染和分发行为；`releaseCheck` 是完整模块/发布 gate。

## 限制

- Coverage 证明沙盒模型路径执行过，不证明 unsupported/partial 行为与原版相同。
- 只有已加载函数参与；按版本的 pack map 和 filter 都会改变分母。
- 完整 snapshot 可能很大或包含偶然模型状态；耐久契约优先 targeted assertion 与 diff。
- Datapack Sandbox 不能替代明确需要网络、真实客户端、红石/物理或原版服务端的少量测试。

## 相关页面

- [Manifest 回归测试](/workflows/manifest-tests)
- [CLI 参考](/reference/cli)
- [报告与可观测性](/reference/reports-observability)
- [命令支持](/runtime/command-support)
