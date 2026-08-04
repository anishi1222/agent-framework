plugins {
    id("com.microsoft.agents.java-library")
}

description = "Provider-neutral chat client, agent, session, middleware, context, and history runtime."

dependencies {
    api(project(":agent-framework-core"))
    api(project(":agent-framework-tools"))
}
