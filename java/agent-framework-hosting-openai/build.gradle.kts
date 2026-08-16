import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "OpenAI Responses-compatible HTTP and SSE hosting over generic Agent Framework hosting."

dependencies {
    api(project(":agent-framework-hosting"))
    api(project(":agent-framework-hosting-http"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework OpenAI Responses hosting")
            description.set(project.description)
        }
    }
}
