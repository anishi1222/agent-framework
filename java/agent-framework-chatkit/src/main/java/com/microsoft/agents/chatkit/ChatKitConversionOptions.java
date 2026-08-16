// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Configures thread-item conversion.
 *
 * @param attachmentUriPolicy HTTPS URI policy used after byte fetching is unavailable or fails
 * @param unsupportedItemPolicy unsupported item handling
 * @param maxAttachmentBytes maximum accepted fetched attachment size
 * @param failOnAttachmentError whether attachment resolution failures fail the conversion
 */
public record ChatKitConversionOptions(
        ChatKitAttachmentUriPolicy attachmentUriPolicy,
        ChatKitUnsupportedItemPolicy unsupportedItemPolicy,
        int maxAttachmentBytes,
        boolean failOnAttachmentError) {

    /** Validates and creates conversion options. */
    public ChatKitConversionOptions {
        attachmentUriPolicy = Objects.requireNonNull(attachmentUriPolicy, "attachmentUriPolicy");
        unsupportedItemPolicy = Objects.requireNonNull(unsupportedItemPolicy, "unsupportedItemPolicy");
        if (maxAttachmentBytes <= 0) {
            throw new IllegalArgumentException("maxAttachmentBytes must be positive.");
        }
    }

    /** Returns secure defaults that deny remote URIs and ignore unsupported items. */
    public static ChatKitConversionOptions defaults() {
        return new ChatKitConversionOptions(
                ChatKitAttachmentUriPolicy.denyAll(), ChatKitUnsupportedItemPolicy.IGNORE, 8 * 1_024 * 1_024, false);
    }
}
