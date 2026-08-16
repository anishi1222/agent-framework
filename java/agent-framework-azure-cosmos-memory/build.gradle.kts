import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure Cosmos DB NoSQL vector, full-text, and hybrid memory storage."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-azure-cosmos"))

    implementation(libs.azure.cosmos)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure Cosmos DB Memory")
            description.set(project.description)
        }
    }
}
