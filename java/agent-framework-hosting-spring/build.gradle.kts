import org.gradle.api.publish.maven.MavenPublication
import java.net.URLClassLoader

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Optional Spring Boot WebFlux adapter for Agent Framework hosting."

dependencies {
    api(project(":agent-framework-hosting"))

    implementation(project(":agent-framework-hosting-http")) {
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-core")
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
    }
    implementation(libs.reactor.core)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.spring.webflux)

    testImplementation(libs.reactor.test)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.test)
}

val assertSpringReactiveRuntimeClasspath = tasks.register("assertSpringReactiveRuntimeClasspath") {
    group = "verification"
    description = "Asserts that Spring WebFlux uses Netty without embedded Tomcat."
    dependsOn(tasks.named("testClasses"))

    doLast {
        val productionFiles = configurations.runtimeClasspath.get().files
        val reactiveFiles = sourceSets.test.get().runtimeClasspath.files
        val tomcatArtifacts = reactiveFiles
            .filter { it.name.startsWith("tomcat-embed-") }
            .map { it.name }
            .sorted()
        check(tomcatArtifacts.isEmpty()) {
            "Spring reactive runtime selected embedded Tomcat artifacts: $tomcatArtifacts"
        }
        check(productionFiles.none { it.name.startsWith("tomcat-embed-") }) {
            "Spring production runtime selected an embedded Tomcat artifact."
        }
        check(reactiveFiles.any { it.name.startsWith("reactor-netty-http-") }) {
            "Spring reactive runtime did not select reactor-netty-http."
        }

        URLClassLoader(
            reactiveFiles.map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        ).use { runtime ->
            runtime.loadClass("reactor.netty.http.server.HttpServer")
            check(runCatching { runtime.loadClass("org.apache.catalina.startup.Tomcat") }.isFailure) {
                "Spring reactive runtime can load org.apache.catalina.startup.Tomcat."
            }
            check(runCatching { runtime.loadClass("org.apache.tomcat.websocket.server.WsSci") }.isFailure) {
                "Spring reactive runtime can load embedded Tomcat WebSocket classes."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(assertSpringReactiveRuntimeClasspath)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Spring hosting")
            description.set(project.description)
        }
    }
}
