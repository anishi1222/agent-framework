// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Contains attachment bytes returned by an injected asynchronous fetcher.
 *
 * @param data attachment bytes
 * @param mediaType attachment media type
 */
public record ChatKitFetchedAttachment(byte[] data, String mediaType) {

    /** Defensively copies and validates fetched attachment data. */
    public ChatKitFetchedAttachment {
        data = Objects.requireNonNull(data, "data").clone();
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        if (mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank.");
        }
    }

    /** Returns a defensive copy of the attachment bytes. */
    @Override
    public byte[] data() {
        return data.clone();
    }
}
