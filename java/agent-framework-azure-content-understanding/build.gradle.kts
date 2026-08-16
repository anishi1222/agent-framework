import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure AI Content Understanding integration for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-azure-authentication"))
    api(project(":agent-framework-core"))

    implementation(libs.azure.ai.content.understanding)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure AI Content Understanding")
            description.set(project.description)
        }
    }
}
