// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.tools.shell.ShellResult;
import java.nio.charset.StandardCharsets;

final class CodeActOutputBudget {
    private final int limit;
    private int capturedBytes;
    private boolean truncated;

    CodeActOutputBudget(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero.");
        }
        this.limit = limit;
    }

    BoundedOutput capture(ShellResult result) {
        CapturedText stdout = capture(result.stdout());
        CapturedText stderr = capture(result.stderr());
        boolean stepTruncated = result.truncated() || stdout.truncated() || stderr.truncated();
        truncated |= stepTruncated;
        return new BoundedOutput(stdout.value(), stderr.value(), stepTruncated);
    }

    int capturedBytes() {
        return capturedBytes;
    }

    boolean truncated() {
        return truncated;
    }

    private CapturedText capture(String value) {
        int remaining = limit - capturedBytes;
        if (remaining <= 0) {
            truncated |= !value.isEmpty();
            return new CapturedText("", !value.isEmpty());
        }
        int sourceBytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes <= remaining) {
            capturedBytes += sourceBytes;
            return new CapturedText(value, false);
        }

        StringBuilder bounded = new StringBuilder();
        int retainedBytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String encoded = new String(Character.toChars(codePoint));
            int encodedBytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (retainedBytes + encodedBytes > remaining) {
                break;
            }
            bounded.append(encoded);
            retainedBytes += encodedBytes;
            offset += Character.charCount(codePoint);
        }
        capturedBytes += retainedBytes;
        truncated = true;
        return new CapturedText(bounded.toString(), true);
    }

    record BoundedOutput(String stdout, String stderr, boolean truncated) {}

    private record CapturedText(String value, boolean truncated) {}
}
