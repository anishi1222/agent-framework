// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/** Provides an HTTPS content URL while redacting query material such as SAS tokens. */
public final class ContentUrlInput implements ContentInput {
    private final URI uri;
    private final String name;
    private final String mimeType;
    private final String contentRange;

    /**
     * Creates a URL input.
     *
     * @param uri public HTTPS content URI
     * @param name optional name
     * @param mimeType required MIME type
     * @param contentRange optional page or time range
     */
    public ContentUrlInput(URI uri, String name, String mimeType, String contentRange) {
        this.uri = validateUri(uri);
        this.name = optional(name, "name");
        this.mimeType = required(mimeType, "mimeType");
        this.contentRange = optional(contentRange, "contentRange");
    }

    /** Returns the content URI, which can include sensitive query material. */
    public URI uri() {
        return uri;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public String contentRange() {
        return contentRange;
    }

    @Override
    public String toString() {
        String redacted = uri.getQuery() == null ? uri.toString() : new URIBuilder(uri).withoutQuery() + "?[REDACTED]";
        return "ContentUrlInput[uri="
                + redacted
                + ", name="
                + name
                + ", mimeType="
                + mimeType
                + ", contentRange="
                + contentRange
                + "]";
    }

    private static URI validateUri(URI value) {
        Objects.requireNonNull(value, "uri");
        String host = value.getHost();
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || host == null
                || host.isBlank()
                || value.getUserInfo() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException("Content URL must be absolute HTTPS without user info or a fragment.");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".local")) {
            throw new IllegalArgumentException("Content URL must not target a local host.");
        }
        if (isLiteralAddress(normalized)) {
            try {
                InetAddress address = InetAddress.getByName(normalized);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Content URL must not target a private or local address.");
                }
            } catch (UnknownHostException failure) {
                throw new IllegalArgumentException("Content URL host is invalid.", failure);
            }
        }
        return value;
    }

    private static boolean isLiteralAddress(String value) {
        return value.indexOf(':') >= 0 || value.matches("[0-9.]+");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optional(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private record URIBuilder(URI value) {
        private String withoutQuery() {
            try {
                return new URI(value.getScheme(), null, value.getHost(), value.getPort(), value.getPath(), null, null)
                        .toString();
            } catch (java.net.URISyntaxException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
