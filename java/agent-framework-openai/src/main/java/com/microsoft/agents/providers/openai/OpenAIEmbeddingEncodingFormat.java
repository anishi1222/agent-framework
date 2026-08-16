// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/** Selects the wire encoding requested from the OpenAI embeddings API. */
public enum OpenAIEmbeddingEncodingFormat {
    /** Requests JSON floating-point values. */
    FLOAT,

    /** Requests base64-encoded little-endian IEEE 754 single-precision values. */
    BASE64
}
