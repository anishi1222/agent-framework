// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

/**
 * Selects Gemini Developer API or Vertex AI authentication.
 */
public enum GeminiAuthenticationMode {
    /** Uses an explicit Gemini API key. */
    API_KEY,

    /** Uses Google Application Default Credentials through the official SDK. */
    VERTEX_APPLICATION_DEFAULT
}
