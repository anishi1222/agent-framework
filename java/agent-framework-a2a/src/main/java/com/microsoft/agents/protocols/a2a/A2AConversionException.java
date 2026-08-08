// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Reports an unsupported or lossy framework/A2A content conversion. */
public final class A2AConversionException extends A2AException {
    private static final long serialVersionUID = 1L;

    /** Creates a conversion failure. */
    public A2AConversionException(String message) {
        super(message);
    }

    /** Creates a conversion failure with an underlying cause. */
    public A2AConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
