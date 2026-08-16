plugins {
    id("com.microsoft.agents.java-library")
}

description = "Strict YAML and JSON prompt-agent definitions and factories."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.jackson.databind)
    implementation("org.yaml:snakeyaml:2.5")
}
