plugins {
    id("com.microsoft.agents.java-test-support")
}

description = "Offline executable samples for Microsoft Agent Framework Java."

dependencies {
    implementation(project(":agent-framework-agents"))
    implementation(project(":agent-framework-harness"))
    implementation(project(":agent-framework-orchestrations"))
    implementation(project(":agent-framework-workflows"))
}

val sampleMainClasses =
    linkedMapOf(
        "runSample01" to "com.microsoft.agents.samples.s01.BasicAgentSample",
        "runSample02" to "com.microsoft.agents.samples.s02.ToolCallingSample",
        "runSample03" to "com.microsoft.agents.samples.s03.WorkflowSample",
        "runSample04" to "com.microsoft.agents.samples.s04.OrchestrationSample",
        "runSample05" to "com.microsoft.agents.samples.s05.HarnessSample",
    )

val sampleTasks =
    sampleMainClasses.map { (taskName, mainClassName) ->
        tasks.register<JavaExec>(taskName) {
            group = "verification"
            description = "Runs $mainClassName."
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set(mainClassName)
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                },
            )
        }
    }

val checkSamples =
    tasks.register("checkSamples") {
        group = "verification"
        description = "Runs every offline Java sample."
        dependsOn(sampleTasks)
    }

tasks.named("check") {
    dependsOn(checkSamples)
}
