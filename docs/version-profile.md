# Version Profile

The first profile is `26.1.2`, using Java 25. Mojang's manifest currently lists
`26.1.2` as the latest release and `26.2-rc-1` as the latest snapshot at the
time this profile was created.

The sandbox uses clean-room runtime code. Mojang's `server.jar` is only a local
reference source for generated reports such as command trees and registries; it
is not embedded in the standalone CLI jar.

Reference sources:

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://minecraft.wiki/w/Commands
- https://minecraft.wiki/w/Loot_table
- https://minecraft.wiki/w/Predicate
- https://minecraft.wiki/w/Advancement_definition
- https://c4k3.github.io/wiki.vg/Data_Generators.html
