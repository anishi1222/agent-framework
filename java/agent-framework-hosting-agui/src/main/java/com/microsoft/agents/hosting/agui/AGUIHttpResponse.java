// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Represents one finite JSON, empty, or streaming AG-UI HTTP response. */
public final class AGUIHttpResponse {
    private final int status;

    private final Map<String, List<String>> headers;

    private final byte[] body;

    private final AGUIHostedRun streamingRun;

    private AGUIHttpResponse(int status, Map<String, List<String>> headers, byte[] body, AGUIHostedRun streamingRun) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status.");
        }
        this.status = status;
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copied);
        this.body = body.clone();
        this.streamingRun = streamingRun;
        if (streamingRun != null && body.length != 0) {
            throw new IllegalArgumentException("Streaming AG-UI response must not contain a finite body.");
        }
    }

    /**
     * Creates a finite response.
     *
     * @param status HTTP status
     * @param headers headers
     * @param body body bytes
     * @return response
     */
    public static AGUIHttpResponse finite(int status, Map<String, List<String>> headers, byte[] body) {
        return new AGUIHttpResponse(status, headers, body, null);
    }

    /**
     * Creates an SSE response.
     *
     * @param headers headers
     * @param run hosted run
     * @return response
     */
    public static AGUIHttpResponse sse(Map<String, List<String>> headers, AGUIHostedRun run) {
        return new AGUIHttpResponse(200, headers, new byte[0], run);
    }

    /**
     * Returns HTTP status.
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
     * @return body
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Reports whether this is an SSE response.
     *
     * @return streaming state
     */
    public boolean isStreaming() {
        return streamingRun != null;
    }

    /**
     * Returns the hosted run, or {@code null} for finite responses.
     *
     * @return run or {@code null}
     */
    public AGUIHostedRun streamingRun() {
        return streamingRun;
    }
}
