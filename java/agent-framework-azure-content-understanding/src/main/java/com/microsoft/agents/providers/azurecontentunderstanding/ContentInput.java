// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

/** Identifies safe URL and byte content inputs accepted by Content Understanding. */
public sealed interface ContentInput permits ContentUrlInput, ContentBytesInput {
    /** Returns the optional input name. */
    String name();

    /** Returns the required MIME type. */
    String mimeType();

    /** Returns the optional page or time range. */
    String contentRange();
}
