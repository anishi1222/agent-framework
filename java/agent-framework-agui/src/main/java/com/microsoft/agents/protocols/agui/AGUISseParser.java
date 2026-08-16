// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Incrementally parses strict AG-UI Server-Sent Events lines.
 *
 * <p>The parser accepts comments as heartbeats and standard {@code data} fields. It rejects {@code
 * id} and {@code retry} fields because this client deliberately does not claim replay or automatic
 * reconnect support.
 */
public final class AGUISseParser {
    private final AGUIJsonCodec codec;

    private final StringBuilder data = new StringBuilder();

    private int frameBytes;

    /**
     * Creates a parser backed by a strict codec.
     *
     * @param codec codec
     */
    public AGUISseParser(AGUIJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Accepts one decoded line without its line terminator.
     *
     * @param line SSE line
     * @return zero or one decoded event
     */
    public List<AGUIEvent> acceptLine(String line) {
        Objects.requireNonNull(line, "line");
        int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        frameBytes = Math.addExact(frameBytes, bytes);
        if (frameBytes > codec.limits().maxSseFrameBytes()) {
            throw new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI SSE frame exceeds maxSseFrameBytes.");
        }
        if (line.isEmpty()) {
            AGUIEvent event = dispatch();
            return event == null ? List.of() : List.of(event);
        }
        if (line.charAt(0) == ':') {
            return List.of();
        }
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "data" -> data.append(value).append('\n');
            case "event" -> {
                if (!value.isEmpty() && !"message".equals(value)) {
                    throw malformed("AG-UI SSE event field must be omitted or message.");
                }
            }
            case "id" -> throw malformed("AG-UI SSE replay identifiers are not supported.");
            case "retry" -> throw malformed("AG-UI SSE reconnect directives are not supported.");
            default -> {
                // Unknown SSE fields are ignored by the standard.
            }
        }
        return List.of();
    }

    /**
     * Dispatches a final data frame at end of stream when the server omitted the terminating blank
     * line.
     *
     * <p>This deliberately follows SSE end-of-file dispatch semantics: a buffered, syntactically
     * complete JSON event is returned exactly once, while incomplete JSON fails decoding. Calling
     * this method after a normally blank-line-terminated frame returns an empty list.
     *
     * @return zero or one decoded event
     */
    public List<AGUIEvent> finish() {
        AGUIEvent event = dispatch();
        return event == null ? List.of() : List.of(event);
    }

    private AGUIEvent dispatch() {
        frameBytes = 0;
        if (data.isEmpty()) {
            return null;
        }
        data.setLength(data.length() - 1);
        byte[] json = data.toString().getBytes(StandardCharsets.UTF_8);
        data.setLength(0);
        return codec.decodeEvent(json);
    }

    private static AGUIProtocolException malformed(String message) {
        return new AGUIProtocolException(AGUIErrorCode.MALFORMED_INPUT, message);
    }
}
