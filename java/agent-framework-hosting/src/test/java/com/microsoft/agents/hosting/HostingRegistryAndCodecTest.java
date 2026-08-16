// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HostingRegistryAndCodecTest {
    @Test
    void registry_shouldSortDescriptorsAndRejectDuplicateRoutes() {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        Agent<Void> beta = agent("beta");
        Agent<Void> alpha = agent("alpha");

        // Act
        registry.registerAgent(beta);
        registry.registerAgent(alpha);

        // Assert
        assertThat(registry.agents()).extracting(HostingRouteDescriptor::id).containsExactly("alpha", "beta");
        assertThatThrownBy(() -> registry.registerAgent("alpha", beta))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.CONFLICT);
    }

    @Test
    void registry_shouldPreserveExplicitAgentCapabilitiesAndMetadata() {
        HostingRegistry registry = new HostingRegistry();

        HostingRouteDescriptor descriptor = registry.registerAgent(
                "foundry", agent("foundry"), false, false, Map.of("provider", StateValue.string("foundry")));

        assertThat(descriptor.streamingSupported()).isFalse();
        assertThat(descriptor.resumeSupported()).isFalse();
        assertThat(descriptor.metadata()).containsEntry("provider", StateValue.string("foundry"));
    }

    @Test
    void registry_shouldRegisterTypedWorkflowWithoutReverseDependencies() {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("echo-workflow", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("echo", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));

        // Act
        try (Workflow<String, String> workflow =
                builder.entry(node).output(node).build()) {
            HostingRouteDescriptor descriptor = registry.registerWorkflow(workflow, HostingWorkflowCodecs.text());

            // Assert
            assertThat(descriptor.kind()).isEqualTo(HostingRouteKind.WORKFLOW);
            assertThat(registry.workflows()).containsExactly(descriptor);
        }
    }

    @Test
    void codec_shouldDecodeStrictMessagesOptionsAndWorkflowInput() {
        // Arrange
        HostingJsonCodec codec = new HostingJsonCodec(HostingLimits.defaults());
        byte[] body = json("""
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                  ],
                  "input":{"value":7},
                  "options":{"maxIterations":3,"maxFunctionCalls":2},
                  "metadata":{"trace":"safe"}
                }
                """);

        // Act
        HostingRunRequest request = codec.decodeRunRequest(body);

        // Assert
        assertThat(request.messages()).containsExactly(Message.text(Role.USER, "hello"));
        assertThat(request.input()).isEqualTo(StateValue.object(Map.of("value", StateValue.integer(7))));
        assertThat(request.options().maxIterations()).isEqualTo(3);
        assertThat(request.options().maxFunctionCalls()).isEqualTo(2);
    }

    @Test
    void codec_shouldRejectDuplicateTrailingNonFiniteAndUnknownPolymorphism() {
        // Arrange
        HostingJsonCodec codec = new HostingJsonCodec(HostingLimits.defaults());

        // Act / Assert
        assertMalformed(codec, """
                {"version":"java-hosting-2026-08-01","input":"one","input":"two"}
                """);
        assertMalformed(codec, """
                {"version":"java-hosting-2026-08-01","input":"one"} {}
                """);
        assertMalformed(codec, """
                {"version":"java-hosting-2026-08-01","input":NaN}
                """);
        assertMalformed(codec, """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {
                      "role":"user",
                      "contents":[
                        {"kind":"text","text":"hello","@class":"java.lang.Runtime"}
                      ]
                    }
                  ]
                }
                """);
    }

    @Test
    void codec_shouldRejectDepthCollectionAndUnsupportedVersion() {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxNestingDepth(5)
                .maxCollectionEntries(2)
                .build();
        HostingJsonCodec codec = new HostingJsonCodec(limits);

        // Act / Assert
        assertThatThrownBy(() -> codec.decodeRunRequest(json("""
                        {"version":"java-hosting-2026-08-01","input":{"a":{"b":{"c":{"d":{"e":{"f":1}}}}}}}
                        """))).isInstanceOf(HostingException.class);
        assertMalformed(codec, """
                {"version":"java-hosting-2026-08-01","input":[1,2,3]}
                """);
        assertThatThrownBy(() -> codec.decodeRunRequest(json("""
                        {"version":"future","input":"value"}
                        """)))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.UNPROCESSABLE);
    }

    @Test
    void codec_shouldRedactCredentialsAndNeverEmitClassMetadata() {
        // Arrange
        HostingJsonCodec codec = new HostingJsonCodec(HostingLimits.defaults());
        StateValue value = StateValue.object(Map.of(
                "apiKey",
                StateValue.string("super-secret"),
                "client-secret",
                StateValue.string("client-value"),
                "x-api-key",
                StateValue.string("header-value"),
                "nested",
                StateValue.object(Map.of("authorization", StateValue.string("Bearer value")))));

        // Act
        String encoded = HostingJsonCodec.utf8(codec.encodeValue(value));

        // Assert
        assertThat(encoded)
                .contains("[REDACTED]")
                .doesNotContain("super-secret", "Bearer value", "@class", "java.lang");
        assertThat(encoded).doesNotContain("client-value", "header-value");
    }

    @Test
    void codec_shouldExposeOnlyFrameworkIssuedPrincipalBoundContinuationToken() {
        // Arrange
        HostingJsonCodec codec = new HostingJsonCodec(HostingLimits.defaults());
        HostingContinuationDescriptor continuation = new HostingContinuationDescriptor(
                "continuation-token",
                HostingContinuationType.APPROVAL,
                Instant.parse("2026-08-09T00:10:00Z"),
                List.of(new HostingApprovalRequest(
                        "approval-1",
                        "write",
                        StateValue.object(Map.of("apiKey", StateValue.string("super-secret"))))));
        HostingOutcome outcome = HostingOutcome.approvalRequired("run-1", continuation);

        // Act
        String encoded = HostingJsonCodec.utf8(codec.encodeOutcome(outcome));
        String arbitrary = HostingJsonCodec.utf8(
                codec.encodeValue(StateValue.object(Map.of("token", StateValue.string("application-token")))));

        // Assert
        assertThat(encoded)
                .contains("\"token\":\"continuation-token\"", "\"oneTime\":true", "\"processLocal\":true", "[REDACTED]")
                .doesNotContain("super-secret");
        assertThat(arbitrary).contains("[REDACTED]").doesNotContain("application-token");
    }

    @Test
    void codec_shouldEnforceEncodedResponseBytes() {
        // Arrange
        HostingLimits limits = HostingLimits.builder().maxResponseBytes(153).build();
        HostingJsonCodec codec = new HostingJsonCodec(limits);

        // Act / Assert
        assertThatThrownBy(() -> codec.encodeValue(StateValue.string("x".repeat(256))))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.OVERFLOW);
    }

    @Test
    void limits_shouldRejectResponseBoundThatCannotFitProtocolError() {
        assertThatThrownBy(() -> HostingLimits.builder().maxResponseBytes(152).build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class)
                .hasMessageContaining("minimal Java-hosting error envelope", "153");
    }

    private static void assertMalformed(HostingJsonCodec codec, String value) {
        assertThatThrownBy(() -> codec.decodeRunRequest(json(value)))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isIn(HostingErrorCode.MALFORMED_REQUEST, HostingErrorCode.OVERFLOW);
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Agent<Void> agent(String id) {
        return new Agent<>() {
            @Override
            public AgentMetadata metadata() {
                return new AgentMetadata(id, id, "test");
            }

            @Override
            public RunHandle<AgentResponse<Void>> startRun(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
                source.tryComplete(AgentResponse.<Void>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "ok")))
                        .build());
                return source.handle();
            }

            @Override
            public Flow.Publisher<AgentResponseUpdate> runStreaming(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {}
                });
            }
        };
    }
}
