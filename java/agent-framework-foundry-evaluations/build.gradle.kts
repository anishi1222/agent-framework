import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Foundry cloud evaluation and project discovery integration."

dependencies {
    api(project(":agent-framework-azure-authentication"))
    api(project(":agent-framework-core"))

    implementation(libs.azure.ai.projects)
    implementation(libs.jackson.databind)
    implementation(libs.openai.java)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Foundry evaluations")
            description.set(project.description)
        }
    }
}
