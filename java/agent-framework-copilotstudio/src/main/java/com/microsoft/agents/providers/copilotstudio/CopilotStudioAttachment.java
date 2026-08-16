// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.StateValue;
import java.net.URI;

/**
 * Represents one Activity protocol attachment.
 *
 * @param contentType MIME content type
 * @param name optional display name
 * @param contentUrl optional content URI
 * @param content JSON-shaped inline content
 * @param adaptiveCard parsed card when applicable
 */
public record CopilotStudioAttachment(
        String contentType, String name, URI contentUrl, StateValue content, CopilotStudioAdaptiveCard adaptiveCard) {
    /** Creates a validated attachment. */
    public CopilotStudioAttachment {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank.");
        }
        if (contentUrl != null
                && (!contentUrl.isAbsolute()
                        || contentUrl.getRawUserInfo() != null
                        || contentUrl.getRawFragment() != null)) {
            throw new IllegalArgumentException("contentUrl must be an absolute URI without user info or fragment.");
        }
        content = content == null ? StateValue.nullValue() : content;
    }
}
