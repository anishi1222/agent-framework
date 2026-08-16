// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2AClient;
import com.microsoft.agents.protocols.a2a.A2AClientOptions;
import com.microsoft.agents.protocols.a2a.A2AProtocol;
import com.microsoft.agents.protocols.a2a.AgentCapabilities;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.AgentSkill;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.FilePart;
import com.microsoft.agents.protocols.a2a.Message;
import com.microsoft.agents.protocols.a2a.Role;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TextPart;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

class A2AOfficialSdkInteropTest {
    @Test
    void officialJavaSdkClientAndCardResolver_shouldUseFrameworkHost() throws Exception {
        // Arrange
        AgentCard card = frameworkCard();
        FileRoundTripExecutor executor = new FileRoundTripExecutor();
        A2AService service = A2AService.builder(card, executor).build();
        A2AHttpServer server =
                A2AHttpServer.start(service, A2AHttpServerOptions.builder().build());
        try {
            byte[] expectedFile = new byte[] {1, 2, 3, 4};
            org.a2aproject.sdk.spec.Message officialMessage = org.a2aproject.sdk.spec.Message.builder()
                    .role(org.a2aproject.sdk.spec.Message.Role.ROLE_USER)
                    .messageId("official-message")
                    .parts(
                            new org.a2aproject.sdk.spec.TextPart("hello"),
                            new org.a2aproject.sdk.spec.FilePart(new org.a2aproject.sdk.spec.FileWithBytes(
                                    "image/png", "official.png", expectedFile)))
                    .build();
            JSONRPCTransport transport = new JSONRPCTransport(server.endpoint().toString());
            A2ACardResolver resolver = A2ACardResolver.builder()
                    .httpClient(new JdkA2AHttpClient(java.net.http.HttpClient.newBuilder()
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .build()))
                    .baseUrl(server.endpoint().resolve("/").toString())
                    .build();

            // Act
            org.a2aproject.sdk.spec.AgentCard officialCard = resolver.getAgentCard();
            EventKind response = transport.sendMessage(
                    new MessageSendParams(
                            officialMessage,
                            MessageSendConfiguration.builder()
                                    .acceptedOutputModes(List.of("image/png"))
                                    .build(),
                            Map.of()),
                    null);
            HttpClient strictClient = HttpClient.newHttpClient();
            HttpResponse<String> rawCard = strictClient.send(
                    HttpRequest.newBuilder(server.endpoint().resolve(A2AProtocol.AGENT_CARD_PATH))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            org.a2aproject.sdk.grpc.AgentCard.Builder strictBuilder = org.a2aproject.sdk.grpc.AgentCard.newBuilder();
            JSONRPCUtils.parseJsonString(rawCard.body(), strictBuilder, null);
            org.a2aproject.sdk.spec.AgentCard strictRoundTrip = ProtoUtils.FromProto.agentCard(
                    ProtoUtils.ToProto.agentCard(ProtoUtils.FromProto.agentCard(strictBuilder.build())));

            // Assert
            assertThat(officialCard.name()).isEqualTo("framework");
            assertThat(officialCard.supportedInterfaces()).singleElement().satisfies(agentInterface -> {
                assertThat(agentInterface.url()).isEqualTo(server.endpoint().toString());
                assertThat(agentInterface.protocolBinding()).isEqualTo("JSONRPC");
                assertThat(agentInterface.protocolVersion()).isEqualTo(A2AProtocol.VERSION);
            });
            assertThat(officialCard.capabilities().streaming()).isTrue();
            assertThat(officialCard.capabilities().pushNotifications()).isFalse();
            assertThat(officialCard.capabilities().extendedAgentCard()).isFalse();
            assertThat(officialCard.capabilities().extensions()).isEmpty();
            assertThat(officialCard.skills()).singleElement().satisfies(skill -> {
                assertThat(skill.id()).isEqualTo("echo");
                assertThat(skill.name()).isEqualTo("Echo");
                assertThat(skill.description()).isEqualTo("Echoes");
                assertThat(skill.tags()).containsExactly("interop");
            });
            assertThat(rawCard.statusCode()).isEqualTo(200);
            assertThat(rawCard.body()).doesNotContain("\"metadata\"");
            assertThat(strictRoundTrip.supportedInterfaces()).isEqualTo(officialCard.supportedInterfaces());
            assertThat(strictRoundTrip.capabilities()).isEqualTo(officialCard.capabilities());
            assertThat(strictRoundTrip.skills()).isEqualTo(officialCard.skills());
            assertThat(response).isInstanceOf(org.a2aproject.sdk.spec.Task.class);
            org.a2aproject.sdk.spec.Task officialTask = (org.a2aproject.sdk.spec.Task) response;
            assertThat(officialTask.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
            assertThat(officialTask.artifacts()).hasSize(1);
            org.a2aproject.sdk.spec.FilePart officialPart = (org.a2aproject.sdk.spec.FilePart)
                    officialTask.artifacts().getFirst().parts().getFirst();
            org.a2aproject.sdk.spec.FileWithBytes officialFile =
                    (org.a2aproject.sdk.spec.FileWithBytes) officialPart.file();
            assertThat(officialFile.mimeType()).isEqualTo("image/png");
            assertThat(officialFile.name()).isEqualTo("official.png");
            assertThat(Base64.getDecoder().decode(officialFile.bytes())).containsExactly(expectedFile);
            assertThat(executor.receivedFile().bytes()).containsExactly(expectedFile);
            assertThat(executor.receivedFile().mediaType()).isEqualTo("image/png");
            assertThat(executor.receivedFile().filename()).isEqualTo("official.png");
            strictClient.close();
            transport.close();
        } finally {
            server.close();
            service.close();
        }
    }

    @Test
    void frameworkClient_shouldUseOfficialParserModelsAndJsonRpcSerializer() throws Exception {
        // Arrange
        try (OfficialArtifactHost host = new OfficialArtifactHost();
                A2AClient client = A2AClient.create(A2AClientOptions.builder(host.endpoint())
                        .allowInsecureLoopbackHttp(true)
                        .build())) {
            SendMessageRequest request = new SendMessageRequest(Message.builder(Role.ROLE_USER)
                    .messageId("framework-message")
                    .parts(List.of(
                            new TextPart("hello official"),
                            FilePart.bytes(
                                    new byte[] {9, 8, 7}, "framework.bin", "application/octet-stream", Map.of())))
                    .build());

            // Act
            Task response = (Task)
                    client.sendMessageAsync(request).toCompletableFuture().join();

            // Assert
            assertThat(host.parsedWithOfficialSdk()).isTrue();
            assertThat(response.id()).isEqualTo("official-task");
            assertThat(response.contextId()).isEqualTo("official-context");
            assertThat(response.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(host.receivedFileBytes()).containsExactly(9, 8, 7);
            assertThat(host.receivedFileMediaType()).isEqualTo("application/octet-stream");
            assertThat(host.receivedFilename()).isEqualTo("framework.bin");
            assertThat(response.artifacts()).singleElement().satisfies(artifact -> {
                FilePart file = (FilePart) artifact.parts().getFirst();
                assertThat(file.bytes()).containsExactly(4, 5, 6);
                assertThat(file.mediaType()).isEqualTo("image/png");
                assertThat(file.filename()).isEqualTo("official.png");
            });
        }
    }

    private static AgentCard frameworkCard() {
        return AgentCard.builder("framework", "Framework interop agent", "1.0.0")
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .defaultInputModes(List.of("text/plain", "image/png"))
                .defaultOutputModes(List.of("text/plain", "image/png"))
                .skills(List.of(AgentSkill.builder("echo", "Echo", "Echoes")
                        .tags(List.of("interop"))
                        .metadata(Map.of("local-only", StateValue.string("never-on-wire")))
                        .build()))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("http://127.0.0.1:1/a2a"))))
                .build();
    }

    private static final class FileRoundTripExecutor implements A2AExecutor {
        private volatile FilePart receivedFile;

        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context, A2AEventSink sink, RunCancellation cancellation) {
            receivedFile = context.request().message().parts().stream()
                    .filter(FilePart.class::isInstance)
                    .map(FilePart.class::cast)
                    .findFirst()
                    .orElseThrow();
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                    .thenCompose(ignored -> sink.addArtifactAsync(
                            Artifact.builder(context.task().id() + "-result")
                                    .parts(List.of(receivedFile))
                                    .build(),
                            false,
                            true,
                            Map.of()))
                    .thenCompose(ignored -> sink.updateStatusAsync(TaskState.TASK_STATE_COMPLETED, null))
                    .thenApply(ignored -> null);
        }

        private FilePart receivedFile() {
            return receivedFile;
        }
    }

    private static final class OfficialArtifactHost implements AutoCloseable {
        private final HttpServer server;

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private volatile boolean parsedWithOfficialSdk;

        private volatile byte[] receivedFileBytes;

        private volatile String receivedFileMediaType;

        private volatile String receivedFilename;

        private OfficialArtifactHost() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(executor);
            server.createContext("/a2a", exchange -> {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    A2ARequest<?> parsed = JSONRPCUtils.parseRequestBody(body, null);
                    parsedWithOfficialSdk =
                            parsed instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
                    org.a2aproject.sdk.spec.MessageSendParams params =
                            (org.a2aproject.sdk.spec.MessageSendParams) parsed.getParams();
                    org.a2aproject.sdk.spec.FilePart receivedPart = params.message().parts().stream()
                            .filter(org.a2aproject.sdk.spec.FilePart.class::isInstance)
                            .map(org.a2aproject.sdk.spec.FilePart.class::cast)
                            .findFirst()
                            .orElseThrow();
                    org.a2aproject.sdk.spec.FileWithBytes receivedFile =
                            (org.a2aproject.sdk.spec.FileWithBytes) receivedPart.file();
                    receivedFileBytes = Base64.getDecoder().decode(receivedFile.bytes());
                    receivedFileMediaType = receivedFile.mimeType();
                    receivedFilename = receivedFile.name();
                    org.a2aproject.sdk.spec.Task task = new org.a2aproject.sdk.spec.Task(
                            "official-task",
                            "official-context",
                            new org.a2aproject.sdk.spec.TaskStatus(
                                    org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED,
                                    null,
                                    OffsetDateTime.of(2026, 8, 8, 0, 0, 0, 0, ZoneOffset.UTC)),
                            List.of(org.a2aproject.sdk.spec.Artifact.builder()
                                    .artifactId("official-artifact")
                                    .parts(new org.a2aproject.sdk.spec.FilePart(
                                            new org.a2aproject.sdk.spec.FileWithBytes(
                                                    "image/png", "official.png", new byte[] {4, 5, 6})))
                                    .build()),
                            List.of(),
                            Map.of());
                    String response = JSONRPCUtils.toJsonRPCResultResponse(
                            parsed.getId(), ProtoUtils.ToProto.taskOrMessage(task));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception failure) {
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a2a");
        }

        private boolean parsedWithOfficialSdk() {
            return parsedWithOfficialSdk;
        }

        private byte[] receivedFileBytes() {
            return receivedFileBytes.clone();
        }

        private String receivedFileMediaType() {
            return receivedFileMediaType;
        }

        private String receivedFilename() {
            return receivedFilename;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
