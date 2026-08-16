// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and decodes strict bounded AG-UI JSON, NDJSON, and SSE data frames.
 *
 * <p>Run input is closed and rejects unknown members, while recognized event envelopes retain safe
 * unknown additive fields for forward-compatible decode/encode round trips.
 */
public final class AGUIJsonCodec {
    private final AGUILimits limits;

    private final StrictAGUIJson json;

    /**
     * Creates a strict codec.
     *
     * @param limits mandatory parser and encoder limits
     */
    public AGUIJsonCodec(AGUILimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        json = new StrictAGUIJson(limits);
    }

    /**
     * Returns this codec's immutable limits.
     *
     * @return limits
     */
    public AGUILimits limits() {
        return limits;
    }

    /**
     * Decodes one strict standard event while retaining safe unknown additive envelope fields.
     *
     * @param utf8Json UTF-8 JSON bytes
     * @return event
     */
    public AGUIEvent decodeEvent(byte[] utf8Json) {
        return AGUIWireValues.decodeEvent(requireObject(json.parse(utf8Json), "event"));
    }

    /**
     * Encodes one standard event with deterministic member order.
     *
     * @param event event
     * @return UTF-8 JSON
     */
    public byte[] encodeEvent(AGUIEvent event) {
        return json.write(AGUIWireValues.encodeEvent(Objects.requireNonNull(event, "event")));
    }

    /**
     * Decodes one exact {@link RunAgentInput}.
     *
     * <p>Unknown input members are rejected with their names, and present optional identifiers and
     * names must be nonblank. Remove an unsupported member or upgrade to a version that declares it.
     *
     * @param utf8Json UTF-8 JSON bytes
     * @return run input
     */
    public RunAgentInput decodeRunAgentInput(byte[] utf8Json) {
        return AGUIWireValues.decodeRunAgentInput(requireObject(json.parse(utf8Json), "RunAgentInput"));
    }

    /**
     * Encodes one exact {@link RunAgentInput}.
     *
     * @param input run input
     * @return UTF-8 JSON
     */
    public byte[] encodeRunAgentInput(RunAgentInput input) {
        return json.write(AGUIWireValues.encodeRunAgentInput(Objects.requireNonNull(input, "input")));
    }

    /**
     * Decodes any bounded JSON value.
     *
     * @param utf8Json UTF-8 JSON bytes
     * @return immutable framework-owned value
     */
    public StateValue decodeValue(byte[] utf8Json) {
        return json.parse(utf8Json);
    }

    /**
     * Encodes any bounded framework-owned JSON value.
     *
     * @param value value
     * @return UTF-8 JSON
     */
    public byte[] encodeValue(StateValue value) {
        return json.write(value);
    }

    /**
     * Decodes newline-delimited event JSON.
     *
     * @param utf8Ndjson bounded UTF-8 NDJSON
     * @return immutable events
     */
    public List<AGUIEvent> decodeNdjson(byte[] utf8Ndjson) {
        Objects.requireNonNull(utf8Ndjson, "utf8Ndjson");
        if (utf8Ndjson.length > limits.maxRequestBytes()) {
            throw new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI NDJSON exceeds maxRequestBytes.");
        }
        String document = decodeUtf8(utf8Ndjson);
        String[] lines = document.split("\\n", -1);
        ArrayList<AGUIEvent> events = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (line.isBlank()) {
                throw new AGUIProtocolException(
                        AGUIErrorCode.MALFORMED_INPUT, "AG-UI NDJSON contains an empty event line.");
            }
            if (events.size() >= limits.maxEventsPerRun()) {
                throw new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI NDJSON exceeds maxEventsPerRun.");
            }
            events.add(decodeEvent(line.getBytes(StandardCharsets.UTF_8)));
        }
        return List.copyOf(events);
    }

    /**
     * Encodes events as newline-delimited JSON.
     *
     * @param events ordered events
     * @return UTF-8 NDJSON with a final newline
     */
    public byte[] encodeNdjson(List<? extends AGUIEvent> events) {
        List<? extends AGUIEvent> checked = AGUIValidation.list(events, "events");
        if (checked.size() > limits.maxEventsPerRun()) {
            throw new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI event list exceeds maxEventsPerRun.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (AGUIEvent event : checked) {
            byte[] encoded = encodeEvent(event);
            if ((long) output.size() + encoded.length + 1 > limits.maxResponseBytes()) {
                throw new AGUIProtocolException(
                        AGUIErrorCode.LIMIT_EXCEEDED, "Encoded AG-UI NDJSON exceeds maxResponseBytes.");
            }
            output.writeBytes(encoded);
            output.write('\n');
        }
        return output.toByteArray();
    }

    /**
     * Encodes the exact official JSON SSE frame form {@code data: JSON\n\n}.
     *
     * @param event event
     * @return UTF-8 SSE frame
     */
    public byte[] encodeSseFrame(AGUIEvent event) {
        byte[] encoded = encodeEvent(event);
        byte[] prefix = "data: ".getBytes(StandardCharsets.US_ASCII);
        int length = Math.addExact(Math.addExact(prefix.length, encoded.length), 2);
        if (length > limits.maxSseFrameBytes()) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.LIMIT_EXCEEDED, "Encoded AG-UI SSE frame exceeds maxSseFrameBytes.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        output.writeBytes(prefix);
        output.writeBytes(encoded);
        output.write('\n');
        output.write('\n');
        return output.toByteArray();
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new AGUIProtocolException(AGUIErrorCode.MALFORMED_INPUT, name + " must be a JSON object.");
    }

    static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.MALFORMED_INPUT, "AG-UI input is not valid UTF-8.", exception);
        }
    }
}
