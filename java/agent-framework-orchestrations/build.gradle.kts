plugins {
    id("com.microsoft.agents.java-library")
}

description = "Higher-level sequential, concurrent, handoff, group-chat, and Magentic orchestrations."

dependencies {
    api(project(":agent-framework-agents"))
}
