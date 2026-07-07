import java.net.URI
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.0" apply false
}

allprojects {
    group = "moe.afox.dpsandbox"
    version = "0.5.337-SNAPSHOT"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val vanillaMcdocArchive = layout.buildDirectory.file("vanilla-mcdoc/vanilla-mcdoc-main.zip")
val vanillaMcdocSourceDir = layout.buildDirectory.dir("vanilla-mcdoc/source")
val vanillaMcdocIndexFile = layout.buildDirectory.file("generated/vanilla-mcdoc/index.json")
val vanillaNbtSchemaResourceDir = layout.buildDirectory.dir("generated/vanilla-mcdoc/resources")
val vanillaNbtSchemaFile = vanillaNbtSchemaResourceDir.map { it.file("vanilla-nbt-schemas.json") }
val nodeExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "node.exe" else "node"
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

tasks.register("fetchVanillaMcdoc") {
    group = "vanilla data"
    description = "Downloads the SpyglassMC vanilla-mcdoc repository snapshot for local schema/resource extraction."
    outputs.file(vanillaMcdocArchive)
    outputs.dir(vanillaMcdocSourceDir)

    doLast {
        val archive = vanillaMcdocArchive.get().asFile
        val sourceDir = vanillaMcdocSourceDir.get().asFile
        archive.parentFile.mkdirs()
        sourceDir.mkdirs()

        if (!archive.isFile) {
            try {
                URI("https://github.com/SpyglassMC/vanilla-mcdoc/archive/refs/heads/main.zip")
                    .toURL()
                    .openStream()
                    .use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
            } catch (error: Exception) {
                logger.warn("Could not download vanilla-mcdoc; generating fallback versioned NBT schema from empty input: ${error.message}")
                archive.delete()
                ZipOutputStream(archive.outputStream()).use {}
            }
        }

        sourceDir.deleteRecursively()
        sourceDir.mkdirs()
        if (!archive.isFile) return@doLast

        ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.name.endsWith(".mcdoc")) return@forEach
                val relative = entry.name.substringAfter('/', missingDelimiterValue = entry.name)
                val target = sourceDir.resolve(relative).normalize()
                require(target.toPath().startsWith(sourceDir.toPath())) { "Refusing to unzip outside target directory: ${entry.name}" }
                target.parentFile.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
    }
}

tasks.register("generateVanillaMcdocIndex") {
    group = "vanilla data"
    description = "Generates a lightweight lexical index from downloaded mcdoc files for inspection. Runtime NBT validation uses generateVanillaNbtSchemas."
    dependsOn("fetchVanillaMcdoc")
    outputs.file(vanillaMcdocIndexFile)

    doLast {
        val sourceDir = vanillaMcdocSourceDir.get().asFile
        val output = vanillaMcdocIndexFile.get().asFile
        val files = sourceDir.walkTopDown().filter { it.isFile && it.extension == "mcdoc" }.toList()
        val resourceLocationPattern = Regex("""minecraft:[a-z0-9_./-]+""")
        val declarationPattern = Regex("""(?m)^\s*(struct|enum|dispatch|type)\s+([A-Za-z0-9_.$]+)""")
        val resourceLocations = sortedSetOf<String>()
        val declarations = sortedSetOf<String>()

        files.forEach { file ->
            val text = file.readText()
            resourceLocationPattern.findAll(text).forEach { resourceLocations += it.value }
            declarationPattern.findAll(text).forEach { declarations += "${it.groupValues[1]} ${it.groupValues[2]}" }
        }

        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("{")
                appendLine("""  "source": "https://github.com/SpyglassMC/vanilla-mcdoc",""")
                appendLine("""  "format": "mcdoc-lexical-index-v1",""")
                appendLine("""  "fileCount": ${files.size},""")
                appendLine("""  "resourceLocations": [""")
                append(resourceLocations.joinToString(",\n") { """    "${it.jsonEscaped()}"""" })
                appendLine()
                appendLine("  ],")
                appendLine("""  "declarations": [""")
                append(declarations.joinToString(",\n") { """    "${it.jsonEscaped()}"""" })
                appendLine()
                appendLine("  ]")
                appendLine("}")
            },
        )
        println("Generated ${output.toPath().toAbsolutePath().normalize()} from ${files.size} mcdoc files")
    }
}

val npmInstallMcdocTools = tasks.register<Exec>("npmInstallMcdocTools") {
    group = "vanilla data"
    description = "Installs the official Spyglass mcdoc parser used by vanilla schema generation."
    inputs.file(layout.projectDirectory.file("package.json"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@spyglassmc/mcdoc"))
    commandLine(npmExecutable, "install", "--no-audit", "--no-fund")
}

tasks.register<Exec>("generateVanillaNbtSchemas") {
    group = "vanilla data"
    description = "Uses the official Spyglass mcdoc parser to generate lightweight NBT schema resources for the runtime."
    dependsOn("fetchVanillaMcdoc", npmInstallMcdocTools)
    inputs.dir(vanillaMcdocSourceDir)
    inputs.file(layout.projectDirectory.file("tools/generate-vanilla-nbt-schemas.mjs"))
    inputs.file(layout.projectDirectory.file("package.json"))
    outputs.file(vanillaNbtSchemaFile)
    commandLine(
        nodeExecutable,
        layout.projectDirectory.file("tools/generate-vanilla-nbt-schemas.mjs").asFile.absolutePath,
        vanillaMcdocSourceDir.get().asFile.absolutePath,
        vanillaNbtSchemaFile.get().asFile.absolutePath,
    )
}

project(":core") {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        the<SourceSetContainer>().named("main") {
            resources.srcDir(vanillaNbtSchemaResourceDir)
        }
        tasks.named("processResources") {
            dependsOn(rootProject.tasks.named("generateVanillaNbtSchemas"))
        }
    }
}

fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
