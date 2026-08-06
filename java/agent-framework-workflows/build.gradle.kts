plugins {
    id("com.microsoft.agents.java-library")
}

description = "Workflow graph, execution, events, state, and checkpoint runtime."

dependencies {
    api(project(":agent-framework-agents"))

    testImplementation(project(":agent-framework-conformance"))
}
