// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.microsoft.agents.providers.openai.OpenAITransport;

/**
 * Defines the framework-owned Responses protocol boundary for Microsoft Foundry.
 *
 * <p>The boundary contains no Azure SDK models. Injected transports are caller-owned unless
 * ownership is explicitly transferred to {@link FoundryChatClient}.
 */
public interface FoundryTransport extends OpenAITransport {}
