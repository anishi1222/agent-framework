plugins {
    id("com.microsoft.agents.java-library")
}

description = "Strict YAML and JSON workflow definitions built by the production workflow runtime."

dependencies {
    api(project(":agent-framework-workflows"))

    implementation(libs.jackson.databind)
    implementation("org.yaml:snakeyaml:2.5")
}
