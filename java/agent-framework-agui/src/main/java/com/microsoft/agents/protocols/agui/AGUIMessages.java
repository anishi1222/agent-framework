// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/** Contains the closed AG-UI message, tool-call, user-content, and multimodal value hierarchy. */
public final class AGUIMessages {
    private AGUIMessages() {}

    /** Closed marker implemented by every concrete AG-UI message. */
    public sealed interface Message extends AGUIMessage
            permits Developer, System, Assistant, User, Tool, Activity, Reasoning {}

    /** Closed marker implemented by every concrete user-content value. */
    public sealed interface UserContent extends AGUIUserContent permits TextUserContent, PartsUserContent {}

    /** Closed marker implemented by every concrete input part. */
    public sealed interface Input extends AGUIInputContent permits TextInput, MediaInput, LegacyBinaryInput {}

    /** Closed marker implemented by every concrete multimodal source. */
    public sealed interface InputSource extends AGUIInputSource permits DataSource, UrlSource {}

    /**
     * Represents function metadata inside a tool call.
     *
     * @param name function name
     * @param arguments JSON-encoded argument text
     */
    public record FunctionCall(String name, String arguments) {
        /** Creates a validated function call. */
        public FunctionCall {
            name = AGUIValidation.nonBlank(name, "name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * Represents an assistant tool call.
     *
     * @param id tool-call identifier
     * @param function function metadata
     * @param encryptedValue optional opaque encrypted reasoning
     */
    public record ToolCall(String id, FunctionCall function, String encryptedValue) {
        /** Creates a validated function-type tool call. */
        public ToolCall {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(function, "function");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        /**
         * Returns the fixed AG-UI tool-call discriminator.
         *
         * @return {@code function}
         */
        public String type() {
            return "function";
        }
    }

    /**
     * Represents a developer message.
     *
     * @param id identifier
     * @param content required text
     * @param name optional author name
     * @param encryptedValue optional opaque encrypted value
     */
    public record Developer(String id, String content, String name, String encryptedValue) implements Message {
        /** Creates a validated developer message. */
        public Developer {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(content, "content");
            name = AGUIValidation.optionalNonBlank(name, "name");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.DEVELOPER;
        }
    }

    /**
     * Represents a system message.
     *
     * @param id identifier
     * @param content required text
     * @param name optional author name
     * @param encryptedValue optional opaque encrypted value
     */
    public record System(String id, String content, String name, String encryptedValue) implements Message {
        /** Creates a validated system message. */
        public System {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(content, "content");
            name = AGUIValidation.optionalNonBlank(name, "name");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.SYSTEM;
        }
    }

    /**
     * Represents an assistant message.
     *
     * @param id identifier
     * @param content optional text
     * @param name optional author name
     * @param encryptedValue optional opaque encrypted value
     * @param toolCalls ordered tool calls
     */
    public record Assistant(String id, String content, String name, String encryptedValue, List<ToolCall> toolCalls)
            implements Message {
        /** Creates a validated assistant message. */
        public Assistant {
            id = AGUIValidation.nonBlank(id, "id");
            name = AGUIValidation.optionalNonBlank(name, "name");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
            toolCalls = AGUIValidation.list(toolCalls, "toolCalls");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.ASSISTANT;
        }
    }

    /**
     * Represents a user message.
     *
     * @param id identifier
     * @param content required string-or-parts content
     * @param name optional author name
     * @param encryptedValue optional opaque encrypted value
     */
    public record User(String id, AGUIUserContent content, String name, String encryptedValue) implements Message {
        /** Creates a validated user message. */
        public User {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(content, "content");
            name = AGUIValidation.optionalNonBlank(name, "name");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.USER;
        }
    }

    /**
     * Represents a tool result message.
     *
     * @param id identifier
     * @param content required result text
     * @param toolCallId originating tool-call identifier
     * @param error optional failure text
     * @param encryptedValue optional opaque encrypted value
     */
    public record Tool(String id, String content, String toolCallId, String error, String encryptedValue)
            implements Message {
        /** Creates a validated tool message. */
        public Tool {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(content, "content");
            toolCallId = AGUIValidation.nonBlank(toolCallId, "toolCallId");
            error = AGUIValidation.optionalNonBlank(error, "error");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.TOOL;
        }
    }

    /**
     * Represents a frontend-only activity message.
     *
     * @param id identifier
     * @param activityType activity discriminator
     * @param content structured activity state
     */
    public record Activity(String id, String activityType, StateValue.ObjectValue content) implements Message {
        /** Creates a validated activity message. */
        public Activity {
            id = AGUIValidation.nonBlank(id, "id");
            activityType = AGUIValidation.nonBlank(activityType, "activityType");
            content = AGUIValidation.object(content, "content");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.ACTIVITY;
        }
    }

    /**
     * Represents a reasoning message.
     *
     * @param id identifier
     * @param content visible reasoning summary
     * @param encryptedValue optional opaque protected reasoning
     */
    public record Reasoning(String id, String content, String encryptedValue) implements Message {
        /** Creates a validated reasoning message. */
        public Reasoning {
            id = AGUIValidation.nonBlank(id, "id");
            java.util.Objects.requireNonNull(content, "content");
            encryptedValue = AGUIValidation.optionalNonBlank(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIRole role() {
            return AGUIRole.REASONING;
        }
    }

    /**
     * Represents traditional string user content.
     *
     * @param text text, which may be empty
     */
    public record TextUserContent(String text) implements UserContent {
        /** Creates string user content. */
        public TextUserContent {
            java.util.Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Represents ordered multimodal user input.
     *
     * @param parts non-null ordered input parts
     */
    public record PartsUserContent(List<AGUIInputContent> parts) implements UserContent {
        /** Creates immutable user input parts. */
        public PartsUserContent {
            parts = AGUIValidation.list(parts, "parts");
        }
    }

    /**
     * Represents a text input part.
     *
     * @param text text, which may be empty
     */
    public record TextInput(String text) implements Input {
        /** Creates a text input part. */
        public TextInput {
            java.util.Objects.requireNonNull(text, "text");
        }

        @Override
        public String type() {
            return "text";
        }
    }

    /**
     * Represents an image, audio, video, or document input part.
     *
     * @param kind media kind
     * @param source inline-data or URL source
     * @param metadata optional immutable metadata
     */
    public record MediaInput(AGUIMediaKind kind, AGUIInputSource source, StateValue metadata) implements Input {
        /** Creates a media input part. */
        public MediaInput {
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(source, "source");
        }

        @Override
        public String type() {
            return kind.value();
        }
    }

    /**
     * Represents the deprecated TypeScript binary compatibility input.
     *
     * @param mimeType media type
     * @param id optional server-side identifier
     * @param url optional URL
     * @param data optional inline base64 data
     * @param filename optional filename
     */
    @Deprecated(forRemoval = true)
    public record LegacyBinaryInput(String mimeType, String id, String url, String data, String filename)
            implements Input {
        /** Creates a validated legacy binary input. */
        public LegacyBinaryInput {
            mimeType = AGUIValidation.nonBlank(mimeType, "mimeType");
            id = AGUIValidation.optionalNonBlank(id, "id");
            url = AGUIValidation.optionalNonBlank(url, "url");
            data = AGUIValidation.optionalNonBlank(data, "data");
            filename = AGUIValidation.optionalNonBlank(filename, "filename");
            if (id == null && url == null && data == null) {
                throw AGUIValidation.invalid("Legacy binary input requires id, url, or data.");
            }
        }

        @Override
        public String type() {
            return "binary";
        }
    }

    /**
     * Represents an inline base64 data source.
     *
     * @param value base64 payload
     * @param mimeType media type
     */
    public record DataSource(String value, String mimeType) implements InputSource {
        /** Creates an inline data source. */
        public DataSource {
            java.util.Objects.requireNonNull(value, "value");
            mimeType = AGUIValidation.nonBlank(mimeType, "mimeType");
        }

        @Override
        public String type() {
            return "data";
        }
    }

    /**
     * Represents a URL media source.
     *
     * @param value URL text
     * @param mimeType optional media type
     */
    public record UrlSource(String value, String mimeType) implements InputSource {
        /** Creates a URL source. */
        public UrlSource {
            value = AGUIValidation.nonBlank(value, "value");
            mimeType = AGUIValidation.optionalNonBlank(mimeType, "mimeType");
        }

        @Override
        public String type() {
            return "url";
        }
    }
}
