plugins {
    id("com.microsoft.agents.java-platform")
}

description = "Dependency constraints for the Agent Framework Java release train."

dependencies {
    constraints {
        api(project(":agent-framework-core"))
        api(project(":agent-framework-tools"))
        api(project(":agent-framework-agents"))
        api(project(":agent-framework-workflows"))
        api(project(":agent-framework-orchestrations"))
        api(project(":agent-framework-observability"))
        api(project(":agent-framework-reactor-adapter"))
        api(project(":agent-framework-openai"))
        api(project(":agent-framework-azure-openai"))
        api(project(":agent-framework-foundry"))
    }
}
