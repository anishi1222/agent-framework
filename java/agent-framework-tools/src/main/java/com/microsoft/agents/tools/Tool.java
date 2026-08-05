// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.Set;

/**
 * Defines the provider-neutral contract implemented by every tool.
 */
public interface Tool {
    /**
     * Returns immutable tool metadata.
     *
     * @return immutable metadata
     */
    ToolMetadata metadata();

    /**
     * Returns the stable tool name.
     *
     * @return tool name
     */
    default String name() {
        return metadata().name();
    }

    /**
     * Returns the human-readable tool description.
     *
     * @return tool description
     */
    default String description() {
        return metadata().description();
    }

    /**
     * Returns the provider-neutral capabilities.
     *
     * @return immutable capability set
     */
    default Set<ToolCapability> capabilities() {
        return metadata().capabilities();
    }
}
