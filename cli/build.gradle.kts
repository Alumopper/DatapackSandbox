import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jline:jline:3.30.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}

application {
    mainClass.set("moe.afox.dpsandbox.cli.MainKt")
}

tasks.processResources {
    from(rootProject.layout.projectDirectory.file("docs/dps-manifest.schema.json"))
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a standalone CLI jar."
    archiveFileName.set("datapack-sandbox-cli.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "moe.afox.dpsandbox.cli.MainKt"
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

val fatJar = tasks.named<Jar>("fatJar")
val cliSmokeJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

fun registerCliJarSmokeTask(
    name: String,
    descriptionText: String,
    vararg cliArgs: String,
) = tasks.register<Exec>(name) {
    group = "verification"
    description = descriptionText
    dependsOn(fatJar)
    executable = cliSmokeJavaLauncher.get().executablePath.asFile.absolutePath
    inputs.file(fatJar.flatMap { it.archiveFile })

    doFirst {
        setArgs(listOf("-jar", fatJar.get().archiveFile.get().asFile.absolutePath) + cliArgs)
    }
}

val smokeCliJarVersion = registerCliJarSmokeTask(
    name = "smokeCliJarVersion",
    descriptionText = "Runs the standalone CLI jar version command.",
    "version",
)

val versionProfileDoc = rootProject.layout.projectDirectory.file("docs/version-profile.md")
val smokeCliJarVersionDocs = registerCliJarSmokeTask(
    name = "smokeCliJarVersionDocs",
    descriptionText = "Checks that the generated version profile docs table is current.",
    "version",
    "--docs",
    "--check",
    versionProfileDoc.asFile.absolutePath,
)
smokeCliJarVersionDocs.configure {
    inputs.file(versionProfileDoc)
}

val commandSupportDoc = rootProject.layout.projectDirectory.file("docs/command-support.md")
val smokeCliJarCommandDocs = registerCliJarSmokeTask(
    name = "smokeCliJarCommandDocs",
    descriptionText = "Checks that command support docs cover the command behavior catalog.",
    "commands",
    "--check",
    commandSupportDoc.asFile.absolutePath,
)
smokeCliJarCommandDocs.configure {
    inputs.file(commandSupportDoc)
}

val resourceFormatsDoc = rootProject.layout.projectDirectory.file("docs/resource-formats.md")
val smokeCliJarResourceDocs = registerCliJarSmokeTask(
    name = "smokeCliJarResourceDocs",
    descriptionText = "Checks that resource format docs cover the resource behavior catalog.",
    "resources",
    "--check",
    resourceFormatsDoc.asFile.absolutePath,
)
smokeCliJarResourceDocs.configure {
    inputs.file(resourceFormatsDoc)
}

val examplesDir = rootProject.layout.projectDirectory.dir("examples")
val fullStackExamplePack = examplesDir.dir("full-stack/pack")

val smokeResourceIndexOutput = layout.buildDirectory.file("smoke/resources-loaded.json")
val smokeCliJarResourcesLoaded = registerCliJarSmokeTask(
    name = "smokeCliJarResourcesLoaded",
    descriptionText = "Checks the standalone CLI jar can export a loaded pack resource index.",
    "resources",
    "--version",
    "26.2",
    "--pack",
    fullStackExamplePack.asFile.absolutePath,
    "--type",
    "function",
    "--id",
    "demo:reward",
    "--namespace",
    "demo",
    "--source-pack",
    fullStackExamplePack.asFile.absolutePath,
    "--active-only",
    "--json",
    "--output",
    smokeResourceIndexOutput.get().asFile.absolutePath,
)
smokeCliJarResourcesLoaded.configure {
    inputs.dir(fullStackExamplePack)
    outputs.file(smokeResourceIndexOutput)
    doFirst {
        smokeResourceIndexOutput.get().asFile.parentFile.mkdirs()
    }
}

val smokeSchemaOutput = layout.buildDirectory.file("smoke/dps-manifest.schema.json")
val smokeCliJarSchema = registerCliJarSmokeTask(
    name = "smokeCliJarSchema",
    descriptionText = "Exports the bundled manifest JSON Schema from the standalone CLI jar.",
    "schema",
    "--output",
    smokeSchemaOutput.get().asFile.absolutePath,
)
smokeCliJarSchema.configure {
    outputs.file(smokeSchemaOutput)
    doFirst {
        smokeSchemaOutput.get().asFile.parentFile.mkdirs()
    }
}

val smokeDiffBefore = layout.buildDirectory.file("smoke/diff-before-report.json")
val smokeDiffAfter = layout.buildDirectory.file("smoke/diff-after-report.json")
val smokeCliJarDiff = registerCliJarSmokeTask(
    name = "smokeCliJarDiff",
    descriptionText = "Checks the standalone CLI jar can compare report snapshots.",
    "diff",
    "--snapshot",
    "--check",
    smokeDiffBefore.get().asFile.absolutePath,
    smokeDiffAfter.get().asFile.absolutePath,
)
smokeCliJarDiff.configure {
    doFirst {
        smokeDiffBefore.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"snapshot":{"scores":{"runs":{"#smoke":1}}}}""")
        }
        smokeDiffAfter.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"snapshot":{"scores":{"runs":{"#smoke":1}}}}""")
        }
    }
}

val smokeBenchmarkOutput = layout.buildDirectory.file("smoke/benchmark.json")
val smokeCliJarBenchmark = registerCliJarSmokeTask(
    name = "smokeCliJarBenchmark",
    descriptionText = "Runs the standalone CLI jar built-in benchmark smoke profile.",
    "benchmark",
    "--version",
    "26.2",
    "--scale",
    "3",
    "--json",
    "--output",
    smokeBenchmarkOutput.get().asFile.absolutePath,
)
smokeCliJarBenchmark.configure {
    outputs.file(smokeBenchmarkOutput)
    doFirst {
        smokeBenchmarkOutput.get().asFile.parentFile.mkdirs()
    }
}

val smokeCliJarExamples = registerCliJarSmokeTask(
    name = "smokeCliJarExamples",
    descriptionText = "Runs all example manifests through the standalone CLI jar.",
    "check",
    "--validate-schema",
    examplesDir.asFile.absolutePath,
)
smokeCliJarExamples.configure {
    inputs.dir(examplesDir)
}

val smokeCliJarReadmeLoot = registerCliJarSmokeTask(
    name = "smokeCliJarReadmeLoot",
    descriptionText = "Runs the README loot CLI example through the standalone jar.",
    "loot",
    "--pack",
    fullStackExamplePack.asFile.absolutePath,
    "--table",
    "demo:gift",
    "--context",
    "minecraft:advancement_reward",
    "--seed",
    "42",
)
smokeCliJarReadmeLoot.configure {
    inputs.dir(fullStackExamplePack)
    val output = ByteArrayOutputStream()
    standardOutput = output
    errorOutput = output
}

val smokeCliJarReadmeEvent = registerCliJarSmokeTask(
    name = "smokeCliJarReadmeEvent",
    descriptionText = "Runs the README player event CLI example through the standalone jar.",
    "event",
    "--pack",
    fullStackExamplePack.asFile.absolutePath,
    "player",
    "Steve",
    "item-used",
    "minecraft:carrot_on_a_stick",
)
smokeCliJarReadmeEvent.configure {
    inputs.dir(fullStackExamplePack)
    val output = ByteArrayOutputStream()
    standardOutput = output
    errorOutput = output
}

val smokeCliJarReadmeRunAssert = registerCliJarSmokeTask(
    name = "smokeCliJarReadmeRunAssert",
    descriptionText = "Runs the README run assertion shorthand example through the standalone jar.",
    "run",
    "--version",
    "26.2",
    "--command",
    "scoreboard objectives add runs dummy",
    "--command",
    "scoreboard players set #fixture runs 1",
    "--command",
    "data merge storage demo:env {ready:true}",
    "--command",
    "give Steve minecraft:stick 3",
    "--command",
    "summon minecraft:pig 0 0 0 {Tags:[\"fixture\"]}",
    "--command",
    "say generated ok",
    "--assert",
    "score:#fixture:runs=1",
    "--assert",
    "storage:demo:env:ready=true",
    "--assert",
    "item:Steve:minecraft:stick=3",
    "--assert",
    "entity:minecraft:pig@fixture=1",
    "--assert",
    "trace:scoreboard=2",
    "--assert",
    "output:generated ok",
)

val smokeCliJarRunLimits = registerCliJarSmokeTask(
    name = "smokeCliJarRunLimits",
    descriptionText = "Checks standalone CLI run limit options are accepted.",
    "run",
    "--version",
    "26.2",
    "--max-commands",
    "10",
    "--max-snapshot-bytes",
    "1000000",
    "--command",
    "say limit smoke",
)

tasks.register("smokeCliJar") {
    group = "verification"
    description = "Builds the standalone CLI jar and runs release smoke checks."
    dependsOn(
        smokeCliJarVersion,
        smokeCliJarVersionDocs,
        smokeCliJarCommandDocs,
        smokeCliJarResourceDocs,
        smokeCliJarResourcesLoaded,
        smokeCliJarSchema,
        smokeCliJarDiff,
        smokeCliJarBenchmark,
        smokeCliJarExamples,
        smokeCliJarReadmeLoot,
        smokeCliJarReadmeEvent,
        smokeCliJarReadmeRunAssert,
        smokeCliJarRunLimits,
    )
}

tasks.named("check") {
    dependsOn("smokeCliJar")
}
