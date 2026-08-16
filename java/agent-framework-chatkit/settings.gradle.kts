pluginManagement {
    includeBuild("../build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "agent-framework-chatkit"

include(
    "agent-framework-core",
    "agent-framework-conformance",
)

project(":agent-framework-core").projectDir = file("../agent-framework-core")
project(":agent-framework-conformance").projectDir = file("../agent-framework-conformance")
