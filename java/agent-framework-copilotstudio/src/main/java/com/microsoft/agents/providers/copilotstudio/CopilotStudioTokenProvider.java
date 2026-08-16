// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/**
 * Supplies cancellation-aware Entra tokens for the Power Platform audience.
 */
@FunctionalInterface
public interface CopilotStudioTokenProvider {
    /**
     * Gets a current access token.
     *
     * @param cancellation cancellation signal
     * @return token stage
     */
    CompletionStage<CopilotStudioAccessToken> getTokenAsync(RunCancellation cancellation);
}
