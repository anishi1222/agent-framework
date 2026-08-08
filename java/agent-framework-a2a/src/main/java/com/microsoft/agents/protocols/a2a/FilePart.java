// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an inline file or external file URI.
 */
public final class FilePart implements Part {
    private final byte[] bytes;
    private final URI uri;
    private final String filename;
    private final String mediaType;
    private final Map<String, StateValue> metadata;

    private FilePart(byte[] bytes, URI uri, String filename, String mediaType, Map<String, StateValue> metadata) {
        if ((bytes == null) == (uri == null)) {
            throw new ValidationException("Exactly one of bytes or uri must be present.");
        }
        this.bytes = bytes == null ? null : bytes.clone();
        this.uri = uri == null ? null : A2AValidation.absoluteUri(uri, "uri");
        this.filename = A2AValidation.nonBlank(filename, "filename");
        this.mediaType = A2AValidation.nonBlank(mediaType, "mediaType");
        this.metadata = A2AValidation.metadata(metadata, "metadata");
    }

    /**
     * Creates inline file content.
     *
     * @param bytes file bytes
     * @param filename optional filename
     * @param mediaType media type
     * @param metadata metadata
     * @return file part
     */
    public static FilePart bytes(byte[] bytes, String filename, String mediaType, Map<String, StateValue> metadata) {
        return new FilePart(Objects.requireNonNull(bytes, "bytes"), null, filename, mediaType, metadata);
    }

    /**
     * Creates URI-addressed file content.
     *
     * @param uri absolute file URI
     * @param filename optional filename
     * @param mediaType media type
     * @param metadata metadata
     * @return file part
     */
    public static FilePart uri(URI uri, String filename, String mediaType, Map<String, StateValue> metadata) {
        return new FilePart(null, Objects.requireNonNull(uri, "uri"), filename, mediaType, metadata);
    }

    /**
     * Returns whether content is inline.
     *
     * @return {@code true} for byte content
     */
    public boolean inline() {
        return bytes != null;
    }

    /**
     * Returns copied inline bytes.
     *
     * @return bytes, or {@code null}
     */
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    /**
     * Returns the external URI.
     *
     * @return URI, or {@code null}
     */
    public URI uri() {
        return uri;
    }

    /**
     * Returns the optional filename.
     *
     * @return filename, or {@code null}
     */
    public String filename() {
        return filename;
    }

    @Override
    public String mediaType() {
        return mediaType;
    }

    @Override
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof FilePart part
                        && Arrays.equals(bytes, part.bytes)
                        && Objects.equals(uri, part.uri)
                        && Objects.equals(filename, part.filename)
                        && mediaType.equals(part.mediaType)
                        && metadata.equals(part.metadata);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(uri, filename, mediaType, metadata) + Arrays.hashCode(bytes);
    }
}
