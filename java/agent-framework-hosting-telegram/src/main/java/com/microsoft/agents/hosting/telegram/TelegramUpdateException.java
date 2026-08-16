// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

final class TelegramUpdateException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final TelegramUpdateError error;

    TelegramUpdateException(TelegramUpdateError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    TelegramUpdateError error() {
        return error;
    }
}
