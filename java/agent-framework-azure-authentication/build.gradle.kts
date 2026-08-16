import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Framework-owned Azure authentication contracts and credential factories."

dependencies {
    api(project(":agent-framework-core"))

    implementation(libs.azure.identity)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure authentication")
            description.set(project.description)
        }
    }
}
