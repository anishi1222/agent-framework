// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.util.Set;

final class MistralRequestValidator {
    static final Role DEVELOPER = Role.of("developer");

    static final Set<String> PROVIDER_METADATA = Set.of("mistral.responseSchema", "mistral.safePrompt");

    private MistralRequestValidator() {}

    static void validate(ChatClientRequest request) {
        if (request.messages().isEmpty()) {
            throw new ValidationException("Mistral requests require at least one message.");
        }
        for (Message message : request.messages()) {
            validateMessage(message);
        }
        validateOptions(request.options());
        for (ToolMetadata tool : request.tools()) {
            if (!tool.capabilities().equals(Set.of(ToolCapability.FUNCTION))) {
                throw new ValidationException(
                        "Mistral supports only FUNCTION tool declarations; tool '" + tool.name() + "' is unsupported.");
            }
        }
        if (request.tools().isEmpty()
                && (request.toolMode() == ToolMode.REQUIRED
                        || request.options().toolChoice() == com.microsoft.agents.core.ToolChoice.REQUIRED)) {
            throw new ValidationException("Mistral required tool selection needs at least one tool.");
        }
    }

    private static void validateMessage(Message message) {
        Role role = message.role();
        if (role.equals(DEVELOPER)) {
            throw new ValidationException("Mistral Chat Completions does not support the developer role.");
        }
        if (!(role.equals(Role.SYSTEM)
                || role.equals(Role.USER)
                || role.equals(Role.ASSISTANT)
                || role.equals(Role.TOOL))) {
            throw new ValidationException("Mistral does not support message role '" + role.value() + "'.");
        }
        if (message.contents().isEmpty()) {
            throw new ValidationException("Mistral messages require at least one content item.");
        }
        for (Content content : message.contents()) {
            if (role.equals(Role.TOOL)) {
                if (!(content instanceof FunctionResultContent result)) {
                    throw unsupported(role, content);
                }
                if (!result.items().isEmpty()) {
                    throw new ValidationException("Mistral tool results do not support rich result items.");
                }
                continue;
            }
            if (content instanceof TextContent) {
                continue;
            }
            if (content instanceof FunctionCallContent) {
                if (!role.equals(Role.ASSISTANT)) {
                    throw unsupported(role, content);
                }
                continue;
            }
            if (content instanceof DataContent data) {
                if (!role.equals(Role.USER)
                        || !data.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                    throw unsupported(role, content);
                }
                continue;
            }
            if (content instanceof UriContent uri) {
                String mediaType = uri.mediaType();
                if (!role.equals(Role.USER)
                        || mediaType == null
                        || !mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                        || !"https".equalsIgnoreCase(uri.uri().getScheme())) {
                    throw unsupported(role, content);
                }
                continue;
            }
            throw unsupported(role, content);
        }
    }

    private static void validateOptions(ChatOptions options) {
        if (options.temperature() != null && options.temperature() > 1.0) {
            throw new ValidationException("Mistral temperature must be between 0 and 1.");
        }
        if (options.store() != null) {
            throw new ValidationException("Mistral does not support ChatOptions.store.");
        }
        if (options.conversationId() != null) {
            throw new ValidationException("Mistral does not support ChatOptions.conversationId.");
        }
        if (options.user() != null) {
            throw new ValidationException("Mistral does not support ChatOptions.user.");
        }
        for (var entry : options.metadata().entrySet()) {
            if (entry.getKey().startsWith("mistral.") && !PROVIDER_METADATA.contains(entry.getKey())) {
                throw new ValidationException("Unsupported Mistral metadata option '" + entry.getKey() + "'.");
            }
        }
        StructuredOutputSupport.resolve(options, "mistral.responseSchema");
        StateValue safePrompt = options.metadata().get("mistral.safePrompt");
        if (safePrompt != null && !(safePrompt instanceof StateValue.BooleanValue)) {
            throw new ValidationException("mistral.safePrompt must be a Boolean.");
        }
    }

    private static ValidationException unsupported(Role role, Content content) {
        return new ValidationException(
                "Mistral does not support content kind '" + content.kind() + "' for role '" + role.value() + "'.");
    }
}
