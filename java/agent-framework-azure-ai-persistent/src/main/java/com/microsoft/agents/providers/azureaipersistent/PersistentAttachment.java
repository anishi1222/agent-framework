// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;

/**
 * Associates one uploaded file with provider tools.
 *
 * @param fileId uploaded file identifier
 * @param toolKinds supported file-search or code-interpreter tool kinds
 */
public record PersistentAttachment(String fileId, List<PersistentToolKind> toolKinds) {
    /** Creates and validates an attachment. */
    public PersistentAttachment {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank.");
        }
        toolKinds = toolKinds == null ? List.of() : List.copyOf(toolKinds);
        if (toolKinds.isEmpty()) {
            throw new IllegalArgumentException("toolKinds must not be empty.");
        }
        if (toolKinds.stream()
                .anyMatch(kind ->
                        kind != PersistentToolKind.FILE_SEARCH && kind != PersistentToolKind.CODE_INTERPRETER)) {
            throw new IllegalArgumentException("Attachments support only FILE_SEARCH and CODE_INTERPRETER.");
        }
    }
}
