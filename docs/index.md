---
layout: home
title: Datapack Sandbox
titleTemplate: false

hero:
  name: Datapack Sandbox
  text: 在提交前运行、测试并调试数据包
  tagline: 面向 Minecraft Java 数据包作者的 clean-room 本地运行时。先用 CLI 运行，再用 Manifest 固化回归，最后在 VS Code 中逐步调试。
  image:
    src: /datapack-sandbox-mark.svg
    alt: Datapack Sandbox 立方体命令行标志
  actions:
    - theme: brand
      text: 运行一个数据包
      link: /workflows/cli
    - theme: alt
      text: 编写 Manifest
      link: /workflows/manifest-tests
    - theme: alt
      text: 查看 GitHub
      link: https://github.com/Alumopper/DatapackSandbox

features:
  - title: 1. 运行数据包
    details: 用 `run` 执行 pack、函数或单个 `.mcfunction`，立即查看 output、trace 和 snapshot diff。
    link: /workflows/cli
    linkText: 打开 CLI 工作流
  - title: 2. 编写 Manifest
    details: 用 `.dps.json` 组合世界 fixture、步骤、断言、版本矩阵和覆盖率阈值。
    link: /workflows/manifest-tests
    linkText: 固化回归场景
  - title: 3. 在 VS Code 调试
    details: 从编辑器运行函数、查看 trace、维护活动沙盒，也可以连接 Jupyter kernel。
    link: /guide/vscode-extension
    linkText: 配置 VS Code
  - title: 浏览器与 JVM 集成
    details: 嵌入 Playground、Core、Renderer 或 Serve JSONL，把同一个沙盒接入网站、测试框架和编辑器。
    link: /reference/core-api
    linkText: 选择集成接口
---
