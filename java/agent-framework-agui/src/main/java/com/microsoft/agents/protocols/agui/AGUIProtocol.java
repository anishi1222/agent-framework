// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Declares the pinned AG-UI protocol and transport metadata implemented by this module. */
public final class AGUIProtocol {
    /** Official TypeScript schema package used for conformance fixtures. */
    public static final String TYPESCRIPT_CORE_VERSION = "0.0.57";

    /** Official TypeScript SSE encoder package used for conformance fixtures. */
    public static final String TYPESCRIPT_ENCODER_VERSION = "0.0.57";

    /** Official TypeScript HTTP client package compared by runtime transport tests. */
    public static final String TYPESCRIPT_CLIENT_VERSION = "0.0.57";

    /** Official .NET SDK release compared by the conformance fixtures. */
    public static final String DOTNET_SDK_VERSION = "0.0.5";

    /** Community Java SDK release compared by the conformance fixtures. */
    public static final String COMMUNITY_JAVA_SDK_VERSION = "0.1.0";

    /** JSON request media type. */
    public static final String JSON_MEDIA_TYPE = "application/json";

    /** Server-Sent Events response media type. */
    public static final String SSE_MEDIA_TYPE = "text/event-stream";

    /** Newline-delimited JSON media type accepted by the codec utilities. */
    public static final String NDJSON_MEDIA_TYPE = "application/x-ndjson";

    private AGUIProtocol() {}
}
