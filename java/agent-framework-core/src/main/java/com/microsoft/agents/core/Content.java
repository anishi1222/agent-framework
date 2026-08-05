// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;

/**
 * Represents one provider-neutral message content item.
 */
public sealed interface Content
        permits DataContent,
                ErrorContent,
                FunctionCallContent,
                FunctionResultContent,
                MetadataContent,
                ReasoningContent,
                TextContent,
                UriContent,
                UsageContent {
    /**
     * Returns the stable serialization discriminator.
     *
     * @return content kind
     */
    String kind();

    /**
     * Returns immutable additive metadata associated with the content.
     *
     * @return metadata, never {@code null}
     */
    Map<String, StateValue> metadata();
}
