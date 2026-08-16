// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Represents a thread item in the supported ChatKit wire subset. */
public sealed interface ChatKitThreadItem
        permits ChatKitAssistantMessageItem,
                ChatKitHiddenContextItem,
                ChatKitUnsupportedThreadItem,
                ChatKitUserMessageItem {

    /** Returns the stable item identifier. */
    String id();

    /** Returns the owning ChatKit thread identifier. */
    String threadId();

    /** Returns the wire discriminator for this item. */
    String type();
}
