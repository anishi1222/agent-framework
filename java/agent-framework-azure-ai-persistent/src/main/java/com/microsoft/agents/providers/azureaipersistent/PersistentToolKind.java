// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/** Identifies a persistent-agent tool family. */
public enum PersistentToolKind {
    /** Azure-hosted code interpreter. */
    CODE_INTERPRETER,
    /** Azure-hosted file search. */
    FILE_SEARCH,
    /** Caller-hosted function tool. */
    FUNCTION,
    /** Azure-hosted OpenAPI tool. */
    OPENAPI,
    /** Model Context Protocol tool, unsupported by the pinned persistent SDK. */
    MCP,
    /** A future or otherwise unsupported tool family. */
    UNSUPPORTED
}
