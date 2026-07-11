---
layout: home
title: Datapack Sandbox
titleTemplate: false

hero:
  name: Datapack Sandbox
  text: Verify datapack behavior without starting a server
  tagline: A clean-room local runtime for Minecraft Java datapacks. Run functions, inspect resources, inject player events, and lock world state down with repeatable assertions.
  image:
    src: /datapack-sandbox-mark.svg
    alt: Datapack Sandbox cube and command prompt mark
  actions:
    - theme: brand
      text: Start in 5 minutes
      link: /en/guide/getting-started
    - theme: alt
      text: Explore testing patterns
      link: /en/guide/testing-patterns
    - theme: alt
      text: View on GitHub
      link: https://github.com/Alumopper/DatapackSandbox

features:
  - title: Start with one command
    details: Run a `.mcfunction`, direct command, or complete datapack without waiting for a vanilla server to boot.
    link: /en/guide/getting-started
    linkText: Choose an entry point
  - title: Turn world state into assertions
    details: Check scores, storage, entities, players, output events, traces, and snapshot diffs.
    link: /en/guide/testing-patterns
    linkText: Design regression tests
  - title: Know the simulation boundary
    details: Commands and resources are labeled modeled, partial, or unsupported so approximations never masquerade as vanilla behavior.
    link: /en/runtime/command-support
    linkText: Read the support matrix
  - title: Fit into JVM test suites
    details: Write Kotlin or Java QuickTests, or connect the CLI and manifest runner to CI.
    link: /en/guide/code-test-api
    linkText: Open the code test API
---
