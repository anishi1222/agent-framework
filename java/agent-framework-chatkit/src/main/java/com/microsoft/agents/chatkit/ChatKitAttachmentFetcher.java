// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.concurrent.CompletionStage;

/** Asynchronously resolves trusted attachment bytes by framework-owned attachment metadata. */
@FunctionalInterface
public interface ChatKitAttachmentFetcher {

    /**
     * Fetches attachment bytes without logging credentials or content.
     *
     * @param attachment attachment metadata
     * @return a finite asynchronous result
     */
    CompletionStage<ChatKitFetchedAttachment> fetchAsync(ChatKitAttachment attachment);
}
