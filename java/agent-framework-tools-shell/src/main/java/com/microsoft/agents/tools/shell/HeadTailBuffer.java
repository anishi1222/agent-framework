// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.nio.charset.StandardCharsets;

final class HeadTailBuffer {
    private final int capacity;
    private final byte[] head;
    private final byte[] tail;
    private int headSize;
    private int tailSize;
    private int tailWrite;
    private long totalBytes;
    private boolean headSealed;

    HeadTailBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive.");
        }
        this.capacity = capacity;
        head = new byte[capacity / 2];
        tail = new byte[capacity - head.length];
    }

    void append(byte[] value, int length) {
        for (int index = 0; index < length; index++) {
            append(value[index]);
        }
    }

    private void append(byte value) {
        totalBytes++;
        if (!headSealed && headSize < head.length) {
            head[headSize++] = value;
            return;
        }
        headSealed = true;
        if (tail.length == 0) {
            return;
        }
        tail[tailWrite] = value;
        tailWrite = (tailWrite + 1) % tail.length;
        if (tailSize < tail.length) {
            tailSize++;
        }
    }

    CapturedOutput capture() {
        byte[] tailBytes = orderedTail();
        if (totalBytes <= capacity) {
            byte[] combined = new byte[headSize + tailBytes.length];
            System.arraycopy(head, 0, combined, 0, headSize);
            System.arraycopy(tailBytes, 0, combined, headSize, tailBytes.length);
            return new CapturedOutput(new String(combined, StandardCharsets.UTF_8), false);
        }
        long dropped = totalBytes - headSize - tailBytes.length;
        String value = new String(head, 0, headSize, StandardCharsets.UTF_8)
                + "\n[... truncated "
                + dropped
                + " bytes ...]\n"
                + new String(tailBytes, StandardCharsets.UTF_8);
        return new CapturedOutput(value, true);
    }

    private byte[] orderedTail() {
        byte[] ordered = new byte[tailSize];
        if (tailSize == 0) {
            return ordered;
        }
        int start = tailSize == tail.length ? tailWrite : 0;
        for (int index = 0; index < tailSize; index++) {
            ordered[index] = tail[(start + index) % tail.length];
        }
        return ordered;
    }

    record CapturedOutput(String text, boolean truncated) {}
}
