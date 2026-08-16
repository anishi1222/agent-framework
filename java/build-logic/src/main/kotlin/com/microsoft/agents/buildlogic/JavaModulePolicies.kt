// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

internal data class JavaModulePolicy(
    val allowedProjectDependencies: Set<String>,
    val expectedPackageRoot: String,
    val published: Boolean = true,
)

/** Single source of truth for Java module architecture and publication policy. */
internal object JavaModulePolicies {
    const val BOM_MODULE = "agent-framework-bom"

    const val TEST_SUPPORT_MODULE = "agent-framework-conformance"

    val policies =
        mapOf(
            "agent-framework-core" to
                JavaModulePolicy(emptySet(), "com.microsoft.agents.core"),
            "agent-framework-tools" to
                JavaModulePolicy(
                    setOf("agent-framework-core"),
                    "com.microsoft.agents.tools",
                ),
            "agent-framework-tools-shell" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.tools.shell",
                ),
            "agent-framework-agents" to
                JavaModulePolicy(
                    setOf("agent-framework-core", "agent-framework-tools"),
                    "com.microsoft.agents.agents",
                ),
            "agent-framework-harness" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.harness",
                ),
            "agent-framework-evaluation" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-workflows"),
                    "com.microsoft.agents.evaluation",
                ),
            "agent-framework-workflows" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.workflows",
                ),
            "agent-framework-workflows-declarative" to
                JavaModulePolicy(
                    setOf("agent-framework-workflows"),
                    "com.microsoft.agents.workflows.declarative",
                ),
            "agent-framework-orchestrations" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.orchestrations",
                ),
            "agent-framework-observability" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-workflows"),
                    "com.microsoft.agents.observability",
                ),
            "agent-framework-reactor-adapter" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.adapters.reactor",
                ),
            "agent-framework-mcp" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.protocols.mcp",
                ),
            "agent-framework-hosting-mcp" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-mcp"),
                    "com.microsoft.agents.hosting.mcp",
                ),
            "agent-framework-a2a" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.protocols.a2a",
                ),
            "agent-framework-hosting-a2a" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-a2a",
                        "agent-framework-agents",
                        "agent-framework-workflows",
                    ),
                    "com.microsoft.agents.hosting.a2a",
                ),
            "agent-framework-agui" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.protocols.agui",
                ),
            "agent-framework-hosting" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-agents",
                        "agent-framework-orchestrations",
                        "agent-framework-workflows",
                    ),
                    "com.microsoft.agents.hosting",
                ),
            "agent-framework-hosting-http" to
                JavaModulePolicy(
                    setOf("agent-framework-hosting"),
                    "com.microsoft.agents.hosting.http",
                ),
            "agent-framework-hosting-openai" to
                JavaModulePolicy(
                    setOf("agent-framework-hosting", "agent-framework-hosting-http"),
                    "com.microsoft.agents.hosting.openai",
                ),
            "agent-framework-hosting-spring" to
                JavaModulePolicy(
                    setOf("agent-framework-hosting", "agent-framework-hosting-http"),
                    "com.microsoft.agents.hosting.spring",
                ),
            "agent-framework-hosting-telegram" to
                JavaModulePolicy(
                    setOf("agent-framework-hosting"),
                    "com.microsoft.agents.hosting.telegram",
                ),
            "agent-framework-hosting-agui" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-agui",
                        "agent-framework-hosting",
                        "agent-framework-hosting-http",
                        "agent-framework-orchestrations",
                    ),
                    "com.microsoft.agents.hosting.agui",
                ),
            "agent-framework-hosting-agui-spring" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-hosting-agui",
                        "agent-framework-hosting-http",
                        "agent-framework-hosting-spring",
                    ),
                    "com.microsoft.agents.hosting.agui.spring",
                ),
            "agent-framework-openai" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.openai",
                ),
            "agent-framework-azure-openai" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-openai"),
                    "com.microsoft.agents.providers.azureopenai",
                ),
            "agent-framework-azure-authentication" to
                JavaModulePolicy(
                    setOf("agent-framework-core"),
                    "com.microsoft.agents.azure",
                ),
            "agent-framework-azure-ai-persistent" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-azure-authentication"),
                    "com.microsoft.agents.providers.azureaipersistent",
                ),
            "agent-framework-foundry" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-openai"),
                    "com.microsoft.agents.providers.foundry",
                ),
            "agent-framework-foundry-hosting" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-azure-ai-persistent",
                        "agent-framework-hosting",
                    ),
                    "com.microsoft.agents.hosting.foundry",
                ),
            "agent-framework-foundry-evaluations" to
                JavaModulePolicy(
                    setOf("agent-framework-azure-authentication", "agent-framework-core"),
                    "com.microsoft.agents.evaluation.foundry",
                ),
            "agent-framework-azure-content-understanding" to
                JavaModulePolicy(
                    setOf("agent-framework-azure-authentication", "agent-framework-core"),
                    "com.microsoft.agents.providers.azurecontentunderstanding",
                ),
            "agent-framework-azure-cosmos" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-agents",
                        "agent-framework-azure-authentication",
                        "agent-framework-workflows",
                    ),
                    "com.microsoft.agents.storage.cosmos",
                ),
            "agent-framework-azure-cosmos-memory" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-azure-cosmos"),
                    "com.microsoft.agents.storage.cosmosmemory",
                ),
            "agent-framework-azure-ai-search" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-azure-authentication"),
                    "com.microsoft.agents.storage.azureaisearch",
                ),
            "agent-framework-valkey" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.storage.valkey",
                ),
            "agent-framework-mem0" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.memory.mem0",
                ),
            "agent-framework-purview" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-azure-authentication"),
                    "com.microsoft.agents.purview",
                ),
            "agent-framework-anthropic" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.anthropic",
                ),
            "agent-framework-bedrock" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.bedrock",
                ),
            "agent-framework-chatkit" to
                JavaModulePolicy(
                    setOf("agent-framework-core"),
                    "com.microsoft.agents.chatkit",
                ),
            "agent-framework-gemini" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.gemini",
                ),
            "agent-framework-mistral" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.mistral",
                ),
            "agent-framework-ollama" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.ollama",
                ),
            "agent-framework-foundry-local" to
                JavaModulePolicy(
                    setOf("agent-framework-agents", "agent-framework-mistral"),
                    "com.microsoft.agents.providers.foundrylocal",
                ),
            "agent-framework-github-copilot" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.githubcopilot",
                ),
            "agent-framework-copilotstudio" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.providers.copilotstudio",
                ),
            "agent-framework-codeact" to
                JavaModulePolicy(
                    setOf("agent-framework-tools-shell"),
                    "com.microsoft.agents.codeact",
                ),
            "agent-framework-declarative" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.declarative",
                ),
            "agent-framework-devui" to
                JavaModulePolicy(
                    setOf("agent-framework-hosting", "agent-framework-hosting-http"),
                    "com.microsoft.agents.devui",
                ),
            "samples" to
                JavaModulePolicy(
                    setOf(
                        "agent-framework-agents",
                        "agent-framework-harness",
                        "agent-framework-orchestrations",
                        "agent-framework-workflows",
                    ),
                    "com.microsoft.agents.samples",
                    published = false,
                ),
            TEST_SUPPORT_MODULE to
                JavaModulePolicy(
                    emptySet(),
                    "com.microsoft.agents.conformance",
                    published = false,
                ),
        )

    fun publishedModules(root: org.gradle.api.Project): Set<String> =
        root.subprojects
            .map { it.name }
            .filterTo(sortedSetOf()) { moduleName -> policies[moduleName]?.published == true }
}
