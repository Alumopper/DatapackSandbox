pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net")
    }
}

rootProject.name = "datapack-sandbox"
include(
    ":runtime-engine",
    ":renderer-engine",
    ":browser-runtime",
    ":core",
    ":renderer",
    ":testkit",
    ":manifest",
    ":cli",
    ":schema-generator",
)
