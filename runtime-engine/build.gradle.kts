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

// Browser behavior is exercised through the packaged module Worker in
// Playwright. Keep Gradle's cross-compiled tests as a compile gate and run the
// same common tests on the JVM without requiring a system Chrome installation.
tasks.named("check") {
    setDependsOn(listOf("jvmTest", "compileTestKotlinJs"))
}
