// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.RunCancelledException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

final class OpenAIErrorMapper {
    private OpenAIErrorMapper() {}

    static RuntimeException map(RuntimeException failure) {
        if (failure instanceof OpenAIProviderException
                || failure instanceof OpenAIUnsupportedContentException
                || failure instanceof OpenAIStreamingBufferOverflowException
                || failure instanceof RunCancelledException) {
            return failure;
        }
        if (failure instanceof CancellationException) {
            return new RunCancelledException();
        }
        if (failure instanceof OpenAIServiceException serviceFailure) {
            int status = serviceFailure.statusCode();
            String requestId = firstHeader(serviceFailure, "x-request-id");
            String errorCode = serviceFailure
                    .code()
                    .map(OpenAIProviderException::safeIdentifier)
                    .orElse(null);
            if (status == 401 || status == 403) {
                return new OpenAIAuthenticationException(status, requestId, errorCode);
            }
            if (status == 429) {
                return new OpenAIRateLimitException(requestId, errorCode);
            }
            return new OpenAIHttpException(status, requestId, errorCode);
        }
        if (failure instanceof OpenAIIoException && hasTimeoutCause(failure)) {
            return new OpenAITimeoutException();
        }
        if (failure instanceof OpenAIInvalidDataException) {
            return new OpenAIProtocolException(
                    "OpenAI returned data that the pinned SDK could not decode.", null, "invalid_data");
        }
        if (failure instanceof com.openai.errors.OpenAIException) {
            return new OpenAISdkException("sdk_error");
        }
        if (hasTimeoutCause(failure)) {
            return new OpenAITimeoutException();
        }
        return new OpenAISdkException("transport_error");
    }

    private static String firstHeader(OpenAIServiceException failure, String name) {
        return failure.headers().values(name).stream()
                .findFirst()
                .map(OpenAIProviderException::safeIdentifier)
                .orElse(null);
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
}
