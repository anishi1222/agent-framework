// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Describes the verified Copilot Studio Direct-to-Engine wire boundary.
 */
public final class CopilotStudioProtocol {
    /** Current API version used by the official .NET and Python Copilot Studio clients. */
    public static final String API_VERSION = "2022-03-01-preview";

    /** Official Python client release used to verify request and SSE fixture shapes. */
    public static final String VERIFIED_PYTHON_CLIENT_VERSION = "1.3.0";

    /** Media type used for activity streams. */
    public static final String EVENT_STREAM_MEDIA_TYPE = "text/event-stream";

    /** Response header carrying the active conversation identity. */
    public static final String CONVERSATION_ID_HEADER = "x-ms-conversationid";

    private CopilotStudioProtocol() {}
}
