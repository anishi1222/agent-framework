// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.util.Objects;

/**
 * Identifies the non-root user and group used inside a shell container.
 *
 * @param user numeric or named user
 * @param group numeric or named group
 */
public record ContainerUser(String user, String group) {
    /** Creates a validated container identity. */
    public ContainerUser {
        user = requireToken(user, "user");
        group = requireToken(group, "group");
    }

    /**
     * Returns the conventional unprivileged nobody identity.
     *
     * @return user and group {@code 65534}
     */
    public static ContainerUser defaultUser() {
        return new ContainerUser("65534", "65534");
    }

    /**
     * Returns the Docker user argument.
     *
     * @return {@code user:group}
     */
    @Override
    public String toString() {
        return user + ":" + group;
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.contains(":") || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must be one non-blank user token.");
        }
        return value;
    }
}
