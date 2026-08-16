import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure OpenAI Responses and embeddings provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-openai"))

    implementation(libs.azure.ai.openai)
    implementation(libs.azure.identity)
    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

val liveTestSourceSet = sourceSets.create("liveTest")
liveTestSourceSet.compileClasspath += sourceSets.main.get().output
liveTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations[liveTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[liveTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs opt-in live Azure OpenAI integration tests."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    doFirst {
        val endpoint = System.getenv("AZURE_OPENAI_ENDPOINT")
        val deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT")
        val apiKey = System.getenv("AZURE_OPENAI_API_KEY")
        if (endpoint.isNullOrBlank() || deployment.isNullOrBlank() || apiKey.isNullOrBlank()) {
            throw GradleException(
                "liveTest requires non-blank AZURE_OPENAI_ENDPOINT, " +
                    "AZURE_OPENAI_DEPLOYMENT, and AZURE_OPENAI_API_KEY environment variables.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(liveTestSourceSet.compileJavaTaskName)
}

tasks.named("jacocoTestReport") {
    setDependsOn(listOf(tasks.named("test")))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure OpenAI provider")
            description.set(project.description)
        }
    }
}
