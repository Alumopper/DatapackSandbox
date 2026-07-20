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
include(":core", ":renderer", ":testkit", ":manifest", ":cli", ":playground-api", ":schema-generator")
