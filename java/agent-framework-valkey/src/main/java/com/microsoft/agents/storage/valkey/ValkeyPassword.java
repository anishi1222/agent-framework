// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.ValidationException;
import java.util.Arrays;

/** Holds an explicitly configured Valkey password and always renders it as redacted. */
public final class ValkeyPassword implements AutoCloseable {
    private final char[] value;

    private boolean closed;

    private ValkeyPassword(char[] value) {
        this.value = value;
    }

    /**
     * Copies a password into a redacting wrapper.
     *
     * @param value ACL password
     * @return password wrapper
     */
    public static ValkeyPassword of(String value) {
        return new ValkeyPassword(
                ValkeyValidation.boundedIdentifier(value, "value", 4096).toCharArray());
    }

    /**
     * Returns a short-lived String copy for the internal client factory.
     *
     * <p>Callers must not log, persist, or retain the returned value.
     *
     * @return secret password copy
     */
    public synchronized String secretValue() {
        if (closed) {
            throw new ValidationException("Valkey password has been closed.");
        }
        return new String(value);
    }

    /** Clears the wrapper's internal password copy. */
    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(value, '\0');
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ValkeyPassword[REDACTED]";
    }
}
