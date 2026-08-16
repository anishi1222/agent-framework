plugins {
    application
    java
}

val frameworkVersion =
    providers
        .gradleProperty("frameworkVersion")
        .getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(platform("com.microsoft.agents:agent-framework-bom:$frameworkVersion"))
    implementation("com.microsoft.agents:agent-framework-harness")
    implementation("com.microsoft.agents:agent-framework-workflows")
}

application {
    mainClass.set("smoke.BomConsumer")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
