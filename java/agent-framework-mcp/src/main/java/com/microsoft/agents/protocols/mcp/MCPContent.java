// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Represents rich content returned by an MCP tool or prompt.
 */
public sealed interface MCPContent
        permits MCPContent.Text,
                MCPContent.Image,
                MCPContent.Audio,
                MCPContent.EmbeddedResource,
                MCPContent.ResourceLink {
    /**
     * Returns immutable MCP metadata.
     *
     * @return metadata
     */
    Map<String, StateValue> metadata();

    /**
     * Represents text content.
     *
     * @param text text, including the empty string
     * @param metadata immutable metadata
     */
    record Text(String text, Map<String, StateValue> metadata) implements MCPContent {
        /** Creates immutable text content. */
        public Text {
            Objects.requireNonNull(text, "text");
            metadata = MCPValidation.copyMap(metadata, "metadata");
        }

        /**
         * Creates text without metadata.
         *
         * @param text text
         */
        public Text(String text) {
            this(text, Map.of());
        }
    }

    /**
     * Represents base64-decoded image content.
     */
    final class Image implements MCPContent {
        private final byte[] data;

        private final String mediaType;

        private final Map<String, StateValue> metadata;

        /**
         * Creates image content with defensive byte copying.
         *
         * @param data image bytes
         * @param mediaType image media type
         * @param metadata immutable metadata
         */
        public Image(byte[] data, String mediaType, Map<String, StateValue> metadata) {
            this.data = Objects.requireNonNull(data, "data").clone();
            this.mediaType = MCPValidation.nonBlank(mediaType, "mediaType");
            this.metadata = MCPValidation.copyMap(metadata, "metadata");
        }

        /**
         * Returns a defensive byte copy.
         *
         * @return image bytes
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

        @Override
        public Map<String, StateValue> metadata() {
            return metadata;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Image image
                            && Arrays.equals(data, image.data)
                            && mediaType.equals(image.mediaType)
                            && metadata.equals(image.metadata);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(mediaType, metadata) + Arrays.hashCode(data);
        }
    }

    /**
     * Represents base64-decoded audio content.
     */
    final class Audio implements MCPContent {
        private final byte[] data;

        private final String mediaType;

        private final Map<String, StateValue> metadata;

        /**
         * Creates audio content with defensive byte copying.
         *
         * @param data audio bytes
         * @param mediaType audio media type
         * @param metadata immutable metadata
         */
        public Audio(byte[] data, String mediaType, Map<String, StateValue> metadata) {
            this.data = Objects.requireNonNull(data, "data").clone();
            this.mediaType = MCPValidation.nonBlank(mediaType, "mediaType");
            this.metadata = MCPValidation.copyMap(metadata, "metadata");
        }

        /**
         * Returns a defensive byte copy.
         *
         * @return audio bytes
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

        @Override
        public Map<String, StateValue> metadata() {
            return metadata;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Audio audio
                            && Arrays.equals(data, audio.data)
                            && mediaType.equals(audio.mediaType)
                            && metadata.equals(audio.metadata);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(mediaType, metadata) + Arrays.hashCode(data);
        }
    }

    /**
     * Represents resource contents embedded in a result.
     *
     * @param resource resource contents
     * @param metadata immutable metadata
     */
    record EmbeddedResource(MCPResourceContents resource, Map<String, StateValue> metadata) implements MCPContent {
        /** Creates immutable embedded-resource content. */
        public EmbeddedResource {
            Objects.requireNonNull(resource, "resource");
            metadata = MCPValidation.copyMap(metadata, "metadata");
        }
    }

    /**
     * Represents a link to an MCP resource.
     *
     * @param uri absolute resource URI
     * @param name display name
     * @param title optional title
     * @param description optional description
     * @param mediaType optional media type
     * @param size optional non-negative size
     * @param metadata immutable metadata
     */
    record ResourceLink(
            URI uri,
            String name,
            String title,
            String description,
            String mediaType,
            Long size,
            Map<String, StateValue> metadata)
            implements MCPContent {
        /** Creates immutable resource-link content. */
        public ResourceLink {
            Objects.requireNonNull(uri, "uri");
            if (!uri.isAbsolute()) {
                throw new com.microsoft.agents.core.ValidationException("resource link uri must be absolute.");
            }
            name = MCPValidation.nonBlank(name, "name");
            title = MCPValidation.optionalNonBlank(title, "title");
            description = Objects.requireNonNullElse(description, "");
            mediaType = MCPValidation.optionalNonBlank(mediaType, "mediaType");
            if (size != null && size < 0) {
                throw new com.microsoft.agents.core.ValidationException("size must be non-negative.");
            }
            metadata = MCPValidation.copyMap(metadata, "metadata");
        }
    }
}
