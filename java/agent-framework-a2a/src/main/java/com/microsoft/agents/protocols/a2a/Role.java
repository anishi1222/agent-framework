// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Identifies an A2A message author. */
public enum Role {
    /** Message originated from the client or user. */
    ROLE_USER,
    /** Message originated from the agent. */
    ROLE_AGENT,
    /** Role was not recognized or was omitted by a non-conforming peer. */
    ROLE_UNSPECIFIED
}
