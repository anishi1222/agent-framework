plugins {
    id("com.microsoft.agents.java-library")
}

description = "Autonomous harness loop, file, todo, mode, and background-agent providers."

dependencies {
    api(project(":agent-framework-agents"))
    implementation(libs.re2j)

    testImplementation(project(":agent-framework-conformance"))
}
