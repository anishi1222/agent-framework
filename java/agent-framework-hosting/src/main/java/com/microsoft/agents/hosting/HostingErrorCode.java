// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/**
 * Defines stable hosting error codes and their default HTTP and WebSocket mappings.
 */
public enum HostingErrorCode {
    /** The request syntax or strict JSON contract is invalid. */
    MALFORMED_REQUEST("malformed_request", 400, 1007),
    /** Authentication credentials are missing or invalid. */
    UNAUTHENTICATED("unauthenticated", 401, 1008),
    /** Authentication succeeded but authorization denied the operation. */
    FORBIDDEN("forbidden", 403, 1008),
    /** The route, run, or continuation was not found. */
    NOT_FOUND("not_found", 404, 1008),
    /** Current state conflicts with the requested operation. */
    CONFLICT("conflict", 409, 1008),
    /** The request method is not valid for the selected route. */
    METHOD_NOT_ALLOWED("method_not_allowed", 405, 1008),
    /** The requested response media type is unsupported. */
    NOT_ACCEPTABLE("not_acceptable", 406, 1003),
    /** The selected route requires a WebSocket upgrade. */
    UPGRADE_REQUIRED("upgrade_required", 426, 1002),
    /** A one-time continuation has already been consumed. */
    CONTINUATION_REPLAYED("continuation_replayed", 409, 1008),
    /** A process-local continuation expired. */
    CONTINUATION_EXPIRED("continuation_expired", 409, 1008),
    /** The request body or complete WebSocket message is too large. */
    PAYLOAD_TOO_LARGE("payload_too_large", 413, 1009),
    /** The request media type is unsupported. */
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", 415, 1003),
    /** The request is syntactically valid but cannot be executed. */
    UNPROCESSABLE("unprocessable", 422, 1007),
    /** A bounded request, run, or continuation capacity is exhausted. */
    TOO_MANY_REQUESTS("too_many_requests", 429, 1013),
    /** A stream or outbound queue exceeded a configured bound. */
    OVERFLOW("overflow", 429, 1009),
    /** The peer disconnected or explicitly cancelled the request. */
    CLIENT_CANCELLED("client_cancelled", 499, 1000),
    /** The configured logical run deadline elapsed. */
    RUN_TIMEOUT("run_timeout", 504, 1013),
    /** The host failed without exposing internal exception details. */
    INTERNAL_ERROR("internal_error", 500, 1011);

    private final String value;

    private final int httpStatus;

    private final int webSocketCloseCode;

    HostingErrorCode(String value, int httpStatus, int webSocketCloseCode) {
        this.value = value;
        this.httpStatus = httpStatus;
        this.webSocketCloseCode = webSocketCloseCode;
    }

    /**
     * Returns the stable wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }

    /**
     * Returns the default HTTP status.
     *
     * @return HTTP status
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Returns the default RFC 6455 close code.
     *
     * @return close code
     */
    public int webSocketCloseCode() {
        return webSocketCloseCode;
    }
}
