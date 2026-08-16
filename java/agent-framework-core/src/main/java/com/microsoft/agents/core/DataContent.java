// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Represents inline binary media with a required media type.
 */
public final class DataContent implements Content {
    private final byte[] data;

    private final String mediaType;

    private final Map<String, StateValue> metadata;

    /**
     * Creates inline binary content and defensively copies the supplied bytes.
     *
     * @param data binary data
     * @param mediaType non-blank media type
     * @param metadata immutable additive metadata
     */
    public DataContent(byte[] data, String mediaType, Map<String, StateValue> metadata) {
        Objects.requireNonNull(data, "data");
        this.data = data.clone();
        this.mediaType = CoreValidation.requireNonBlank(mediaType, "mediaType");
        this.metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates inline binary content without metadata.
     *
     * @param data binary data
     * @param mediaType non-blank media type
     */
    public DataContent(byte[] data, String mediaType) {
        this(data, mediaType, Map.of());
    }

    /**
     * Decodes a base64 data URI.
     *
     * @param uri base64 data URI
     * @return decoded data content
     * @throws ValidationException when the URI is not a supported base64 data URI
     */
    public static DataContent fromDataUri(String uri) {
        CoreValidation.requireNonBlank(uri, "uri");
        int comma = uri.indexOf(',');
        if (!uri.startsWith("data:") || comma < 0) {
            throw new ValidationException("uri must be a data URI.");
        }
        String descriptor = uri.substring(5, comma);
        if (!descriptor.endsWith(";base64")) {
            throw new ValidationException("Only base64 data URIs are supported.");
        }
        String mediaType = descriptor.substring(0, descriptor.length() - ";base64".length());
        try {
            return new DataContent(Base64.getDecoder().decode(uri.substring(comma + 1)), mediaType);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Data URI contains invalid base64 data.", exception);
        }
    }

    /**
     * Returns a defensive copy of the binary data.
     *
     * @return copied bytes
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * Returns the media type.
     *
     * @return media type
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * Returns a stable base64 data URI.
     *
     * @return data URI
     */
    public URI dataUri() {
        String encoded = Base64.getEncoder().encodeToString(data);
        return URI.create("data:" + mediaType + ";base64," + encoded);
    }

    @Override
    public String kind() {
        return "data";
    }

    @Override
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof DataContent content
                        && Arrays.equals(data, content.data)
                        && mediaType.equals(content.mediaType)
                        && metadata.equals(content.metadata);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(mediaType, metadata) + Arrays.hashCode(data);
    }
}
