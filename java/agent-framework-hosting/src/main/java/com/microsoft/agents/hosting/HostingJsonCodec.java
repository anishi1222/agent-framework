// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Implements the strict nonstandard Java-hosting JSON wire contract.
 *
 * <p>The codec rejects duplicate object members, trailing content, non-finite numbers, unknown
 * request members, unknown content discriminators, and configured parser-limit violations. It never
 * enables Java-class polymorphism and redacts credential-bearing values on output.
 */
public final class HostingJsonCodec {
    /** Stable wire-version marker for this nonstandard Java hosting API. */
    public static final String WIRE_VERSION = "java-hosting-2026-08-01";

    private final StrictHostingJson json;

    /**
     * Creates a codec using mandatory hosting limits.
     *
     * @param limits hosting limits
     */
    public HostingJsonCodec(HostingLimits limits) {
        json = new StrictHostingJson(Objects.requireNonNull(limits, "limits"));
    }

    /**
     * Decodes a complete strict run request.
     *
     * @param utf8Json request bytes
     * @return hosted request
     */
    public HostingRunRequest decodeRunRequest(byte[] utf8Json) {
        return decodeRunRequest(decodeObject(utf8Json));
    }

    /**
     * Decodes a nested strict run request.
     *
     * @param object request object
     * @return hosted request
     */
    public HostingRunRequest decodeRunRequest(StateValue.ObjectValue object) {
        return HostingWireValues.decodeRunRequest(Objects.requireNonNull(object, "object"), WIRE_VERSION);
    }

    /**
     * Decodes a complete strict resume request.
     *
     * @param utf8Json request bytes
     * @return resume request
     */
    public HostingResumeRequest decodeResumeRequest(byte[] utf8Json) {
        return decodeResumeRequest(decodeObject(utf8Json));
    }

    /**
     * Decodes a nested strict resume request.
     *
     * @param object request object
     * @return resume request
     */
    public HostingResumeRequest decodeResumeRequest(StateValue.ObjectValue object) {
        return HostingWireValues.decodeResumeRequest(Objects.requireNonNull(object, "object"), WIRE_VERSION);
    }

    /**
     * Decodes one complete JSON object under strict parser limits.
     *
     * @param utf8Json JSON bytes
     * @return object
     */
    public StateValue.ObjectValue decodeObject(byte[] utf8Json) {
        return HostingWireValues.requireObject(json.parse(utf8Json), "JSON request");
    }

    /**
     * Encodes one safe JSON-shaped value canonically.
     *
     * @param value value
     * @return compact UTF-8 JSON
     */
    public byte[] encodeValue(StateValue value) {
        return json.write(HostingRedactor.redact(Objects.requireNonNull(value, "value")));
    }

    /**
     * Encodes one route descriptor.
     *
     * @param descriptor descriptor
     * @return compact UTF-8 JSON
     */
    public byte[] encodeDescriptor(HostingRouteDescriptor descriptor) {
        return encodeValue(descriptorValue(descriptor));
    }

    /**
     * Encodes a descriptor collection.
     *
     * @param kind route kind
     * @param descriptors descriptors
     * @return compact UTF-8 JSON
     */
    public byte[] encodeDescriptors(HostingRouteKind kind, List<HostingRouteDescriptor> descriptors) {
        return encodeValue(descriptorsValue(kind, descriptors));
    }

    /**
     * Encodes one execution outcome.
     *
     * @param outcome outcome
     * @return compact UTF-8 JSON
     */
    public byte[] encodeOutcome(HostingOutcome outcome) {
        return encodePreparedValue(outcomeValue(outcome));
    }

    /**
     * Encodes one stream event.
     *
     * @param event event
     * @return compact UTF-8 JSON
     */
    public byte[] encodeEvent(HostingEvent event) {
        return encodePreparedValue(eventValue(event));
    }

    /**
     * Encodes one sanitized error envelope.
     *
     * @param error error
     * @return compact UTF-8 JSON
     */
    public byte[] encodeError(HostingError error) {
        return encodePreparedValue(errorValue(error));
    }

    /**
     * Encodes a framework-prepared wire value without applying a second recursive redaction pass.
     *
     * <p>This method exists for transport adapters that compose values returned by
     * {@link #outcomeValue(HostingOutcome)}, {@link #eventValue(HostingEvent)}, or
     * {@link #errorValue(HostingError)} into a larger framework-owned envelope. Those value factories
     * already redact application-controlled fields while intentionally retaining opaque,
     * principal-bound continuation tokens. Callers must not use this method for arbitrary
     * application values.
     *
     * @param value framework-prepared wire value
     * @return compact UTF-8 JSON
     */
    public byte[] encodePreparedValue(StateValue value) {
        return json.write(Objects.requireNonNull(value, "value"));
    }

    /**
     * Returns one descriptor wire value.
     *
     * @param descriptor descriptor
     * @return wire value
     */
    public StateValue.ObjectValue descriptorValue(HostingRouteDescriptor descriptor) {
        return HostingWireValues.descriptorValue(Objects.requireNonNull(descriptor, "descriptor"), WIRE_VERSION);
    }

    /**
     * Returns one descriptor-list wire value.
     *
     * @param kind route kind
     * @param descriptors descriptors
     * @return wire value
     */
    public StateValue.ObjectValue descriptorsValue(HostingRouteKind kind, List<HostingRouteDescriptor> descriptors) {
        return HostingWireValues.descriptorsValue(
                Objects.requireNonNull(kind, "kind"),
                List.copyOf(Objects.requireNonNull(descriptors, "descriptors")),
                WIRE_VERSION);
    }

    /**
     * Returns one outcome wire value.
     *
     * @param outcome outcome
     * @return wire value
     */
    public StateValue.ObjectValue outcomeValue(HostingOutcome outcome) {
        return HostingWireValues.outcomeValue(Objects.requireNonNull(outcome, "outcome"), WIRE_VERSION);
    }

    /**
     * Returns one event wire value.
     *
     * @param event event
     * @return wire value
     */
    public StateValue.ObjectValue eventValue(HostingEvent event) {
        return HostingWireValues.eventValue(Objects.requireNonNull(event, "event"), WIRE_VERSION);
    }

    /**
     * Returns one error wire value.
     *
     * @param error error
     * @return wire value
     */
    public StateValue.ObjectValue errorValue(HostingError error) {
        return HostingWireValues.errorValue(Objects.requireNonNull(error, "error"), WIRE_VERSION);
    }

    /**
     * Returns the sanitized error body used inside a larger transport envelope.
     *
     * @param error error
     * @return error body without another version/type envelope
     */
    public StateValue.ObjectValue errorBodyValue(HostingError error) {
        return (StateValue.ObjectValue) errorValue(error).values().get("error");
    }

    /**
     * Encodes bytes as UTF-8 text for an HTTP or WebSocket writer.
     *
     * @param bytes UTF-8 JSON bytes
     * @return text
     */
    public static String utf8(byte[] bytes) {
        return new String(Objects.requireNonNull(bytes, "bytes"), StandardCharsets.UTF_8);
    }
}
