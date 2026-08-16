import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Valkey-backed bounded conversation history for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.valkey.glide.get()) {
        artifact {
            type = "jar"
        }
    }
    compileOnly(libs.spotbugs.annotations)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Valkey history")
            description.set(project.description)
        }
    }
}
