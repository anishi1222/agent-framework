// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Represents text or binary MCP resource contents.
 */
public sealed interface MCPResourceContents permits MCPResourceContents.Text, MCPResourceContents.Binary {
    /**
     * Returns the absolute resource URI.
     *
     * @return resource URI
     */
    URI uri();

    /**
     * Returns the optional media type.
     *
     * @return media type, or {@code null}
     */
    String mediaType();

    /**
     * Returns immutable metadata.
     *
     * @return metadata
     */
    Map<String, StateValue> metadata();

    /**
     * Represents textual resource contents.
     *
     * @param uri absolute resource URI
     * @param mediaType optional media type
     * @param text text contents
     * @param metadata immutable metadata
     */
    record Text(URI uri, String mediaType, String text, Map<String, StateValue> metadata)
            implements MCPResourceContents {
        /** Creates immutable text resource contents. */
        public Text {
            validateUri(uri);
            mediaType = MCPValidation.optionalNonBlank(mediaType, "mediaType");
            Objects.requireNonNull(text, "text");
            metadata = MCPValidation.copyMap(metadata, "metadata");
        }
    }

    /**
     * Represents binary resource contents.
     */
    final class Binary implements MCPResourceContents {
        private final URI uri;

        private final String mediaType;

        private final byte[] data;

        private final Map<String, StateValue> metadata;

        /**
         * Creates binary contents with defensive byte copying.
         *
         * @param uri absolute resource URI
         * @param mediaType optional media type
         * @param data binary bytes
         * @param metadata immutable metadata
         */
        public Binary(URI uri, String mediaType, byte[] data, Map<String, StateValue> metadata) {
            validateUri(uri);
            this.uri = uri;
            this.mediaType = MCPValidation.optionalNonBlank(mediaType, "mediaType");
            this.data = Objects.requireNonNull(data, "data").clone();
            this.metadata = MCPValidation.copyMap(metadata, "metadata");
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public String mediaType() {
            return mediaType;
        }

        /**
         * Returns a defensive byte copy.
         *
         * @return resource bytes
         */
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Map<String, StateValue> metadata() {
            return metadata;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Binary binary
                            && uri.equals(binary.uri)
                            && Objects.equals(mediaType, binary.mediaType)
                            && Arrays.equals(data, binary.data)
                            && metadata.equals(binary.metadata);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(uri, mediaType, metadata) + Arrays.hashCode(data);
        }
    }

    private static void validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new com.microsoft.agents.core.ValidationException("resource uri must be absolute.");
        }
    }
}
