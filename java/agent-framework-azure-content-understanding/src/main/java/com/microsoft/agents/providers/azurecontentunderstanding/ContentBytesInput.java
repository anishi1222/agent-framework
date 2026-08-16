// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.util.Arrays;

/** Provides in-memory content bytes with defensive copying and size-only diagnostics. */
public final class ContentBytesInput implements ContentInput {
    private final byte[] bytes;
    private final String name;
    private final String mimeType;
    private final String contentRange;

    /**
     * Creates a byte input.
     *
     * @param bytes non-empty content bytes
     * @param name optional name
     * @param mimeType required MIME type
     * @param contentRange optional page or time range
     */
    public ContentBytesInput(byte[] bytes, String name, String mimeType, String contentRange) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty.");
        }
        this.bytes = bytes.clone();
        this.name = optional(name, "name");
        this.mimeType = required(mimeType, "mimeType");
        this.contentRange = optional(contentRange, "contentRange");
    }

    /** Returns a defensive copy of the content bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    int byteLength() {
        return bytes.length;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public String contentRange() {
        return contentRange;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ContentBytesInput input
                        && Arrays.equals(bytes, input.bytes)
                        && java.util.Objects.equals(name, input.name)
                        && mimeType.equals(input.mimeType)
                        && java.util.Objects.equals(contentRange, input.contentRange);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(bytes);
        result = 31 * result + java.util.Objects.hash(name, mimeType, contentRange);
        return result;
    }

    @Override
    public String toString() {
        return "ContentBytesInput[bytes=<" + bytes.length + " bytes>, name=" + name + ", mimeType=" + mimeType
                + ", contentRange=" + contentRange + "]";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optional(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
