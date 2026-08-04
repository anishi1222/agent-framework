plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.diffplug.spotless") version "8.9.0"
}

group = "com.microsoft.agents.buildlogic"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("javaLibraryConvention") {
            id = "com.microsoft.agents.java-library"
            implementationClass = "com.microsoft.agents.buildlogic.JavaLibraryConventionPlugin"
        }
        create("javaPlatformConvention") {
            id = "com.microsoft.agents.java-platform"
            implementationClass = "com.microsoft.agents.buildlogic.JavaPlatformConventionPlugin"
        }
        create("javaTestSupportConvention") {
            id = "com.microsoft.agents.java-test-support"
            implementationClass = "com.microsoft.agents.buildlogic.JavaTestSupportConventionPlugin"
        }
        create("architectureConvention") {
            id = "com.microsoft.agents.architecture"
            implementationClass = "com.microsoft.agents.buildlogic.ArchitectureConventionPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        licenseHeader("// Copyright (c) Microsoft. All rights reserved.\n\n")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"))
}
