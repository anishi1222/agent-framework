// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.protocols.mcp.MCPProtocolException;

final class MCPSkillErrors {
    private static final int RESOURCE_NOT_FOUND = -32002;
    private static final int METHOD_NOT_FOUND = -32601;

    private MCPSkillErrors() {}

    static boolean isNotFound(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        return cause instanceof MCPProtocolException protocol
                && (protocol.code() == RESOURCE_NOT_FOUND || protocol.code() == METHOD_NOT_FOUND);
    }
}
