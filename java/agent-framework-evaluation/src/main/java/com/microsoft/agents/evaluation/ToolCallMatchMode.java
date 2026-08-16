// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

/**
 * Specifies whether all or any requested tool names must be observed.
 */
public enum ToolCallMatchMode {
    /** Every requested tool must be called. */
    ALL,

    /** At least one requested tool must be called. */
    ANY
}
