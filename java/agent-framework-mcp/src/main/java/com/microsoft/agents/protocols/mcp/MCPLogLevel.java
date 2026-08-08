// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Defines MCP logging severity levels.
 */
public enum MCPLogLevel {
    /** Debug detail. */
    DEBUG,
    /** Informational message. */
    INFO,
    /** Normal but significant condition. */
    NOTICE,
    /** Warning condition. */
    WARNING,
    /** Error condition. */
    ERROR,
    /** Critical condition. */
    CRITICAL,
    /** Immediate action is required. */
    ALERT,
    /** System is unusable. */
    EMERGENCY
}
