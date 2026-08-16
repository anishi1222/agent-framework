import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure Cosmos DB NoSQL session, history, and workflow checkpoint storage."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-azure-authentication"))
    api(project(":agent-framework-workflows"))

    implementation(libs.azure.cosmos)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure Cosmos DB")
            description.set(project.description)
        }
    }
}
