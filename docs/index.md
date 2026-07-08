---
layout: home
title: Datapack Sandbox 文档
titleTemplate: false

hero:
  name: Datapack Sandbox
  text: 数据包沙盒文档
  tagline: 面向 Minecraft Java 数据包的本地 CLI 调试、manifest 回归检查和 JVM 代码级快速测试文档中心。
  icon: ⚡
  image:
    src: /datapack-sandbox-mark.svg
    alt: Datapack Sandbox
  actions:
    - theme: brand
      text: 开发者入门
      link: /guide/getting-started
    - theme: alt
      text: 测试模式
      link: /guide/testing-patterns
    - theme: alt
      text: English Docs
      link: /en/

features:
  - title: 开发者入门
    details: 从入口选择、最小依赖、第一个 QuickTest 到完整 pack 测试，按接入顺序走完第一条路径。
    link: /guide/getting-started
    linkText: 开始接入
  - title: 测试模式
    details: 按单函数、fixture、玩家事件、多版本矩阵、生成器产物等实际任务组织测试写法。
    link: /guide/testing-patterns
    linkText: 查看 cookbook
  - title: 命令与运行时模型
    details: 查阅原版命令在沙盒中的支持状态、行为等级、输出事件、selector 能力和 runtime world 边界。
    link: /runtime/command-support
    linkText: 查看运行时能力
---