import org.teavm.gradle.api.JSModuleType
import org.teavm.gradle.api.OptimizationLevel

plugins {
    java
    id("org.teavm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(teavm.libs.jso)
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.test {
    useJUnitPlatform()
}

teavm {
    all {
        mainClass = "moe.afox.dpsandbox.browser.core.BrowserCoreModule"
        fastGlobalAnalysis = true
    }
    js {
        moduleType = JSModuleType.ES2015
        targetFileName = "datapack-sandbox-core.js"
        outputDir = layout.buildDirectory.dir("dist").get()
        obfuscated = true
        sourceMap = false
        strict = true
        optimization = OptimizationLevel.AGGRESSIVE
    }
}
