// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PurviewClientTest {
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String APP = "33333333-3333-3333-3333-333333333333";

    @Test
    void client_shouldUseExactGraphPathsBodiesHeadersScopesAndEtag() {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        AtomicReference<AzureTokenRequest> tokenRequest = new AtomicReference<>();
        http.handler = request -> {
            if (request.uri().getPath().endsWith("/protectionScopes/compute")) {
                return PurviewTestHttpClient.response(
                        request,
                        200,
                        """
                        {"value":[{
                          "activities":"uploadText",
                          "executionMode":"evaluateInline",
                          "locations":[{"@odata.type":"#microsoft.graph.policyLocationApplication",
                                        "value":"33333333-3333-3333-3333-333333333333"}],
                          "policyActions":[]
                        }]}
                        """,
                        Map.of(
                                "etag", List.of("\"scope-one\""),
                                "request-id", List.of("request-scopes")));
            }
            if (request.uri().getPath().endsWith("/processContent")) {
                return PurviewTestHttpClient.response(
                        request, 200, """
                        {"protectionScopeState":"modified",
                         "policyActions":[{"@odata.type":"#microsoft.graph.restrictAccessAction",
                                           "restrictionAction":"block"}],
                         "processingErrors":[]}
                        """, Map.of("request-id", List.of("request-content")));
            }
            if (request.uri().getPath().endsWith("/contentActivities")) {
                return PurviewTestHttpClient.response(
                        request, 201, "{\"id\":\"activity-one\"}", Map.of("request-id", List.of("request-activity")));
            }
            throw new AssertionError("Unexpected request: " + request.uri());
        };
        List<PurviewTelemetryEvent> telemetry = new ArrayList<>();
        PurviewSettings settings =
                settings(tokenRequest).telemetryListener(telemetry::add).build();
        try (PurviewClient client = new PurviewClient(settings, http)) {
            PurviewContentRequest request = contentRequest();
            PurviewProtectionScopes scopes = client.computeProtectionScopesAsync(request, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            PurviewDecision decision = client.processContentAsync(request, scopes, true, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            PurviewDecision activity = client.recordContentActivityAsync(request, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(scopes.etag()).isEqualTo("\"scope-one\"");
            assertThat(scopes.scopes()).singleElement().satisfies(scope -> {
                assertThat(scope.executionMode()).isEqualTo(PurviewExecutionMode.EVALUATE_INLINE);
                assertThat(scope.activities()).containsExactly(PurviewActivity.UPLOAD_TEXT);
            });
            assertThat(decision.blocked()).isTrue();
            assertThat(decision.actions()).singleElement().satisfies(action -> {
                assertThat(action.action()).isEqualTo("restrictAccessAction");
                assertThat(action.restrictionAction()).isEqualTo("block");
            });
            assertThat(decision.protectionScopeModified()).isTrue();
            assertThat(activity.blocked()).isFalse();
            assertThat(tokenRequest.get().scopes()).containsExactly("https://graph.microsoft.com/.default");

            java.net.http.HttpRequest compute = http.requests.get(0);
            java.net.http.HttpRequest process = http.requests.get(1);
            java.net.http.HttpRequest contentActivity = http.requests.get(2);
            assertThat(compute.uri().toString())
                    .isEqualTo("https://graph.microsoft.com/v1.0/users/"
                            + USER
                            + "/dataSecurityAndGovernance/protectionScopes/compute");
            assertThat(process.uri().toString())
                    .isEqualTo("https://graph.microsoft.com/v1.0/users/"
                            + USER
                            + "/dataSecurityAndGovernance/processContent");
            assertThat(process.headers().firstValue("If-None-Match")).contains("\"scope-one\"");
            assertThat(process.headers().firstValue("Prefer")).contains("evaluateInline");
            assertThat(process.headers().firstValue("Authorization")).contains("Bearer token-secret");
            assertThat(body(process))
                    .contains("\"contentToProcess\"")
                    .contains("\"activity\":\"uploadText\"")
                    .contains("\"@odata.type\":\"microsoft.graph.textContent\"")
                    .doesNotContain("token-secret");
            assertThat(contentActivity.uri().toString())
                    .isEqualTo("https://graph.microsoft.com/v1.0/users/"
                            + USER
                            + "/dataSecurityAndGovernance/activities/contentActivities");
            assertThat(body(contentActivity))
                    .contains("\"id\":")
                    .contains("\"userId\":\"" + USER + "\"")
                    .contains("\"contentMetadata\"")
                    .doesNotContain("\"contentToProcess\"", TENANT, "token-secret");
            assertThat(telemetry).hasSize(3);
            assertThat(telemetry)
                    .allSatisfy(event -> assertThat(event.toString())
                            .doesNotContain("Sensitive prompt", USER, TENANT, "token-secret"));
        }
    }

    @Test
    void processContent_shouldBlockWhenEitherDlpActionComponentRequiresBlock() {
        PurviewDecision blockAccess = processDecision("""
                {"action":"blockAccess"}
                """);
        PurviewDecision restrictionBlock = processDecision("""
                {"@odata.type":"#microsoft.graph.notifyUserAction","restrictionAction":"block"}
                """);
        PurviewDecision restrictAccessSubtype = processDecision("""
                {"@odata.type":"#microsoft.graph.restrictAccessAction","restrictionAction":"block"}
                """);
        PurviewDecision restrictionOnly = processDecision("""
                {"restrictionAction":"block"}
                """);

        assertThat(blockAccess.blocked()).isTrue();
        assertThat(restrictionBlock.blocked()).isTrue();
        assertThat(restrictionBlock.actions()).singleElement().satisfies(action -> {
            assertThat(action.action()).isEqualTo("microsoft.graph.notifyUserAction");
            assertThat(action.restrictionAction()).isEqualTo("block");
        });
        assertThat(restrictAccessSubtype.blocked()).isTrue();
        assertThat(restrictAccessSubtype.actions())
                .singleElement()
                .extracting(PurviewPolicyAction::action)
                .isEqualTo("restrictAccessAction");
        assertThat(restrictionOnly.blocked()).isTrue();
        assertThat(restrictionOnly.actions())
                .singleElement()
                .extracting(PurviewPolicyAction::action)
                .isNull();
    }

    @Test
    void processContent_shouldPreferExplicitActionAndPreserveNonblockingFutureValues() {
        PurviewDecision explicitWarn = processDecision("""
                {"@odata.type":"#microsoft.graph.restrictAccessAction","action":"warn"}
                """);
        PurviewDecision allow = processDecision("""
                {"action":"allow"}
                """);
        PurviewDecision unknown = processDecision("""
                {"@odata.type":"#microsoft.graph.futurePolicyAction",
                 "restrictionAction":"futureRestriction"}
                """);

        assertThat(explicitWarn.blocked()).isFalse();
        assertThat(explicitWarn.actions())
                .singleElement()
                .extracting(PurviewPolicyAction::action)
                .isEqualTo("warn");
        assertThat(allow.blocked()).isFalse();
        assertThat(unknown.blocked()).isFalse();
        assertThat(unknown.actions()).singleElement().satisfies(action -> {
            assertThat(action.action()).isEqualTo("microsoft.graph.futurePolicyAction");
            assertThat(action.restrictionAction()).isEqualTo("futureRestriction");
        });
    }

    @Test
    void error_shouldPreserveStatusRequestIdCodeRetryAfterAndRedactSecret() {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        http.handler = request -> PurviewTestHttpClient.response(
                request,
                429,
                """
                {"error":{"code":"throttled","message":"token=credential-secret"}}
                """,
                Map.of(
                        "request-id", List.of("request-one"),
                        "retry-after", List.of("9")));
        try (PurviewClient client =
                new PurviewClient(settings(null).maxRetries(0).build(), http)) {
            assertThatThrownBy(() -> client.computeProtectionScopesAsync(contentRequest(), new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(PurviewException.class)
                    .rootCause()
                    .satisfies(failure -> {
                        PurviewException mapped = (PurviewException) failure;
                        assertThat(mapped.kind()).isEqualTo(PurviewException.Kind.RATE_LIMIT);
                        assertThat(mapped.statusCode()).isEqualTo(429);
                        assertThat(mapped.requestId()).isEqualTo("request-one");
                        assertThat(mapped.serviceCode()).isEqualTo("throttled");
                        assertThat(mapped.retryAfter()).isEqualTo(Duration.ofSeconds(9));
                        assertThat(mapped.getMessage())
                                .isEqualTo("Purview request failed with HTTP 429.")
                                .doesNotContain("credential-secret");
                    });
        }
    }

    @Test
    void processContent_shouldRejectNonemptyProcessingErrorsWithoutRetainingDetails() {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        http.handler = request -> PurviewTestHttpClient.response(request, 200, """
                {"protectionScopeState":"notModified","policyActions":[],
                 "processingErrors":[{"code":"evaluationFailed",
                                      "message":"token=credential-secret"}]}
                """);
        try (PurviewClient client = new PurviewClient(settings(null).build(), http)) {
            PurviewProtectionScopes scopes = new PurviewProtectionScopes(List.of(), null, "request-one");

            assertThatThrownBy(() -> client.processContentAsync(
                                    contentRequest(), scopes, true, new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(PurviewException.class)
                    .rootCause()
                    .satisfies(failure -> {
                        PurviewException mapped = (PurviewException) failure;
                        assertThat(mapped.serviceCode()).isEqualTo("content_processing_failed");
                        assertThat(mapped.getMessage()).doesNotContain("credential-secret", "evaluationFailed");
                    });
        }
    }

    @Test
    void cancellation_shouldCancelPendingGraphRequest() {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> pending;
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (PurviewClient client = new PurviewClient(settings(null).build(), http)) {
            java.util.concurrent.CompletionStage<PurviewProtectionScopes> result =
                    client.computeProtectionScopesAsync(contentRequest(), cancellation);

            cancellation.cancel();

            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(pending.isCancelled()).isTrue();
        }
    }

    @Test
    void delegatedIdentity_shouldResolveOidWithScopesWhetherIdtypIsAbsentOrUser() {
        PurviewClient.TokenIdentity withoutIdType = identity("""
                {"oid":"%s","tid":"%s","azp":"%s","scp":"Content.Process.User"}
                """.formatted(USER, TENANT, APP));
        PurviewClient.TokenIdentity explicitUser = identity("""
                {"oid":"%s","tid":"%s","azp":"%s","idtyp":"user","scp":"Content.Process.User"}
                """.formatted(USER, TENANT, APP));

        assertThat(withoutIdType.userId()).isEqualTo(USER);
        assertThat(explicitUser.userId()).isEqualTo(USER);
    }

    @Test
    void delegatedIdentity_shouldNeverTreatAppTokenAsUser() {
        PurviewClient.TokenIdentity identity = identity("""
                {"oid":"%s","tid":"%s","azp":"%s","idtyp":"app","scp":"Content.Process.User"}
                """.formatted(USER, TENANT, APP));

        assertThat(identity.userId()).isNull();
    }

    @Test
    void delegatedIdentity_shouldIgnoreMissingOrMalformedOidAndMissingScopes() {
        PurviewClient.TokenIdentity missingOid = identity("""
                {"tid":"%s","azp":"%s","scp":"Content.Process.User"}
                """.formatted(TENANT, APP));
        PurviewClient.TokenIdentity malformedOid = identity("""
                {"oid":"not-a-guid","tid":"%s","azp":"%s","scp":"Content.Process.User"}
                """.formatted(TENANT, APP));
        PurviewClient.TokenIdentity missingScopes = identity("""
                {"oid":"%s","tid":"%s","azp":"%s"}
                """.formatted(USER, TENANT, APP));

        assertThat(missingOid.userId()).isNull();
        assertThat(malformedOid.userId()).isNull();
        assertThat(missingScopes.userId()).isNull();
    }

    @Test
    void delegatedIdentity_shouldRejectMalformedJwtWithoutRetainingTokenOrClaims() {
        String malformed = "header.not-base64!.";
        try (PurviewClient client = new PurviewClient(settings(null, malformed).build(), new PurviewTestHttpClient())) {
            assertThatThrownBy(() -> client.resolveIdentityAsync(new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(PurviewException.class)
                    .rootCause()
                    .satisfies(failure -> {
                        PurviewException mapped = (PurviewException) failure;
                        assertThat(mapped.serviceCode()).isEqualTo("invalid_token_claims");
                        assertThat(mapped.getMessage()).doesNotContain(malformed, "not-base64");
                    });
        }
    }

    @Test
    void retryBackoff_shouldCancelImmediatelyAndNeverSendAnotherRequest() throws Exception {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        http.handler = request -> {
            calls.incrementAndGet();
            firstRequest.countDown();
            return PurviewTestHttpClient.response(
                    request, 429, "{}", Map.of("retry-after", List.of(Long.toString(Long.MAX_VALUE))));
        };
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (PurviewClient client =
                new PurviewClient(settings(null).maxRetries(1).build(), http)) {
            java.util.concurrent.CompletionStage<PurviewProtectionScopes> result =
                    client.computeProtectionScopesAsync(contentRequest(), cancellation);
            assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.cancel();

            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            Thread.sleep(1_200);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void closeDuringRetryBackoff_shouldCancelTimerAndNeverSendAnotherRequest() throws Exception {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        http.handler = request -> {
            calls.incrementAndGet();
            firstRequest.countDown();
            return PurviewTestHttpClient.response(request, 503, "{}", Map.of("retry-after", List.of("1")));
        };
        PurviewClient client = new PurviewClient(settings(null).maxRetries(1).build(), http);
        java.util.concurrent.CompletionStage<PurviewProtectionScopes> result =
                client.computeProtectionScopesAsync(contentRequest(), new DefaultRunCancellation());
        assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();

        client.close();

        assertThatThrownBy(() -> result.toCompletableFuture().join())
                .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        Thread.sleep(1_200);
        assertThat(calls).hasValue(1);
    }

    private static PurviewSettings.Builder settings(AtomicReference<AzureTokenRequest> tokenRequest) {
        return settings(tokenRequest, "token-secret");
    }

    private static PurviewSettings.Builder settings(AtomicReference<AzureTokenRequest> tokenRequest, String token) {
        return PurviewSettings.builder()
                .authenticationProvider((request, cancellation) -> {
                    if (tokenRequest != null) {
                        tokenRequest.set(request);
                    }
                    return CompletableFuture.completedStage(
                            new AzureAccessToken(token, Instant.now().plusSeconds(3600)));
                })
                .appName("Agent Framework Test")
                .appVersion("1.0")
                .tenantId(TENANT)
                .appLocation(new PurviewAppLocation(PurviewLocationType.APPLICATION, APP))
                .maxRetries(0);
    }

    private static PurviewClient.TokenIdentity identity(String payload) {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try (PurviewClient client =
                new PurviewClient(settings(null, header + "." + claims + ".").build(), new PurviewTestHttpClient())) {
            return client.resolveIdentityAsync(new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
        }
    }

    private static PurviewDecision processDecision(String policyAction) {
        PurviewTestHttpClient http = new PurviewTestHttpClient();
        http.handler = request -> PurviewTestHttpClient.response(
                request,
                200,
                "{\"protectionScopeState\":\"notModified\",\"policyActions\":["
                        + policyAction
                        + "],\"processingErrors\":[]}");
        try (PurviewClient client = new PurviewClient(settings(null).build(), http)) {
            return client.processContentAsync(
                            contentRequest(),
                            new PurviewProtectionScopes(List.of(), null, "request-one"),
                            true,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
        }
    }

    static PurviewContentRequest contentRequest() {
        return new PurviewContentRequest(
                USER,
                TENANT,
                "44444444-4444-4444-4444-444444444444@AF",
                "message-one",
                0,
                PurviewActivity.UPLOAD_TEXT,
                "Sensitive prompt",
                Instant.parse("2026-08-10T00:00:00Z"),
                new PurviewAppLocation(PurviewLocationType.APPLICATION, APP),
                "Agent Framework Test",
                "1.0");
    }

    private static String body(java.net.http.HttpRequest request) {
        BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ArrayList<ByteBuffer> buffers = new ArrayList<>();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                buffers.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.join();
        int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer result = ByteBuffer.allocate(size);
        buffers.forEach(result::put);
        return new String(result.array(), StandardCharsets.UTF_8);
    }
}
