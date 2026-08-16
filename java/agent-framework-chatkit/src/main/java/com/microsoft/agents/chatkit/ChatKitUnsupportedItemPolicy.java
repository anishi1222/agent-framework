// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Controls handling of unsupported ChatKit thread-item discriminators. */
public enum ChatKitUnsupportedItemPolicy {
    /** Retain a bounded marker during decoding and omit it during conversion. */
    IGNORE,

    /** Reject an unsupported item immediately. */
    REJECT
}
