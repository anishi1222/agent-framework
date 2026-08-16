// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Identifies an attachment shape supported by this module. */
public enum ChatKitAttachmentKind {
    /** An image attachment that may provide an HTTPS preview URI. */
    IMAGE("image"),

    /** A file attachment whose bytes normally come from an injected fetcher. */
    FILE("file");

    private final String wireValue;

    ChatKitAttachmentKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns the ChatKit wire discriminator. */
    public String wireValue() {
        return wireValue;
    }

    static ChatKitAttachmentKind fromWireValue(String value) {
        for (ChatKitAttachmentKind kind : values()) {
            if (kind.wireValue.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unsupported ChatKit attachment type: " + value);
    }
}
