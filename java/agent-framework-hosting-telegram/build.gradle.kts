import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Opt-in Telegram Bot API webhook hosting adapter for Agent Framework."

dependencies {
    api(project(":agent-framework-hosting"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Telegram hosting")
            description.set(project.description)
        }
    }
}
