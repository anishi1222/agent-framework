import org.gradle.api.publish.maven.MavenPublication
import java.net.URLClassLoader

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Optional Spring Boot WebFlux adapter for Agent Framework AG-UI hosting."

dependencies {
    api(project(":agent-framework-hosting-agui")) {
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-core")
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
    }
    implementation(project(":agent-framework-hosting-http")) {
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-core")
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
    }
    implementation(project(":agent-framework-hosting-spring")) {
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

val assertAguiSpringReactiveRuntimeClasspath = tasks.register("assertAguiSpringReactiveRuntimeClasspath") {
    group = "verification"
    description = "Asserts that AG-UI Spring WebFlux uses Netty without embedded Tomcat."
    dependsOn(tasks.named("testClasses"))

    doLast {
        val productionFiles = configurations.runtimeClasspath.get().files
        val reactiveFiles = sourceSets.test.get().runtimeClasspath.files
        val tomcatArtifacts = reactiveFiles
            .filter { it.name.startsWith("tomcat-embed-") }
            .map { it.name }
            .sorted()
        check(tomcatArtifacts.isEmpty()) {
            "AG-UI Spring reactive runtime selected embedded Tomcat artifacts: $tomcatArtifacts"
        }
        check(productionFiles.none { it.name.startsWith("tomcat-embed-") }) {
            "AG-UI Spring production runtime selected an embedded Tomcat artifact."
        }
        check(reactiveFiles.any { it.name.startsWith("reactor-netty-http-") }) {
            "AG-UI Spring reactive runtime did not select reactor-netty-http."
        }

        URLClassLoader(
            reactiveFiles.map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        ).use { runtime ->
            runtime.loadClass("reactor.netty.http.server.HttpServer")
            check(runCatching { runtime.loadClass("org.apache.catalina.startup.Tomcat") }.isFailure) {
                "AG-UI Spring runtime can load org.apache.catalina.startup.Tomcat."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(assertAguiSpringReactiveRuntimeClasspath)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework AG-UI Spring hosting")
            description.set(project.description)
        }
    }
}
