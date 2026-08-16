// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Signals that a hostile or non-compliant producer exceeded a bounded hosting bridge. */
public final class HostingStreamOverflowException extends HostingException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an overflow failure.
     *
     * @param capacity configured buffer capacity
     */
    public HostingStreamOverflowException(int capacity) {
        super(
                HostingErrorCode.OVERFLOW,
                "Hosted stream exceeded max buffered events " + HostingValidation.positive(capacity, "capacity") + ".");
    }
}
