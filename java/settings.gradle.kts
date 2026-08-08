pluginManagement {
    includeBuild("build-logic")
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
}

rootProject.name = "agent-framework-java"

include(
    "agent-framework-core",
    "agent-framework-tools",
    "agent-framework-agents",
    "agent-framework-workflows",
    "agent-framework-orchestrations",
    "agent-framework-observability",
    "agent-framework-reactor-adapter",
    "agent-framework-mcp",
    "agent-framework-hosting-mcp",
    "agent-framework-a2a",
    "agent-framework-hosting-a2a",
    "agent-framework-openai",
    "agent-framework-azure-openai",
    "agent-framework-foundry",
    "agent-framework-conformance",
    "agent-framework-bom",
)
