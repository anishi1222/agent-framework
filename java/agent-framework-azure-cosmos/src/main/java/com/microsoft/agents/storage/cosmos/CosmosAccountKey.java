// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;
import java.util.Arrays;

/** Holds an explicitly configured Cosmos account key and always renders it as redacted. */
public final class CosmosAccountKey implements AutoCloseable {
    private final char[] value;

    private boolean closed;

    private CosmosAccountKey(char[] value) {
        this.value = value;
    }

    /**
     * Copies an account key into a redacting wrapper.
     *
     * @param value account key
     * @return key wrapper
     */
    public static CosmosAccountKey of(String value) {
        return new CosmosAccountKey(
                CosmosValidation.requireNonBlank(value, "value").toCharArray());
    }

    /**
     * Returns a short-lived String copy for an SDK builder.
     *
     * <p>Callers must not log, persist, or retain the returned value. Prefer RBAC authentication.
     *
     * @return secret key copy
     */
    public synchronized String secretValue() {
        if (closed) {
            throw new ValidationException("Cosmos account key has been closed.");
        }
        return new String(value);
    }

    /**
     * Clears the wrapper's internal key copy.
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(value, '\0');
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "CosmosAccountKey[REDACTED]";
    }
}
