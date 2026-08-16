// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.providers.openai.OpenAITransport;

final class AzureOpenAIRequestValidation {
    private static final String CONVERSATION_PREFIX = "conv_";

    private AzureOpenAIRequestValidation() {}

    static void validate(ChatClientRequest request) {
        String continuation = request.options().conversationId();
        if (continuation != null && continuation.startsWith(CONVERSATION_PREFIX)) {
            throw unsupportedConversation();
        }
    }

    static void validate(OpenAITransport.Request request) {
        if (request.conversationId() != null) {
            throw unsupportedConversation();
        }
    }

    private static AzureOpenAIProviderException unsupportedConversation() {
        return new AzureOpenAIProviderException(
                "The configured Azure OpenAI API version does not support Responses conversations; "
                        + "use a previous response identifier instead.",
                AzureOpenAIProviderException.Kind.UNSUPPORTED_OPTION,
                null,
                null,
                null,
                "conversation_not_supported");
    }
}
