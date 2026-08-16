// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.hosting.HostingError;

final class TelegramDispatchException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final transient HostingError error;

    TelegramDispatchException(HostingError error) {
        super("Generic hosting did not produce a supported completed agent response.");
        this.error = error;
    }

    HostingError error() {
        return error;
    }
}
