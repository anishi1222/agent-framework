// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Identifies a supported hidden-context wire discriminator. */
public enum ChatKitHiddenContextType {
    /** Hidden context supplied as a regular ChatKit thread item. */
    HIDDEN_CONTEXT_ITEM("hidden_context_item"),

    /** Hidden context supplied by the ChatKit server SDK contract. */
    SDK_HIDDEN_CONTEXT("sdk_hidden_context");

    private final String wireValue;

    ChatKitHiddenContextType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns the ChatKit wire discriminator. */
    public String wireValue() {
        return wireValue;
    }

    static ChatKitHiddenContextType fromWireValue(String value) {
        for (ChatKitHiddenContextType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported hidden-context type: " + value);
    }
}
