import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Purview policy evaluation middleware for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-azure-authentication"))

    implementation(libs.jackson.databind)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Purview integration")
            description.set(project.description)
        }
    }
}
