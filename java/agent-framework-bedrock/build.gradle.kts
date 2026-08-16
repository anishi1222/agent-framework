import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Amazon Bedrock Runtime Converse provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.aws.bedrock.runtime)
    implementation(libs.aws.netty.nio.client)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Amazon Bedrock provider")
            description.set(project.description)
        }
    }
}
