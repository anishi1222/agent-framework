// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Classifies Copilot Studio Activity protocol events.
 */
public enum CopilotStudioEventType {
    /** Assistant message activity. */
    MESSAGE,
    /** Incremental typing activity. */
    TYPING,
    /** Message update activity. */
    UPDATE,
    /** End-of-conversation activity. */
    END,
    /** Error or trace activity classified as an error. */
    ERROR,
    /** OAuth or sign-in card requiring explicit continuation. */
    OAUTH_REQUIRED,
    /** Adaptive Card input or action requiring explicit continuation. */
    INPUT_REQUIRED,
    /** Other retained activity. */
    OTHER
}
