// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Selects the generated-image format for the OpenAI Responses image-generation tool.
 */
public enum OpenAIImageOutputFormat {
    /** Generates PNG image data. */
    PNG("image/png"),
    /** Generates JPEG image data. */
    JPEG("image/jpeg"),
    /** Generates WebP image data. */
    WEBP("image/webp");

    private final String mediaType;

    OpenAIImageOutputFormat(String mediaType) {
        this.mediaType = mediaType;
    }

    /**
     * Returns the IANA media type for this format.
     *
     * @return image media type
     */
    public String mediaType() {
        return mediaType;
    }
}
