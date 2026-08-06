// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpResponse;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.providers.openai.OpenAIProtocolException;
import com.microsoft.agents.providers.openai.OpenAIProviderException;
import com.microsoft.agents.providers.openai.OpenAIStreamingBufferOverflowException;
import com.microsoft.agents.providers.openai.OpenAIUnsupportedContentException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class AzureOpenAIErrorMapper {
    private AzureOpenAIErrorMapper() {}

    static RuntimeException map(Throwable failure) {
        Throwable current = unwrap(failure);
        if (current instanceof RunCancelledException) {
            return (RunCancelledException) current;
        }
        if (current instanceof CancellationException) {
            return new RunCancelledException();
        }
        if (current instanceof AzureOpenAIProviderException providerFailure) {
            return providerFailure;
        }
        if (current instanceof OpenAIUnsupportedContentException) {
            return provider(
                    AzureOpenAIProviderException.Kind.UNSUPPORTED_CONTENT, null, null, null, "unsupported_content");
        }
        if (current instanceof OpenAIStreamingBufferOverflowException) {
            return provider(
                    AzureOpenAIProviderException.Kind.STREAM_OVERFLOW, null, null, null, "stream_buffer_overflow");
        }
        if (current instanceof OpenAIProtocolException protocol) {
            return provider(
                    AzureOpenAIProviderException.Kind.PROTOCOL,
                    null,
                    protocol.requestId().orElse(null),
                    null,
                    protocol.errorCode().orElse("invalid_response"));
        }
        if (current instanceof OpenAIProviderException openAI) {
            Integer status =
                    openAI.statusCode().isPresent() ? openAI.statusCode().getAsInt() : null;
            return provider(
                    kind(status),
                    status,
                    openAI.requestId().orElse(null),
                    null,
                    openAI.errorCode().orElse(null));
        }
        ClientAuthenticationException nestedAuthentication = findCause(current, ClientAuthenticationException.class);
        if (nestedAuthentication != null) {
            HttpResponse response = nestedAuthentication.getResponse();
            Integer status = response == null ? null : response.getStatusCode();
            return provider(
                    AzureOpenAIProviderException.Kind.AUTHENTICATION,
                    status,
                    header(response, "x-request-id", "apim-request-id", "x-ms-request-id"),
                    header(response, "x-ms-correlation-request-id", "trace-id"),
                    header(response, "x-ms-error-code"));
        }
        if (current instanceof HttpResponseException httpFailure) {
            HttpResponse response = httpFailure.getResponse();
            Integer status = response == null ? null : response.getStatusCode();
            return provider(
                    current instanceof ClientAuthenticationException
                            ? AzureOpenAIProviderException.Kind.AUTHENTICATION
                            : kind(status),
                    status,
                    header(response, "x-request-id", "apim-request-id", "x-ms-request-id"),
                    header(response, "x-ms-correlation-request-id", "trace-id"),
                    header(response, "x-ms-error-code"));
        }
        if (hasTimeoutCause(current)) {
            return provider(AzureOpenAIProviderException.Kind.TIMEOUT, null, null, null, "timeout");
        }
        return provider(AzureOpenAIProviderException.Kind.TRANSPORT, null, null, null, "transport_error");
    }

    private static AzureOpenAIProviderException provider(
            AzureOpenAIProviderException.Kind kind,
            Integer status,
            String requestId,
            String correlationId,
            String serviceCode) {
        StringBuilder message = new StringBuilder("Azure OpenAI request failed");
        if (status != null) {
            message.append(" with status ").append(status);
        }
        String safeRequestId = AzureOpenAIProviderException.safeIdentifier(requestId);
        if (safeRequestId != null) {
            message.append(" (request ").append(safeRequestId).append(')');
        }
        String safeServiceCode = AzureOpenAIProviderException.safeIdentifier(serviceCode);
        if (safeServiceCode != null) {
            message.append(" [code ").append(safeServiceCode).append(']');
        }
        message.append('.');
        return new AzureOpenAIProviderException(
                message.toString(), kind, status, requestId, correlationId, serviceCode);
    }

    private static AzureOpenAIProviderException.Kind kind(Integer status) {
        if (status != null && (status == 401 || status == 403)) {
            return AzureOpenAIProviderException.Kind.AUTHENTICATION;
        }
        if (status != null && status == 429) {
            return AzureOpenAIProviderException.Kind.RATE_LIMIT;
        }
        return AzureOpenAIProviderException.Kind.TRANSPORT;
    }

    private static String header(HttpResponse response, String... names) {
        if (response == null) {
            return null;
        }
        for (String name : names) {
            String value = response.getHeaders().getValue(HttpHeaderName.fromString(name));
            if (AzureOpenAIProviderException.safeIdentifier(value) != null) {
                return value;
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
