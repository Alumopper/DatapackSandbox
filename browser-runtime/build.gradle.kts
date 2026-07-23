import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        browser()
        binaries.library()
        compilerOptions {
            moduleKind.set(JsModuleKind.MODULE_ES)
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":runtime-engine"))
            implementation(project(":renderer-engine"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named("check") {
    setDependsOn(listOf("compileTestKotlinJs", "jsBrowserProductionLibraryDistribution"))
}
