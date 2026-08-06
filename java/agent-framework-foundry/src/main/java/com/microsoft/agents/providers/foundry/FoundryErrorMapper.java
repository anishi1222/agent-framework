// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpResponse;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.providers.openai.OpenAIProtocolException;
import com.microsoft.agents.providers.openai.OpenAIProviderException;
import com.microsoft.agents.providers.openai.OpenAIStreamingBufferOverflowException;
import com.microsoft.agents.providers.openai.OpenAIUnsupportedContentException;
import com.openai.errors.OpenAIServiceException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class FoundryErrorMapper {
    private FoundryErrorMapper() {}

    static RuntimeException map(Throwable failure) {
        Throwable current = unwrap(failure);
        if (current instanceof RunCancelledException) {
            return (RunCancelledException) current;
        }
        if (current instanceof CancellationException) {
            return new RunCancelledException();
        }
        if (current instanceof FoundryProviderException providerFailure) {
            return providerFailure;
        }
        if (current instanceof OpenAIUnsupportedContentException) {
            return provider(FoundryProviderException.Kind.UNSUPPORTED_CONTENT, null, null, null, "unsupported_content");
        }
        if (current instanceof OpenAIStreamingBufferOverflowException) {
            return provider(FoundryProviderException.Kind.STREAM_OVERFLOW, null, null, null, "stream_buffer_overflow");
        }
        if (current instanceof OpenAIProtocolException protocol) {
            return provider(
                    FoundryProviderException.Kind.PROTOCOL,
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
                    FoundryProviderException.Kind.AUTHENTICATION,
                    status,
                    header(response, "x-request-id", "apim-request-id", "x-ms-request-id"),
                    header(response, "x-ms-correlation-request-id", "trace-id"),
                    header(response, "x-ms-error-code"));
        }
        if (current instanceof OpenAIServiceException serviceFailure) {
            int status = serviceFailure.statusCode();
            return provider(
                    kind(status),
                    status,
                    firstOpenAIHeader(serviceFailure, "x-request-id", "apim-request-id", "x-ms-request-id"),
                    firstOpenAIHeader(serviceFailure, "x-ms-correlation-request-id", "trace-id"),
                    serviceFailure
                            .code()
                            .map(FoundryProviderException::safeIdentifier)
                            .orElse(null));
        }
        if (current instanceof HttpResponseException httpFailure) {
            HttpResponse response = httpFailure.getResponse();
            Integer status = response == null ? null : response.getStatusCode();
            return provider(
                    current instanceof ClientAuthenticationException
                            ? FoundryProviderException.Kind.AUTHENTICATION
                            : kind(status),
                    status,
                    header(response, "x-request-id", "apim-request-id", "x-ms-request-id"),
                    header(response, "x-ms-correlation-request-id", "trace-id"),
                    header(response, "x-ms-error-code"));
        }
        if (hasTimeoutCause(current)) {
            return provider(FoundryProviderException.Kind.TIMEOUT, null, null, null, "timeout");
        }
        return provider(FoundryProviderException.Kind.TRANSPORT, null, null, null, "transport_error");
    }

    static FoundryProviderException unsupported(String code) {
        return provider(FoundryProviderException.Kind.UNSUPPORTED_OPERATION, null, null, null, code);
    }

    static FoundryProviderException streamOverflow() {
        return provider(FoundryProviderException.Kind.STREAM_OVERFLOW, null, null, null, "stream_buffer_overflow");
    }

    private static FoundryProviderException provider(
            FoundryProviderException.Kind kind,
            Integer status,
            String requestId,
            String correlationId,
            String serviceCode) {
        StringBuilder message = new StringBuilder("Microsoft Foundry request failed");
        if (status != null) {
            message.append(" with status ").append(status);
        }
        String safeRequestId = FoundryProviderException.safeIdentifier(requestId);
        if (safeRequestId != null) {
            message.append(" (request ").append(safeRequestId).append(')');
        }
        String safeServiceCode = FoundryProviderException.safeIdentifier(serviceCode);
        if (safeServiceCode != null) {
            message.append(" [code ").append(safeServiceCode).append(']');
        }
        message.append('.');
        return new FoundryProviderException(message.toString(), kind, status, requestId, correlationId, serviceCode);
    }

    private static FoundryProviderException.Kind kind(Integer status) {
        if (status != null && (status == 401 || status == 403)) {
            return FoundryProviderException.Kind.AUTHENTICATION;
        }
        if (status != null && status == 429) {
            return FoundryProviderException.Kind.RATE_LIMIT;
        }
        return FoundryProviderException.Kind.TRANSPORT;
    }

    private static String firstOpenAIHeader(OpenAIServiceException failure, String... names) {
        for (String name : names) {
            String value = failure.headers().values(name).stream().findFirst().orElse(null);
            if (FoundryProviderException.safeIdentifier(value) != null) {
                return value;
            }
        }
        return null;
    }

    private static String header(HttpResponse response, String... names) {
        if (response == null) {
            return null;
        }
        for (String name : names) {
            String value = response.getHeaders().getValue(HttpHeaderName.fromString(name));
            if (FoundryProviderException.safeIdentifier(value) != null) {
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
