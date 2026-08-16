// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

/** Identifies the explicit network trust contract for an embedded hosting listener. */
public enum HostingTransportSecurity {
    /** Plain HTTP is accepted only on a loopback bind address. */
    LOOPBACK_HTTP,
    /**
     * The listener is reachable only through an operator-enforced trusted TLS terminating proxy.
     *
     * <p>The host validates forwarded HTTPS metadata but cannot create the network firewall or proxy
     * trust boundary for the application.
     */
    TRUSTED_TLS_PROXY
}
