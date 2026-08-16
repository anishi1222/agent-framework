// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Identifies a current AG-UI multimodal user input kind. */
public enum AGUIMediaKind {
    /** Image input. */
    IMAGE("image"),
    /** Audio input. */
    AUDIO("audio"),
    /** Video input. */
    VIDEO("video"),
    /** Document input. */
    DOCUMENT("document");

    private final String value;

    AGUIMediaKind(String value) {
        this.value = value;
    }

    /**
     * Returns the exact wire discriminator.
     *
     * @return discriminator
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a media input discriminator.
     *
     * @param value wire value
     * @return media kind
     */
    public static AGUIMediaKind fromValue(String value) {
        for (AGUIMediaKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw AGUIValidation.invalid("Unknown AG-UI media input type.");
    }
}
