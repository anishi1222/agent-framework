// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Associates an immutable or detached snapshot with its opaque optimistic revision.
 *
 * @param <T> snapshot type
 * @param snapshot immutable or detached snapshot
 * @param revision positive storage revision returned by a successful write
 */
public record VersionedSnapshot<T>(T snapshot, long revision) {
    /** Creates a validated versioned snapshot. */
    public VersionedSnapshot {
        Objects.requireNonNull(snapshot, "snapshot");
        if (revision <= 0) {
            throw new ValidationException("revision must be greater than zero.");
        }
    }
}
