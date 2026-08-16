// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

/** Selects how a framework conversation identifier is sent to Microsoft Foundry. */
public enum FoundryContinuationMode {
    /** Send the identifier as a Foundry/OpenAI conversation identifier. */
    CONVERSATION,
    /** Send the identifier as a previous Responses response identifier. */
    PREVIOUS_RESPONSE
}
