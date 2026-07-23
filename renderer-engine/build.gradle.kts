plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    js {
        browser()
    }
    jvmToolchain(25)

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named("check") {
    setDependsOn(listOf("jvmTest", "compileTestKotlinJs"))
}
