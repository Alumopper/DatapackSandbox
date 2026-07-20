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
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-cio:3.5.1")
    implementation("io.ktor:ktor-server-websockets:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-content-negotiation:3.5.1")
    testImplementation("io.ktor:ktor-client-websockets:3.5.1")
    testImplementation("io.ktor:ktor-server-test-host:3.5.1")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}

application {
    mainClass.set("moe.afox.dpsandbox.playground.MainKt")
}

tasks.named<Test>("test") {
    dependsOn(":cli:fatJar")
    systemProperty(
        "dps.playground.testCliJar",
        project(":cli")
            .layout.buildDirectory
            .file("libs/datapack-sandbox-cli.jar")
            .get()
            .asFile
            .absolutePath,
    )
}

tasks.named("check") {
    dependsOn("installDist")
}
