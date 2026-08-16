// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import java.net.URI;

/**
 * Describes one citation attached to a Copilot Studio activity.
 *
 * @param title optional source title
 * @param url optional source URI
 * @param source optional source identifier
 * @param startIndex optional inclusive text start
 * @param endIndex optional exclusive text end
 */
public record CopilotStudioCitation(String title, URI url, String source, Integer startIndex, Integer endIndex) {
    /** Creates validated citation offsets and URI. */
    public CopilotStudioCitation {
        if (url != null && (!url.isAbsolute() || url.getRawUserInfo() != null || url.getRawFragment() != null)) {
            throw new IllegalArgumentException("citation URL must be absolute without user info or fragment.");
        }
        if (startIndex != null && startIndex < 0) {
            throw new IllegalArgumentException("startIndex must not be negative.");
        }
        if (endIndex != null && (endIndex < 0 || startIndex != null && endIndex < startIndex)) {
            throw new IllegalArgumentException("endIndex must not precede startIndex.");
        }
    }
}
