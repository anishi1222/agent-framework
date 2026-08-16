// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Identifies the author role of a message while allowing provider-neutral custom roles.
 */
public final class Role {
    /** The system instruction role. */
    public static final Role SYSTEM = new Role("system");

    /** The end-user role. */
    public static final Role USER = new Role("user");

    /** The assistant role. */
    public static final Role ASSISTANT = new Role("assistant");

    /** The tool-result role. */
    public static final Role TOOL = new Role("tool");

    private final String value;

    private Role(String value) {
        this.value = CoreValidation.requireNonBlank(value, "value");
    }

    /**
     * Creates a role from its stable textual value.
     *
     * @param value non-blank role value
     * @return a known singleton or a custom role
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws ValidationException when {@code value} is blank
     */
    public static Role of(String value) {
        return switch (CoreValidation.requireNonBlank(value, "value")) {
            case "system" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "tool" -> TOOL;
            default -> new Role(value);
        };
    }

    /**
     * Returns the stable role value.
     *
     * @return role value
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Role role && value.equals(role.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
