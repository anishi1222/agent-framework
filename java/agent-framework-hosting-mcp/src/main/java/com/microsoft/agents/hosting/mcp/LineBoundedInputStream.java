// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class LineBoundedInputStream extends FilterInputStream {
    private final int maximumLineBytes;

    private int currentLineBytes;

    LineBoundedInputStream(InputStream input, int maximumLineBytes) {
        super(input);
        this.maximumLineBytes = maximumLineBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        count(value);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = super.read(bytes, offset, length);
        for (int index = 0; index < count; index++) {
            count(bytes[offset + index] & 0xff);
        }
        return count;
    }

    private void count(int value) throws IOException {
        if (value == '\n' || value == -1) {
            currentLineBytes = 0;
            return;
        }
        currentLineBytes++;
        if (currentLineBytes > maximumLineBytes) {
            throw new IOException("MCP stdio message exceeds the configured payload limit.");
        }
    }
}
