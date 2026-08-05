// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Converts one explicitly registered custom state type to and from safe {@link StateValue} data.
 *
 * @param <T> custom state type
 */
public interface StateCodec<T> {
    /**
     * Returns a stable lowercase package-qualified identifier that is not a Java class name.
     *
     * @return stable type identifier
     */
    String typeId();

    /**
     * Returns the positive current codec schema version.
     *
     * @return current codec version
     */
    int currentVersion();

    /**
     * Encodes a value using {@link #currentVersion()}.
     *
     * @param value state value
     * @return JSON-shaped encoded state
     */
    StateValue encode(T value);

    /**
     * Migrates one encoded value exactly from one version to the next requested version.
     *
     * @param value encoded value at {@code fromVersion}
     * @param fromVersion source version
     * @param toVersion destination version
     * @return migrated value
     * @throws SerializationException when that migration is unavailable
     */
    StateValue migrate(StateValue value, int fromVersion, int toVersion);

    /**
     * Decodes a value represented at the supplied supported version.
     *
     * @param value encoded value
     * @param version encoded version
     * @return decoded custom state
     */
    T decode(StateValue value, int version);
}
