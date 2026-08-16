// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

/** Describes whether a strategy's configured limit was met. */
public enum CompactionLimitStatus {
    /** The strategy has no numeric budget or target. */
    NOT_APPLICABLE,
    /** The result satisfies the configured limit. */
    WITHIN_LIMIT,
    /** Required atomic or protected content alone exceeds the configured limit. */
    REQUIRED_CONTENT_EXCEEDS_LIMIT
}
