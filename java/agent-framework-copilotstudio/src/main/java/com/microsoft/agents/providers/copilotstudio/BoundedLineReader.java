// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class BoundedLineReader {
    private final InputStream input;

    private final int maximumBytes;

    BoundedLineReader(InputStream input, int maximumBytes) {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.maximumBytes = maximumBytes;
    }

    String readLine() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 1024));
        while (true) {
            int value = input.read();
            if (value < 0) {
                return output.size() == 0 ? null : decode(output);
            }
            if (value == '\n') {
                return decode(output);
            }
            if (output.size() >= maximumBytes) {
                throw new CopilotStudioException(
                        "Copilot Studio SSE line exceeds the configured limit.",
                        null,
                        CopilotStudioException.Kind.LIMIT,
                        null,
                        "line_bytes");
            }
            if (value != '\r') {
                output.write(value);
            }
        }
    }

    private static String decode(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }
}
