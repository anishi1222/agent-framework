// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PurviewPolicyEvaluatorTest {
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String APP = "33333333-3333-3333-3333-333333333333";

    @Test
    void evaluator_shouldCacheScopesApplyMostRestrictiveModeAndBlock() {
        PurviewSettings settings = settings(PurviewFailureMode.FAIL_CLOSED);
        PurviewClient client = mock(PurviewClient.class);
        PurviewAppLocation location = new PurviewAppLocation(PurviewLocationType.APPLICATION, APP);
        PurviewProtectionScopes scopes = new PurviewProtectionScopes(
                List.of(
                        new PurviewProtectionScope(
                                Set.of(PurviewActivity.UPLOAD_TEXT),
                                PurviewExecutionMode.EVALUATE_OFFLINE,
                                List.of(location),
                                List.of()),
                        new PurviewProtectionScope(
                                Set.of(PurviewActivity.UPLOAD_TEXT),
                                PurviewExecutionMode.EVALUATE_INLINE,
                                List.of(location),
                                List.of())),
                "\"scope-one\"",
                "request-scopes");
        when(client.resolveIdentityAsync(any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewClient.TokenIdentity(USER, TENANT, APP)));
        when(client.computeProtectionScopesAsync(any(), any())).thenReturn(CompletableFuture.completedStage(scopes));
        when(client.processContentAsync(any(), any(), any(Boolean.class), any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewDecision(
                        true,
                        false,
                        List.of(new PurviewPolicyAction("restrictAccessAction", "block")),
                        "request-content")));
        try (PurviewPolicyEvaluator evaluator = new PurviewPolicyEvaluator(client, settings)) {
            PurviewEvaluationOutcome first = evaluator
                    .evaluateAsync(
                            List.of(Message.text(Role.USER, "secret")),
                            PurviewActivity.UPLOAD_TEXT,
                            "conversation-one",
                            null,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            PurviewEvaluationOutcome second = evaluator
                    .evaluateAsync(
                            List.of(Message.text(Role.USER, "secret again")),
                            PurviewActivity.UPLOAD_TEXT,
                            "conversation-one",
                            null,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(first.decision().blocked()).isTrue();
            assertThat(second.decision().blocked()).isTrue();
            assertThat(evaluator.cacheSize()).isEqualTo(1);
            verify(client, times(1)).computeProtectionScopesAsync(any(), any());
            verify(client, times(2)).processContentAsync(any(), any(), org.mockito.ArgumentMatchers.eq(true), any());
        }
    }

    @Test
    void evaluator_shouldBlockOfflineScopesWhenEitherActionComponentRequiresBlock() {
        assertThat(evaluateOffline(new PurviewPolicyAction("blockAccess", null)).blocked())
                .isTrue();
        assertThat(evaluateOffline(new PurviewPolicyAction("notifyUserAction", "block"))
                        .blocked())
                .isTrue();
        assertThat(evaluateOffline(new PurviewPolicyAction("restrictAccessAction", "block"))
                        .blocked())
                .isTrue();
    }

    @Test
    void evaluator_shouldNotBlockAllowWarnOrUnknownFutureActions() {
        assertThat(evaluateOffline(new PurviewPolicyAction("allow", null)).blocked())
                .isFalse();
        assertThat(evaluateOffline(new PurviewPolicyAction("warn", null)).blocked())
                .isFalse();
        assertThat(evaluateOffline(new PurviewPolicyAction("futureAction", "futureRestriction"))
                        .blocked())
                .isFalse();
    }

    @Test
    void evaluator_shouldUseValidatedCallerUserOverrideWhenDelegatedIdentityIsUnavailable() {
        PurviewSettings settings = settings(PurviewFailureMode.FAIL_CLOSED);
        PurviewClient client = mock(PurviewClient.class);
        when(client.resolveIdentityAsync(any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewClient.TokenIdentity(null, TENANT, APP)));
        when(client.computeProtectionScopesAsync(argThat(request -> USER.equals(request.userId())), any()))
                .thenReturn(CompletableFuture.completedStage(
                        new PurviewProtectionScopes(List.of(), null, "request-scopes")));
        when(client.recordContentActivityAsync(any(), any()))
                .thenReturn(CompletableFuture.completedStage(PurviewDecision.allow()));
        try (PurviewPolicyEvaluator evaluator = new PurviewPolicyEvaluator(client, settings)) {
            PurviewEvaluationOutcome outcome = evaluator
                    .evaluateAsync(
                            List.of(Message.text(Role.USER, "secret")),
                            PurviewActivity.UPLOAD_TEXT,
                            "conversation-one",
                            USER,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(outcome.userId()).isEqualTo(USER);
            verify(client).computeProtectionScopesAsync(argThat(request -> USER.equals(request.userId())), any());
        }
    }

    @Test
    void evaluator_shouldRejectNonCanonicalCallerUserGuid() {
        PurviewSettings settings = settings(PurviewFailureMode.FAIL_CLOSED);
        PurviewClient client = mock(PurviewClient.class);
        when(client.resolveIdentityAsync(any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewClient.TokenIdentity(null, TENANT, APP)));
        try (PurviewPolicyEvaluator evaluator = new PurviewPolicyEvaluator(client, settings)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator
                            .evaluateAsync(
                                    List.of(Message.text(Role.USER, "secret")),
                                    PurviewActivity.UPLOAD_TEXT,
                                    "conversation-one",
                                    "1-1-1-1-1",
                                    new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(PurviewException.class)
                    .rootCause()
                    .extracting(failure -> ((PurviewException) failure).serviceCode())
                    .isEqualTo("invalid_userId");
        }
    }

    @Test
    void evaluator_shouldInvalidateCachedScopesWhenOfflineProcessingReportsModification() throws Exception {
        PurviewSettings settings = settings(PurviewFailureMode.FAIL_CLOSED);
        PurviewClient client = mock(PurviewClient.class);
        PurviewAppLocation location = new PurviewAppLocation(PurviewLocationType.APPLICATION, APP);
        PurviewProtectionScopes scopes = new PurviewProtectionScopes(
                List.of(new PurviewProtectionScope(
                        Set.of(PurviewActivity.UPLOAD_TEXT),
                        PurviewExecutionMode.EVALUATE_OFFLINE,
                        List.of(location),
                        List.of())),
                "\"scope-one\"",
                "request-scopes");
        when(client.resolveIdentityAsync(any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewClient.TokenIdentity(USER, TENANT, APP)));
        when(client.computeProtectionScopesAsync(any(), any())).thenReturn(CompletableFuture.completedStage(scopes));
        when(client.processContentAsync(any(), any(), any(Boolean.class), any()))
                .thenReturn(CompletableFuture.completedStage(
                        new PurviewDecision(false, true, List.of(), "request-content")));
        try (PurviewPolicyEvaluator evaluator = new PurviewPolicyEvaluator(client, settings)) {
            evaluator
                    .evaluateAsync(
                            List.of(Message.text(Role.USER, "secret")),
                            PurviewActivity.UPLOAD_TEXT,
                            "conversation-one",
                            null,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (evaluator.cacheSize() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(evaluator.cacheSize()).isZero();
        }
    }

    private static PurviewSettings settings(PurviewFailureMode mode) {
        return PurviewSettings.builder()
                .authenticationProvider((request, cancellation) ->
                        CompletableFuture.failedStage(new AssertionError("mock client should resolve identity")))
                .appName("Test")
                .appVersion("1")
                .tenantId(TENANT)
                .appLocation(new PurviewAppLocation(PurviewLocationType.APPLICATION, APP))
                .failureMode(mode)
                .build();
    }

    private static PurviewDecision evaluateOffline(PurviewPolicyAction action) {
        PurviewSettings settings = settings(PurviewFailureMode.FAIL_CLOSED);
        PurviewClient client = mock(PurviewClient.class);
        PurviewProtectionScopes scopes = new PurviewProtectionScopes(
                List.of(new PurviewProtectionScope(
                        Set.of(PurviewActivity.UPLOAD_TEXT),
                        PurviewExecutionMode.EVALUATE_OFFLINE,
                        List.of(new PurviewAppLocation(PurviewLocationType.APPLICATION, APP)),
                        List.of(action))),
                "\"scope-one\"",
                "request-scopes");
        when(client.resolveIdentityAsync(any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewClient.TokenIdentity(USER, TENANT, APP)));
        when(client.computeProtectionScopesAsync(any(), any())).thenReturn(CompletableFuture.completedStage(scopes));
        when(client.processContentAsync(any(), any(), any(Boolean.class), any()))
                .thenReturn(CompletableFuture.completedStage(PurviewDecision.allow()));
        try (PurviewPolicyEvaluator evaluator = new PurviewPolicyEvaluator(client, settings)) {
            return evaluator
                    .evaluateAsync(
                            List.of(Message.text(Role.USER, "content")),
                            PurviewActivity.UPLOAD_TEXT,
                            "conversation-one",
                            null,
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join()
                    .decision();
        }
    }
}
