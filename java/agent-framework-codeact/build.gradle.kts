plugins {
    id("com.microsoft.agents.java-library")
}

description = "Approval-gated bounded CodeAct execution over the framework shell runtime."

dependencies {
    api(project(":agent-framework-tools-shell"))
}
