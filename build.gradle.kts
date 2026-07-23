import java.util.zip.ZipFile
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("multiplatform") version "2.4.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
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

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

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

val releaseModules = listOf("core", "renderer", "testkit", "manifest", "cli")
val browserModules = listOf("runtime-engine", "renderer-engine", "browser-runtime")

fun publicApiDump(module: String): String {
    val jar = project(":$module").layout.buildDirectory.file("libs/$module-${project.version}.jar").get().asFile
    require(jar.isFile) { "Missing module jar for API dump: ${jar.absolutePath}" }
    val classes = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()
    }
    val javap = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").lowercase().contains("windows")) "bin/javap.exe" else "bin/javap",
    )
    require(javap.isFile) { "JDK javap executable is missing: ${javap.absolutePath}" }
    val output = buildString {
        appendLine("# Public JVM API for moe.afox.dpsandbox:$module:${project.version}")
        classes.chunked(100).forEach { chunk ->
            val process = ProcessBuilder(listOf(javap.absolutePath, "-public", "-classpath", jar.absolutePath) + chunk)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            require(process.waitFor() == 0) { "javap failed for :$module:\n$text" }
            text.lineSequence()
                .filterNot { it.startsWith("Compiled from ") }
                .forEach { line -> appendLine(line.trimEnd()) }
        }
    }
    return output.trimEnd() + "\n"
}

tasks.register("apiDump") {
    group = "verification"
    description = "Updates checked-in JDK 25 public API baselines for all published modules."
    dependsOn(releaseModules.map { ":$it:jar" })
    doLast {
        val directory = layout.projectDirectory.dir("api").asFile
        directory.mkdirs()
        releaseModules.forEach { module -> directory.resolve("$module.api").writeText(publicApiDump(module)) }
    }
}

val apiCheck = tasks.register("apiCheck") {
    group = "verification"
    description = "Checks published JVM APIs against the checked-in JDK 25 javap baselines."
    dependsOn(releaseModules.map { ":$it:jar" })
    inputs.files(releaseModules.map { layout.projectDirectory.file("api/$it.api") })
    doLast {
        releaseModules.forEach { module ->
            val baseline = layout.projectDirectory.file("api/$module.api").asFile
            require(baseline.isFile) { "Missing API baseline ${baseline.absolutePath}; run ./gradlew apiDump" }
            val actual = publicApiDump(module)
            require(baseline.readText() == actual) {
                "Public API for :$module changed. Review compatibility and run ./gradlew apiDump when intentional."
            }
        }
    }
}

val architectureCheck = tasks.register("architectureCheck") {
    group = "verification"
    description = "Prevents Kotlin source files from growing back into unreviewable God files."
    val sourceRoots = (releaseModules + browserModules).map { module -> project(":$module").layout.projectDirectory.dir("src") }
    inputs.files(sourceRoots)
    doLast {
        val maximumLines = 3_200
        val oversized =
            sourceRoots
                .flatMap { root ->
                    root.asFile
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .map { file -> file to file.readLines().size }
                        .filter { (_, lines) -> lines > maximumLines }
                        .toList()
                }.sortedByDescending { (_, lines) -> lines }
        require(oversized.isEmpty()) {
            buildString {
                appendLine("Kotlin source files must stay at or below $maximumLines lines; split by responsibility:")
                oversized.forEach { (file, lines) ->
                    appendLine("- ${file.relativeTo(rootDir)}: $lines lines")
                }
            }
        }
    }
}

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
    description = "Runs the full release gate: module checks, reproducibility, API/architecture, CLI smoke, and artifacts."
    dependsOn(
        ":core:check",
        ":renderer:check",
        ":testkit:check",
        ":manifest:check",
        ":cli:check",
        ":runtime-engine:check",
        ":renderer-engine:check",
        ":browser-runtime:check",
        ":schema-generator:check",
        apiCheck,
        architectureCheck,
        "verifyReleaseArtifacts",
    )
}

val prepareJupyterKernel = tasks.register<Copy>("prepareJupyterKernel") {
    group = "build"
    description = "Copies the standalone CLI jar into the Python Jupyter Kernel package."
    dependsOn(":cli:fatJar")
    from(project(":cli").layout.buildDirectory.file("libs/datapack-sandbox-cli.jar"))
    into(layout.projectDirectory.dir("jupyter/src/datapack_sandbox_kernel/resources"))
}

tasks.register<Exec>("jupyterKernelTest") {
    group = "verification"
    description = "Runs the Python unit and real Jupyter Kernel integration tests."
    dependsOn(prepareJupyterKernel)
    val python = providers.environmentVariable("PYTHON").orElse("python")
    val packageRoot = layout.projectDirectory.dir("jupyter/src").asFile.absolutePath
    val cliJar = project(":cli").layout.buildDirectory.file("libs/datapack-sandbox-cli.jar")
    environment("PYTHONPATH", packageRoot)
    environment("DPS_CLI_JAR", cliJar.get().asFile.absolutePath)
    commandLine(
        python.get(),
        "-m",
        "unittest",
        "discover",
        "-s",
        layout.projectDirectory.dir("jupyter/tests").asFile.absolutePath,
        "-p",
        "test*.py",
        "-v",
    )
}

tasks.register<Exec>("jupyterKernelPackage") {
    group = "build"
    description = "Builds and verifies offline Jupyter Kernel wheel and sdist artifacts."
    dependsOn(prepareJupyterKernel)
    val python = providers.environmentVariable("PYTHON").orElse("python")
    commandLine(
        python.get(),
        layout.projectDirectory.file("jupyter/scripts/build_offline.py").asFile.absolutePath,
    )
}
