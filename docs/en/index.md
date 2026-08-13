---
layout: home
title: Datapack Sandbox
titleTemplate: false

hero:
  name: Datapack Sandbox
  text: Run, test, and debug datapacks before shipping
  tagline: A clean-room local runtime for Minecraft Java datapack authors. Run with the CLI, lock regressions into manifests, and debug interactively in VS Code.
  image:
    src: /datapack-sandbox-mark.svg
    alt: Datapack Sandbox cube and command prompt mark
  actions:
    - theme: brand
      text: Run a datapack
      link: /en/workflows/cli
    - theme: alt
      text: Write a manifest
      link: /en/workflows/manifest-tests
    - theme: alt
      text: View on GitHub
      link: https://github.com/Alumopper/DatapackSandbox

features:
  - title: 1. Run a datapack
    details: Use `run` with a pack, function, or single `.mcfunction`, then inspect outputs, traces, and snapshot diffs immediately.
    link: /en/workflows/cli
    linkText: Open the CLI workflow
  - title: 2. Write a manifest
    details: Combine world fixtures, steps, assertions, version matrices, and coverage thresholds in `.dps.json`.
    link: /en/workflows/manifest-tests
    linkText: Lock down a regression
  - title: 3. Debug in VS Code
    details: Run functions from the editor, inspect traces, keep an active sandbox, or connect the Jupyter kernel.
    link: /en/guide/vscode-extension
    linkText: Configure VS Code
  - title: Browser and JVM integration
    details: Embed the Playground, Core, Renderer, or Serve JSONL in websites, test frameworks, and editors.
    link: /en/reference/core-api
    linkText: Choose an integration API
---
