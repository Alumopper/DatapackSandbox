plugins {
    kotlin("jvm")
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
    api(project(":core"))
    implementation(project(":renderer-engine"))
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}

tasks.register<JavaExec>("renderBenchmark") {
    group = "verification"
    description = "Writes a renderer performance baseline report for scene size, resolution, and entity count."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("moe.afox.dpsandbox.render.RenderBenchmarkKt")
    val report = layout.buildDirectory.file("reports/render-benchmark.json")
    args(report.get().asFile.absolutePath)
    outputs.file(report)
}
