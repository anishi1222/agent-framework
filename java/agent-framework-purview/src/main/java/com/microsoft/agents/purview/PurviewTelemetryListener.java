// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/** Receives privacy-safe Purview operation telemetry. */
@FunctionalInterface
public interface PurviewTelemetryListener {
    /** Records one operation event. */
    void record(PurviewTelemetryEvent event);

    /** Returns a no-op listener. */
    static PurviewTelemetryListener none() {
        return ignored -> {};
    }
}
