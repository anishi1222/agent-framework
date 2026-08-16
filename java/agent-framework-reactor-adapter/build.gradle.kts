plugins {
    id("com.microsoft.agents.java-library")
}

description = "Optional Reactor adapters for Agent Framework Java asynchronous and streaming contracts."

dependencies {
    api(project(":agent-framework-agents"))
    api(libs.reactor.core)
}
