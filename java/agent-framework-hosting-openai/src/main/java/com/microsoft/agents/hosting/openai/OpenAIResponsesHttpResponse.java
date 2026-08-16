// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Represents one finite JSON, empty, or streaming OpenAI Responses HTTP response. */
public final class OpenAIResponsesHttpResponse {
    private final int status;

    private final Map<String, List<String>> headers;

    private final byte[] body;

    private final OpenAIResponsesHostedRun streamingRun;

    private OpenAIResponsesHttpResponse(
            int status, Map<String, List<String>> headers, byte[] body, OpenAIResponsesHostedRun streamingRun) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status.");
        }
        this.status = status;
        java.util.Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copied);
        this.body = java.util.Objects.requireNonNull(body, "body").clone();
        this.streamingRun = streamingRun;
        if (streamingRun != null && body.length != 0) {
            throw new IllegalArgumentException("Streaming OpenAI Responses response must not contain a finite body.");
        }
    }

    /**
     * Creates a finite response.
     *
     * @param status HTTP status
     * @param headers response headers
     * @param body response body
     * @return finite response
     */
    public static OpenAIResponsesHttpResponse finite(int status, Map<String, List<String>> headers, byte[] body) {
        return new OpenAIResponsesHttpResponse(status, headers, body, null);
    }

    /**
     * Creates an SSE response.
     *
     * @param headers response headers
     * @param run hosted streaming run
     * @return SSE response
     */
    public static OpenAIResponsesHttpResponse sse(Map<String, List<String>> headers, OpenAIResponsesHostedRun run) {
        return new OpenAIResponsesHttpResponse(200, headers, new byte[0], run);
    }

    /**
     * Returns the HTTP status.
     *
     * @return status
     */
    public int status() {
        return status;
    }

    /**
     * Returns immutable response headers.
     *
     * @return headers
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns a defensive finite body copy.
     *
     * @return body bytes
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Reports whether this response carries an SSE run.
     *
     * @return streaming state
     */
    public boolean isStreaming() {
        return streamingRun != null;
    }

    /**
     * Returns the hosted run, or {@code null} for finite responses.
     *
     * @return hosted run
     */
    public OpenAIResponsesHostedRun streamingRun() {
        return streamingRun;
    }
}
