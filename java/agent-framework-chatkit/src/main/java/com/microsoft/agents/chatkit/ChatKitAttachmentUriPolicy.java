// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Applies an exact-host HTTPS allowlist before an attachment URI becomes agent content.
 *
 * <p>Only default-port HTTPS URIs without user information or fragments are accepted.
 */
public final class ChatKitAttachmentUriPolicy {
    private final Set<String> allowedHosts;

    private ChatKitAttachmentUriPolicy(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    /** Returns a policy that rejects every remote attachment URI. */
    public static ChatKitAttachmentUriPolicy denyAll() {
        return new ChatKitAttachmentUriPolicy(Set.of());
    }

    /**
     * Returns a policy allowing exact, case-insensitive HTTPS host names.
     *
     * @param hosts allowed host names without wildcards
     * @return an immutable URI policy
     */
    public static ChatKitAttachmentUriPolicy allowHttpsHosts(Collection<String> hosts) {
        Objects.requireNonNull(hosts, "hosts");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            Objects.requireNonNull(host, "hosts contains null");
            String value = host.strip().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || value.contains("*") || value.contains("/") || value.contains(":")) {
                throw new IllegalArgumentException("Allowed hosts must be exact host names.");
            }
            normalized.add(value);
        }
        return new ChatKitAttachmentUriPolicy(Set.copyOf(normalized));
    }

    /**
     * Determines whether a URI satisfies the HTTPS and exact-host policy.
     *
     * @param uri URI to evaluate
     * @return {@code true} when the URI is allowed
     */
    public boolean isAllowed(URI uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        return allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /** Returns the normalized exact-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }
}
