// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Marks a validated MCP client transport configuration.
 */
public sealed interface MCPTransport permits MCPStdioTransport, MCPStreamableHTTPTransport {}
