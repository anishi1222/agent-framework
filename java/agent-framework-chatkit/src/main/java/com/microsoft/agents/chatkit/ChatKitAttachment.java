// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.net.URI;
import java.util.Objects;

/**
 * Describes a ChatKit attachment without exposing a browser or provider SDK type.
 *
 * @param id stable attachment identifier
 * @param kind supported attachment kind
 * @param name optional display name
 * @param mediaType attachment media type
 * @param previewUri optional remote preview URI
 */
public record ChatKitAttachment(String id, ChatKitAttachmentKind kind, String name, String mediaType, URI previewUri) {

    /** Validates and creates a ChatKit attachment. */
    public ChatKitAttachment {
        id = requireNonBlank(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        mediaType = requireNonBlank(mediaType, "mediaType");
        if (previewUri != null && !previewUri.isAbsolute()) {
            throw new IllegalArgumentException("previewUri must be absolute when present.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
