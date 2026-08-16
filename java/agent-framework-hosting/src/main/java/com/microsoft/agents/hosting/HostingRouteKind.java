// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Identifies a hosted execution target family. */
public enum HostingRouteKind {
    /** A provider-neutral agent target. */
    AGENT("agents"),
    /** A provider-neutral workflow target. */
    WORKFLOW("workflows"),
    /** A provider-neutral orchestration target. */
    ORCHESTRATION("orchestrations");

    private final String pathSegment;

    HostingRouteKind(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    /**
     * Returns the stable collection path segment.
     *
     * @return path segment
     */
    public String pathSegment() {
        return pathSegment;
    }

    /**
     * Resolves a collection path segment.
     *
     * @param value path segment
     * @return route kind
     */
    public static HostingRouteKind fromPathSegment(String value) {
        for (HostingRouteKind kind : values()) {
            if (kind.pathSegment.equals(value)) {
                return kind;
            }
        }
        throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting route collection was not found.");
    }
}
