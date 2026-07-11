---
layout: home
title: Datapack Sandbox
titleTemplate: false

hero:
  name: Datapack Sandbox
  text: 快速验证数据包
  tagline: 面向 Minecraft Java 数据包的本地虚拟运行时。执行函数、检查资源、注入玩家事件，并用可重复的断言锁定世界状态。
  image:
    src: /datapack-sandbox-mark.svg
    alt: Datapack Sandbox 立方体命令行标志
  actions:
    - theme: brand
      text: 5 分钟快速上手
      link: /guide/getting-started
    - theme: alt
      text: 浏览测试方案
      link: /guide/testing-patterns
    - theme: alt
      text: 查看 GitHub
      link: https://github.com/Alumopper/DatapackSandbox

features:
  - title: 从一条命令开始
    details: 直接运行 `.mcfunction`、命令或完整数据包，不必等待原版服务端启动。
    link: /guide/getting-started
    linkText: 选择运行入口
  - title: 把世界状态写成断言
    details: 检查计分板、storage、实体、玩家、输出事件、trace 与 snapshot diff。
    link: /guide/testing-patterns
    linkText: 设计回归测试
  - title: 明确知道模拟边界
    details: 每项命令与资源都标注 modeled、partial 或 unsupported，避免把近似行为误当成原版行为。
    link: /runtime/command-support
    linkText: 查看支持矩阵
  - title: 接入现有 JVM 测试
    details: 使用 Kotlin/Java API 编写 QuickTest，也可以通过 CLI 和 manifest 接入 CI。
    link: /guide/code-test-api
    linkText: 阅读代码测试 API
---
