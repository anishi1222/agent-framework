// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Classifies one transport authentication result. */
public enum HostingAuthenticationStatus {
    /** Authentication succeeded and produced trusted identity. */
    AUTHENTICATED,
    /** No acceptable credentials were presented. */
    UNAUTHENTICATED,
    /** Credentials were recognized but policy forbids this request. */
    FORBIDDEN
}
