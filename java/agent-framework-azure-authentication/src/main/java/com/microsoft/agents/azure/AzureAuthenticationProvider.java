// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Acquires Azure access tokens through framework-owned asynchronous contracts. */
@FunctionalInterface
public interface AzureAuthenticationProvider {
    /**
     * Acquires a token for one request.
     *
     * @param request token request
     * @param cancellation caller-owned cancellation
     * @return token stage
     */
    CompletionStage<AzureAccessToken> getTokenAsync(AzureTokenRequest request, RunCancellation cancellation);
}
