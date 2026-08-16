plugins {
    id("com.microsoft.agents.java-library")
}

description = "Approval-gated local and container shell execution with bounded output."

dependencies {
    api(project(":agent-framework-agents"))

    testImplementation(project(":agent-framework-conformance"))
}
