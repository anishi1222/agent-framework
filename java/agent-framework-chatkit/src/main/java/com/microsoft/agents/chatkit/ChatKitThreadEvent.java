// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Represents an outbound event in the supported ChatKit thread streaming subset. */
public sealed interface ChatKitThreadEvent
        permits ChatKitThreadItemAddedEvent, ChatKitThreadItemDoneEvent, ChatKitThreadItemUpdatedEvent {

    /** Returns the event wire discriminator. */
    String type();
}
