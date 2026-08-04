plugins {
    id("com.microsoft.agents.java-library")
}

description = "Optional observability decorators for Agent Framework Java."

dependencies {
    api(project(":agent-framework-agents"))
}
