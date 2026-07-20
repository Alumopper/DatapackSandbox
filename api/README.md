# JVM API baselines

The `*.api` files are generated from the Java 25 module jars with `javap
-public`. `./gradlew apiCheck` compares current jars with these checked-in
baselines; run `./gradlew apiDump` only after reviewing an intentional binary
API change.

The dump is deliberately bytecode-oriented, so Kotlin `internal` declarations
that compile to JVM-public members may also appear. This makes the gate stricter
than Kotlin source visibility alone and ensures implementation refactors are
reviewed when they alter emitted class or method signatures.
