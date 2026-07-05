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

val smokeCliJarExamples = registerCliJarSmokeTask(
    name = "smokeCliJarExamples",
    descriptionText = "Runs all example manifests through the standalone CLI jar.",
    "check",
    rootProject.layout.projectDirectory.dir("examples").asFile.absolutePath,
)
smokeCliJarExamples.configure {
    inputs.dir(rootProject.layout.projectDirectory.dir("examples"))
}

tasks.register("smokeCliJar") {
    group = "verification"
    description = "Builds the standalone CLI jar and runs release smoke checks."
    dependsOn(smokeCliJarVersion, smokeCliJarSchema, smokeCliJarExamples)
}

tasks.named("check") {
    dependsOn("smokeCliJar")
}
