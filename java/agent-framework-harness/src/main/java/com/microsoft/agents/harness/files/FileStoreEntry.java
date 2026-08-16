// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.Objects;

/**
 * Describes one direct file-store child.
 *
 * @param path normalized relative path
 * @param directory whether the entry is a directory
 */
public record FileStoreEntry(String path, boolean directory) {
    /** Creates a validated immutable entry. */
    public FileStoreEntry {
        path = Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank.");
        }
    }
}
