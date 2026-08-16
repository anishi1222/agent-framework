import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Foundry provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-openai"))

    implementation(libs.azure.ai.agents)
    implementation(libs.azure.ai.projects)
    implementation(libs.azure.identity)
    implementation(libs.jackson.databind)
    implementation(libs.openai.java)

    testImplementation(project(":agent-framework-conformance"))
}

val testedOpenAIJavaVersion = libs.versions.openai.java.get()
val foundryRuntimeClasspath = configurations.named("runtimeClasspath")
val checkOpenAIJavaConvergence = tasks.register("checkOpenAIJavaConvergence") {
    group = "verification"
    description =
        "Fails unless Foundry runtimeClasspath resolves the tested OpenAI Java SDK version."
    inputs.property("testedOpenAIJavaVersion", testedOpenAIJavaVersion)
    doLast {
        val resolvedVersions =
            foundryRuntimeClasspath
                .get()
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { it.moduleVersion }
                .filter { it.group == "com.openai" && it.name == "openai-java" }
                .map { it.version }
                .toSet()
        if (resolvedVersions != setOf(testedOpenAIJavaVersion)) {
            throw GradleException(
                "Foundry runtimeClasspath must resolve com.openai:openai-java:" +
                    "$testedOpenAIJavaVersion, but resolved $resolvedVersions.",
            )
        }
    }
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
    description = "Runs opt-in live Microsoft Foundry integration tests."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    doFirst {
        val endpoint = System.getenv("FOUNDRY_PROJECT_ENDPOINT")
        val model = System.getenv("FOUNDRY_MODEL")
        val agent = System.getenv("FOUNDRY_AGENT_NAME")
        if (endpoint.isNullOrBlank() || model.isNullOrBlank() && agent.isNullOrBlank()) {
            throw GradleException(
                "liveTest requires non-blank FOUNDRY_PROJECT_ENDPOINT and either " +
                    "FOUNDRY_MODEL or FOUNDRY_AGENT_NAME environment variables.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(liveTestSourceSet.compileJavaTaskName)
    dependsOn(checkOpenAIJavaConvergence)
}

tasks.named("jacocoTestReport") {
    setDependsOn(listOf(tasks.named("test")))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Foundry provider")
            description.set(project.description)
        }
    }
}
