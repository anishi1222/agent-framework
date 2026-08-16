// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Registers explicit state codecs by stable type identifier and applies sequential migrations.
 *
 * <p>The registry has no reflective or generic object fallback. A value can be encoded only by
 * naming a registered codec, so credentials, clients, executors, and other live resources are not
 * serializable accidentally.
 */
public final class StateCodecRegistry {
    private static final Pattern TYPE_ID = Pattern.compile("[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+");

    private final ConcurrentHashMap<String, StateCodec<?>> codecs = new ConcurrentHashMap<>();

    /**
     * Registers one codec exactly once.
     *
     * @param codec codec
     * @param <T> codec value type
     * @throws SerializationException for an invalid or duplicate type identifier or version
     */
    public <T> void register(StateCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        String typeId = CoreValidation.requireNonBlank(codec.typeId(), "codec.typeId()");
        if (!TYPE_ID.matcher(typeId).matches()) {
            throw new SerializationException(
                    SerializationError.DUPLICATE_CODEC,
                    "Codec typeId must be a stable lowercase package-qualified identifier: '" + typeId + "'.");
        }
        if (codec.currentVersion() <= 0) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION, "Codec currentVersion must be greater than zero.");
        }
        if (codecs.putIfAbsent(typeId, codec) != null) {
            throw new SerializationException(
                    SerializationError.DUPLICATE_CODEC, "Codec typeId '" + typeId + "' is already registered.");
        }
    }

    /**
     * Encodes a value with its registered codec.
     *
     * @param codec registered codec
     * @param value non-null state value
     * @param <T> codec value type
     * @return versioned encoded state
     */
    public <T> EncodedState encode(StateCodec<T> codec, T value) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(value, "value");
        StateCodec<?> registered = requireCodec(codec.typeId());
        if (registered != codec) {
            throw new SerializationException(
                    SerializationError.DUPLICATE_CODEC,
                    "Encoding requires the codec instance registered for typeId '" + codec.typeId() + "'.");
        }
        StateValue encoded = codec.encode(value);
        if (encoded == null) {
            throw new SerializationException(
                    SerializationError.MALFORMED_DOCUMENT, "Codec '" + codec.typeId() + "' returned null from encode.");
        }
        return new EncodedState(codec.typeId(), codec.currentVersion(), encoded);
    }

    /**
     * Migrates and decodes a registered encoded value.
     *
     * @param encoded encoded state
     * @param <T> expected decoded type
     * @return decoded state
     * @throws SerializationException for unknown types, future versions, or missing migrations
     */
    @SuppressWarnings("unchecked")
    public <T> T decode(EncodedState encoded) {
        Objects.requireNonNull(encoded, "encoded");
        StateCodec<T> codec = (StateCodec<T>) requireCodec(encoded.typeId());
        int currentVersion = codec.currentVersion();
        if (encoded.codecVersion() > currentVersion) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    "Codec value "
                            + encoded.typeId()
                            + " version "
                            + encoded.codecVersion()
                            + " is newer than supported version "
                            + currentVersion
                            + ".");
        }
        StateValue value = encoded.value();
        for (int version = encoded.codecVersion(); version < currentVersion; version++) {
            try {
                StateValue migrated = codec.migrate(value, version, version + 1);
                if (migrated == null) {
                    throw new SerializationException(
                            SerializationError.CODEC_MIGRATION,
                            "Codec '"
                                    + encoded.typeId()
                                    + "' returned null migrating version "
                                    + version
                                    + " to "
                                    + (version + 1)
                                    + ".");
                }
                value = migrated;
            } catch (UnsupportedOperationException exception) {
                throw new SerializationException(
                        SerializationError.CODEC_MIGRATION,
                        "Codec '"
                                + encoded.typeId()
                                + "' cannot migrate version "
                                + version
                                + " to "
                                + (version + 1)
                                + ".",
                        exception);
            }
        }
        T decoded = codec.decode(value, currentVersion);
        if (decoded == null) {
            throw new SerializationException(
                    SerializationError.MALFORMED_DOCUMENT,
                    "Codec '" + encoded.typeId() + "' returned null from decode.");
        }
        return decoded;
    }

    /**
     * Returns a snapshot of registered type identifiers.
     *
     * @return immutable type identifier set
     */
    public Set<String> registeredTypeIds() {
        return Collections.unmodifiableSet(Set.copyOf(codecs.keySet()));
    }

    private StateCodec<?> requireCodec(String typeId) {
        StateCodec<?> codec = codecs.get(typeId);
        if (codec == null) {
            throw new SerializationException(
                    SerializationError.UNKNOWN_TYPE_ID, "No codec is registered for typeId '" + typeId + "'.");
        }
        return codec;
    }
}
