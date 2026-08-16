// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import com.microsoft.agents.hosting.http.HostingTransportSecurity;

/** Identifies the explicit network trust contract for the embedded developer UI. */
public enum DevUITransportSecurity {
    /** Plain HTTP is accepted only on a loopback bind address. */
    LOOPBACK_HTTP,

    /**
     * The listener is reachable only through an operator-enforced trusted TLS terminating proxy.
     */
    TRUSTED_TLS_PROXY;

    HostingTransportSecurity toHostingTransportSecurity() {
        return switch (this) {
            case LOOPBACK_HTTP -> HostingTransportSecurity.LOOPBACK_HTTP;
            case TRUSTED_TLS_PROXY -> HostingTransportSecurity.TRUSTED_TLS_PROXY;
        };
    }
}
