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
    "agent-framework-tools-shell",
    "agent-framework-agents",
    "agent-framework-harness",
    "agent-framework-workflows",
    "agent-framework-workflows-declarative",
    "agent-framework-orchestrations",
    "agent-framework-observability",
    "agent-framework-reactor-adapter",
    "agent-framework-mcp",
    "agent-framework-hosting-mcp",
    "agent-framework-a2a",
    "agent-framework-hosting-a2a",
    "agent-framework-agui",
    "agent-framework-hosting",
    "agent-framework-hosting-http",
    "agent-framework-hosting-openai",
    "agent-framework-hosting-spring",
    "agent-framework-hosting-telegram",
    "agent-framework-hosting-agui",
    "agent-framework-hosting-agui-spring",
    "agent-framework-openai",
    "agent-framework-azure-openai",
    "agent-framework-azure-authentication",
    "agent-framework-azure-ai-persistent",
    "agent-framework-evaluation",
    "agent-framework-foundry",
    "agent-framework-foundry-hosting",
    "agent-framework-foundry-evaluations",
    "agent-framework-azure-content-understanding",
    "agent-framework-azure-cosmos",
    "agent-framework-azure-cosmos-memory",
    "agent-framework-azure-ai-search",
    "agent-framework-valkey",
    "agent-framework-mem0",
    "agent-framework-purview",
    "agent-framework-anthropic",
    "agent-framework-bedrock",
    "agent-framework-gemini",
    "agent-framework-mistral",
    "agent-framework-ollama",
    "agent-framework-foundry-local",
    "agent-framework-codeact",
    "agent-framework-declarative",
    "agent-framework-devui",
    "agent-framework-github-copilot",
    "agent-framework-chatkit",
    "agent-framework-copilotstudio",
    "agent-framework-conformance",
    "agent-framework-bom",
    "samples",
)
