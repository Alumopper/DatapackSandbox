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
    mainClass.set("net.datapacksandbox.cli.MainKt")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a standalone CLI jar."
    archiveFileName.set("datapack-sandbox-cli.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "net.datapacksandbox.cli.MainKt"
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
