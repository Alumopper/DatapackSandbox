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
    api("com.google.code.gson:gson:2.13.2")
    implementation("com.mojang:brigadier:1.3.10")
    implementation("org.lz4:lz4-java:1.8.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}
