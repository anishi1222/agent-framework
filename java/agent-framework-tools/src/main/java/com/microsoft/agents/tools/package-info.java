// Copyright (c) Microsoft. All rights reserved.

/**
 * Provides provider-neutral tool metadata, safe schema binding, approval authority, durable ledger
 * hooks, and the function-invocation loop.
 *
 * <p>Function tools can be implemented explicitly with {@link
 * com.microsoft.agents.tools.FunctionTool#create(com.microsoft.agents.tools.ToolMetadata,
 * com.microsoft.agents.tools.FunctionToolHandler)} or discovered from public methods annotated with
 * {@link com.microsoft.agents.tools.ToolMethod}. Provider adapters integrate through {@link
 * com.microsoft.agents.tools.ToolTurnSource}; this package does not depend on a chat-client module.
 */
package com.microsoft.agents.tools;
