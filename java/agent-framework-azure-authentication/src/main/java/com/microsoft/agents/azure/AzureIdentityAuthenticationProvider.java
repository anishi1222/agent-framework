// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

final class AzureIdentityAuthenticationProvider implements AzureAuthenticationProvider {
    private final TokenCredential credential;

    AzureIdentityAuthenticationProvider(TokenCredential credential) {
        this.credential = Objects.requireNonNull(credential, "credential");
    }

    @Override
    public java.util.concurrent.CompletionStage<AzureAccessToken> getTokenAsync(
            AzureTokenRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        TokenRequestContext context =
                new TokenRequestContext().addScopes(request.scopes().toArray(String[]::new));
        if (request.tenantId() != null) {
            context.setTenantId(request.tenantId());
        }
        CompletableFuture<com.azure.core.credential.AccessToken> tokenFuture =
                credential.getToken(context).toFuture();
        CompletableFuture<AzureAccessToken> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            tokenFuture.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        }));
        tokenFuture.whenComplete((token, failure) -> {
            RunCancellationRegistration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                result.completeExceptionally(new AzureAuthenticationException("Azure authentication failed.", cause));
            } else if (token == null) {
                result.completeExceptionally(
                        new AzureAuthenticationException("Azure authentication returned no token."));
            } else {
                result.complete(new AzureAccessToken(
                        token.getToken(), token.getExpiresAt().toInstant()));
            }
        });
        return result.minimalCompletionStage();
    }
}
