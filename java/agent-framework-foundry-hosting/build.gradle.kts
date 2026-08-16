import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Foundry bridges for the generic Agent Framework hosting runtime."

dependencies {
    api(project(":agent-framework-hosting"))
    api(project(":agent-framework-azure-ai-persistent"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Foundry hosting")
            description.set(project.description)
        }
    }
}

val foundryHostingConsumerClasspath =
    configurations.create("foundryHostingConsumerClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        extendsFrom(configurations.named("api").get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        }
    }

val consumerSource =
    layout.buildDirectory.file("publication-contract/consumer/FoundryHostingConsumer.java")
val mainSourceSet = the<SourceSetContainer>().named("main")

val generateFoundryHostingConsumerSource =
    tasks.register("generateFoundryHostingConsumerSource") {
        outputs.file(consumerSource)
        doLast {
            consumerSource.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    """
                        package consumer;

                        import com.microsoft.agents.hosting.foundry.FoundryHostingBridge;
                        import com.microsoft.agents.providers.azureaipersistent.AzureAIPersistentAgent;

                        final class FoundryHostingConsumer {
                            void register(FoundryHostingBridge bridge, AzureAIPersistentAgent agent) {
                                bridge.registerPersistentAgent("persistent", agent);
                            }
                        }
                        """.trimIndent(),
                )
            }
        }
    }

val compileFoundryHostingConsumer =
    tasks.register<JavaCompile>("compileFoundryHostingConsumer") {
        dependsOn(generateFoundryHostingConsumerSource, tasks.named("classes"))
        source(consumerSource)
        classpath =
            files(
                mainSourceSet.get().output,
                foundryHostingConsumerClasspath,
            )
        destinationDirectory.set(layout.buildDirectory.dir("publication-contract/classes"))
        javaCompiler.set(
            javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            },
        )
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

val checkFoundryHostingPublicationMetadata =
    tasks.register("checkFoundryHostingPublicationMetadata") {
        dependsOn(
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        val pom = layout.buildDirectory.file("publications/mavenJava/pom-default.xml")
        val module = layout.buildDirectory.file("publications/mavenJava/module.json")
        inputs.files(pom, module)
        doLast {
            val pomText = pom.get().asFile.readText()
            val dependencyBlocks =
                Regex("""<dependency>(.*?)</dependency>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(pomText)
                    .map { it.groupValues[1] }
                    .toList()
            val persistentPom =
                dependencyBlocks.singleOrNull {
                    it.contains("<artifactId>agent-framework-azure-ai-persistent</artifactId>")
                }
            check(persistentPom != null && persistentPom.contains("<scope>compile</scope>")) {
                "Published POM must expose agent-framework-azure-ai-persistent with compile scope."
            }
            check(
                dependencyBlocks.none {
                    it.contains("<artifactId>agent-framework-foundry</artifactId>")
                },
            ) {
                "Published POM must not retain the unused agent-framework-foundry dependency."
            }

            val moduleText = module.get().asFile.readText()
            check(
                Regex(
                    """"module"\s*:\s*"agent-framework-azure-ai-persistent"""",
                ).containsMatchIn(moduleText),
            ) {
                "Gradle module metadata must expose agent-framework-azure-ai-persistent."
            }
            check(
                !Regex(
                    """"module"\s*:\s*"agent-framework-foundry"""",
                ).containsMatchIn(moduleText),
            ) {
                "Gradle module metadata must not retain the unused agent-framework-foundry dependency."
            }
        }
    }

tasks.named("check") {
    dependsOn(compileFoundryHostingConsumer, checkFoundryHostingPublicationMetadata)
}
