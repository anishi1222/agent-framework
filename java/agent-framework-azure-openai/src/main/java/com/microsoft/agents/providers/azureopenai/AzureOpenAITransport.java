// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.providers.openai.OpenAITransport;

/**
 * Defines the framework-owned Responses protocol boundary for Azure OpenAI.
 *
 * <p>The boundary reuses Agent Framework's immutable OpenAI Responses protocol values while keeping
 * every Azure SDK model inside this adapter. Injected transports are caller-owned unless ownership is
 * explicitly transferred to {@link AzureOpenAIChatClient}.
 */
public interface AzureOpenAITransport extends OpenAITransport {}
