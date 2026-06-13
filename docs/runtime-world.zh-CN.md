# 运行时世界模型

沙盒世界是一个内存中的稀疏模型：

- 方块只有被显式放置后才会存储；初始世界是完全虚空。
- `setblock` 和 `fill` 不执行物理、红石、邻居更新或方块实体 tick。它们支持方块状态和初始方块实体 SNBT，例如 `minecraft:chest{Items:[...]}`。
- 运行时默认创建名为 `Steve` 的玩家。该玩家会参与选择器，并暴露可读的原版风格 NBT。
- 实体提供确定性的 NBT 视图，会生成 `id`、`UUID`、`Pos` 和 `Tags`，并补充 generated mcdoc schema 允许的原版字段。非玩家实体 NBT 可以通过 `data` 修改，但未知顶层字段会被拒绝。
- 方块实体来自 generated mcdoc 的 block-to-block-entity dispatch 映射。它们会暴露原版风格的 `id`、`x`、`y`、`z` 以及 schema 字段。未知自定义方块 NBT 字段会被拒绝，不再作为任意沙盒 JSON 保存。
- 玩家提供可读 NBT 视图，但禁止通过 `data` 写入。移动玩家应使用 `tp|teleport`、manifest 玩家步骤或玩家事件。
- tick 会运行计划函数、tick 标签和沙盒玩家事件。实体 AI 和重力不模拟；沙盒也不会自动写入 `NoAI: true`。

## 从 mcdoc 获取原版数据

运行：

```powershell
.\gradlew.bat generateVanillaNbtSchemas --no-daemon --console=plain
```

该任务会把 `SpyglassMC/vanilla-mcdoc` 下载到 `build/vanilla-mcdoc`，安装官方 `@spyglassmc/mcdoc` 解析器，解析 mcdoc AST，并生成 `build/generated/vanilla-mcdoc/resources/vanilla-nbt-schemas.json`。`:core` 的资源流程依赖这个任务，因此运行时会从 classpath 加载该生成 schema；只有资源不存在时，才回退到旧的保守内置 schema。

旧的 `generateVanillaMcdocIndex` 任务仍保留，用于人工快速查看和实验性的词法索引，但它不再是 NBT 校验来源。细粒度的 `since`/`until` 属性裁剪仍是后续工作；当前运行时消费 upstream vanilla mcdoc snapshot，并继续对未知顶层字段给出严格诊断。

## 引入原版客户端还是继续模拟

引入真实 Minecraft 客户端/服务端对本项目更重：它会带来渲染、资源、认证/会话假设、原生平台问题，以及原版线程和世界生命周期约束。对数据包本地检查而言，有限的洁净室模拟更轻、更确定，适合 CLI 使用，也更容易在缺失上下文时给出严格诊断。

更合理的分工是：沙盒继续作为快速、确定性的运行器；原版生成报告和 mcdoc 数据作为命令树、registry、JSON/NBT schema 与边界行为的参考来源。
