import java.net.URI
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    kotlin("jvm") version "2.4.0" apply false
}

allprojects {
    group = "moe.afox.dpsandbox"
    version = "1.0.1"
}

val mavenRepositoryBaseUrl = providers
    .gradleProperty("nexusBaseUrl")
    .orElse("https://nexus.mcfpp.top")
val mavenReleasesRepository = providers
    .gradleProperty("nexusReleasesRepository")
    .orElse("maven-releases")
val mavenSnapshotsRepository = providers
    .gradleProperty("nexusSnapshotsRepository")
    .orElse("maven-snapshots")
val mavenRepositoryUsername = providers
    .environmentVariable("MAVEN_USERNAME")
    .orElse(providers.environmentVariable("MAVEN_USER"))
    .orElse(providers.environmentVariable("NEXUS_USERNAME"))
    .orElse(providers.environmentVariable("NEXUS_USER"))
    .orElse(providers.gradleProperty("nexusUsername"))
val mavenRepositoryPassword = providers
    .environmentVariable("MAVEN_PASSWORD")
    .orElse(providers.environmentVariable("MAVEN_PASS"))
    .orElse(providers.environmentVariable("NEXUS_PASSWORD"))
    .orElse(providers.environmentVariable("NEXUS_PASS"))
    .orElse(providers.gradleProperty("nexusPassword"))

subprojects {
    apply(plugin = "maven-publish")

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.name

                    pom {
                        name.set("Datapack Sandbox ${project.name}")
                        description.set("Datapack Sandbox ${project.name} module")
                    }
                }
            }

            repositories {
                maven {
                    name = "mcfpp"
                    val repositoryName = if (project.version.toString().endsWith("-SNAPSHOT")) {
                        mavenSnapshotsRepository.get()
                    } else {
                        mavenReleasesRepository.get()
                    }
                    url = uri(
                        "${mavenRepositoryBaseUrl.get().trimEnd('/')}/repository/$repositoryName/",
                    )

                    credentials(PasswordCredentials::class) {
                        username = mavenRepositoryUsername.orNull
                        password = mavenRepositoryPassword.orNull
                    }
                }
            }
        }

        tasks.withType<PublishToMavenRepository>().configureEach {
            doFirst {
                require(!mavenRepositoryUsername.orNull.isNullOrBlank()) {
                    "Missing Maven repository username. Set MAVEN_USERNAME, MAVEN_USER, NEXUS_USERNAME, NEXUS_USER, or nexusUsername."
                }
                require(!mavenRepositoryPassword.orNull.isNullOrBlank()) {
                    "Missing Maven repository password. Set MAVEN_PASSWORD, MAVEN_PASS, NEXUS_PASSWORD, NEXUS_PASS, or nexusPassword."
                }
            }
        }
    }
}

val releaseModules = listOf("core", "cli")

tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Verifies release jars and Maven publication metadata without publishing to a remote repository."
    dependsOn(
        releaseModules.flatMap { module ->
            listOf(
                ":$module:jar",
                ":$module:sourcesJar",
                ":$module:javadocJar",
                ":$module:generatePomFileForMavenJavaPublication",
            )
        } + ":cli:fatJar",
    )

    doLast {
        val releaseVersion = project.version.toString()
        require(!releaseVersion.endsWith("-SNAPSHOT")) {
            "Release version must not be a SNAPSHOT: $releaseVersion"
        }
        require(Regex("""\d+\.\d+\.\d+""").matches(releaseVersion)) {
            "Release version must use semantic x.y.z format: $releaseVersion"
        }

        releaseModules.forEach { module ->
            val moduleProject = project(":$module")
            listOf(
                "$module-$releaseVersion.jar",
                "$module-$releaseVersion-sources.jar",
                "$module-$releaseVersion-javadoc.jar",
            ).forEach { artifactName ->
                val artifact = moduleProject.layout.buildDirectory.file("libs/$artifactName").get().asFile
                require(artifact.isFile && artifact.length() > 0L) {
                    "Missing or empty release artifact: ${artifact.absolutePath}"
                }
            }

            val pom = moduleProject.layout.buildDirectory
                .file("publications/mavenJava/pom-default.xml")
                .get()
                .asFile
            require(pom.isFile && pom.length() > 0L) {
                "Missing generated Maven POM for :$module"
            }
            val pomText = pom.readText()
            require("<groupId>moe.afox.dpsandbox</groupId>" in pomText) {
                "Generated POM for :$module is missing the project groupId"
            }
            require("<artifactId>$module</artifactId>" in pomText) {
                "Generated POM for :$module is missing artifactId $module"
            }
            require("<version>$releaseVersion</version>" in pomText) {
                "Generated POM for :$module is missing version $releaseVersion"
            }
        }

        val standalone = project(":cli").layout.buildDirectory
            .file("libs/datapack-sandbox-cli.jar")
            .get()
            .asFile
        require(standalone.isFile && standalone.length() > 0L) {
            "Missing standalone CLI jar: ${standalone.absolutePath}"
        }
    }
}

tasks.register("releaseCheck") {
    group = "verification"
    description = "Runs the full 1.0 release gate: unit checks, standalone CLI smoke, and publication artifact checks."
    dependsOn(":core:check", ":cli:check", "verifyReleaseArtifacts")
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
        tasks.named("sourcesJar") {
            dependsOn(rootProject.tasks.named("generateVanillaNbtSchemas"))
        }
    }
}

fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
