// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/** Identifies a Microsoft Graph policy location type. */
public enum PurviewLocationType {
    /** Entra application identifier. */
    APPLICATION("microsoft.graph.policyLocationApplication"),
    /** Application URL. */
    URI("microsoft.graph.policyLocationUrl"),
    /** Application domain. */
    DOMAIN("microsoft.graph.policyLocationDomain");

    private final String odataType;

    PurviewLocationType(String odataType) {
        this.odataType = odataType;
    }

    /** Returns the Microsoft Graph OData type. */
    public String odataType() {
        return odataType;
    }
}
