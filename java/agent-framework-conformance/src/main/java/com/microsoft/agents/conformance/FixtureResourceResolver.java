// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a classpath-like fixture resource for a manifest entry.
 */
@FunctionalInterface
public interface FixtureResourceResolver {
    /**
     * Opens a fixture resource.
     *
     * @param resourcePath classpath-relative resource path
     * @return resource stream, or {@code null} when absent
     * @throws IOException when the resource cannot be opened
     */
    InputStream open(String resourcePath) throws IOException;
}
