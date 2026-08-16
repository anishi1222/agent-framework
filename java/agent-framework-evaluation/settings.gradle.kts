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

rootProject.name = "agent-framework-evaluation"

include(
    "agent-framework-core",
    "agent-framework-tools",
    "agent-framework-agents",
    "agent-framework-workflows",
    "agent-framework-conformance",
)

project(":agent-framework-core").projectDir = file("../agent-framework-core")
project(":agent-framework-tools").projectDir = file("../agent-framework-tools")
project(":agent-framework-agents").projectDir = file("../agent-framework-agents")
project(":agent-framework-workflows").projectDir = file("../agent-framework-workflows")
project(":agent-framework-conformance").projectDir = file("../agent-framework-conformance")
