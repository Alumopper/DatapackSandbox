# 版本配置

第一个版本配置是 `26.1.2`，使用 Java 25。创建此配置时，Mojang manifest 将 `26.1.2` 列为最新 release，将 `26.2-rc-1` 列为最新 snapshot。

沙盒使用洁净室运行时代码。Mojang 的 `server.jar` 只作为本地参考来源，用于生成命令树和注册表等报告；它不会被嵌入 standalone CLI jar。

参考来源：

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://minecraft.wiki/w/Commands
- https://minecraft.wiki/w/Loot_table
- https://minecraft.wiki/w/Predicate
- https://minecraft.wiki/w/Advancement_definition
- https://c4k3.github.io/wiki.vg/Data_Generators.html
