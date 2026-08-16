// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/**
 * Represents the Agent Framework capability document for one AG-UI endpoint.
 *
 * <p>This document is a namespaced framework extension, not an AG-UI core endpoint contract.
 *
 * @param schemaVersion advertised AG-UI schema version
 * @param sse whether HTTP/SSE runs are supported
 * @param resumeSupported whether opaque process-local resume is enabled
 * @param processLocal whether resume state is retained only in the server process
 * @param oneTime whether resume identifiers are consumed exactly once
 */
public record AGUIClientCapabilities(
        String schemaVersion, boolean sse, boolean resumeSupported, boolean processLocal, boolean oneTime) {
    /** Creates a validated capability document. */
    public AGUIClientCapabilities {
        schemaVersion = AGUIValidation.nonBlank(schemaVersion, "schemaVersion");
        if (resumeSupported && (!processLocal || !oneTime)) {
            throw AGUIValidation.invalid("Java AG-UI resume capability must be process-local and one-time.");
        }
    }
}
