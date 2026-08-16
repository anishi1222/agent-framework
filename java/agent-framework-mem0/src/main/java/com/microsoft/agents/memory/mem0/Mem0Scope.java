// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Defines an explicit Mem0 identity scope using one or more app, user, agent, or run identifiers.
 */
public final class Mem0Scope {
    private static final int MAX_IDENTITY_LENGTH = 1024;

    private final String appId;

    private final String userId;

    private final String agentId;

    private final String runId;

    private Mem0Scope(Builder builder) {
        appId = optionalIdentity(builder.appId, "appId");
        userId = optionalIdentity(builder.userId, "userId");
        agentId = optionalIdentity(builder.agentId, "agentId");
        runId = optionalIdentity(builder.runId, "runId");
        if (appId == null && userId == null && agentId == null && runId == null) {
            throw new ValidationException("Mem0 scope requires at least one identity field.");
        }
    }

    /**
     * Creates a scope builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a user scope.
     *
     * @param userId user identifier
     * @return user scope
     */
    public static Mem0Scope forUser(String userId) {
        return builder().userId(userId).build();
    }

    /**
     * Creates an agent scope.
     *
     * @param agentId agent identifier
     * @return agent scope
     */
    public static Mem0Scope forAgent(String agentId) {
        return builder().agentId(agentId).build();
    }

    /**
     * Creates an app scope.
     *
     * @param appId app identifier
     * @return app scope
     */
    public static Mem0Scope forApp(String appId) {
        return builder().appId(appId).build();
    }

    /**
     * Creates a run scope.
     *
     * @param runId run identifier
     * @return run scope
     */
    public static Mem0Scope forRun(String runId) {
        return builder().runId(runId).build();
    }

    /** Returns the optional app identifier. */
    public String appId() {
        return appId;
    }

    /** Returns the optional user identifier. */
    public String userId() {
        return userId;
    }

    /** Returns the optional agent identifier. */
    public String agentId() {
        return agentId;
    }

    /** Returns the optional run identifier. */
    public String runId() {
        return runId;
    }

    StateValue.ObjectValue filters() {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        putState(values, "app_id", appId);
        putState(values, "user_id", userId);
        putState(values, "agent_id", agentId);
        putState(values, "run_id", runId);
        return StateValue.object(values);
    }

    List<Mem0Scope> partitions() {
        if (userId == null || agentId == null) {
            return List.of(this);
        }
        return List.of(
                builder().appId(appId).userId(userId).runId(runId).build(),
                builder().appId(appId).agentId(agentId).runId(runId).build());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Mem0Scope scope)) {
            return false;
        }
        return Objects.equals(appId, scope.appId)
                && Objects.equals(userId, scope.userId)
                && Objects.equals(agentId, scope.agentId)
                && Objects.equals(runId, scope.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appId, userId, agentId, runId);
    }

    @Override
    public String toString() {
        return "Mem0Scope{appId="
                + present(appId)
                + ", userId="
                + present(userId)
                + ", agentId="
                + present(agentId)
                + ", runId="
                + present(runId)
                + '}';
    }

    private static String optionalIdentity(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank when present.");
        }
        if (value.length() > MAX_IDENTITY_LENGTH) {
            throw new ValidationException(name + " exceeds the supported length.");
        }
        if ("*".equals(value)) {
            throw new ValidationException(name + " must not use the Mem0 wildcard identity.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new ValidationException(name + " must not contain control characters.");
            }
        }
        return value;
    }

    private static void putState(java.util.Map<String, StateValue> values, String name, String value) {
        if (value != null) {
            values.put(name, StateValue.string(value));
        }
    }

    private static String present(String value) {
        return value == null ? "<absent>" : "[REDACTED]";
    }

    /** Builds immutable {@link Mem0Scope} values. */
    public static final class Builder {
        private String appId;

        private String userId;

        private String agentId;

        private String runId;

        private Builder() {}

        /** Sets the optional app identity. */
        public Builder appId(String value) {
            appId = value;
            return this;
        }

        /** Sets the optional user identity. */
        public Builder userId(String value) {
            userId = value;
            return this;
        }

        /** Sets the optional agent identity. */
        public Builder agentId(String value) {
            agentId = value;
            return this;
        }

        /** Sets the optional run identity. */
        public Builder runId(String value) {
            runId = value;
            return this;
        }

        /**
         * Creates the immutable scope.
         *
         * @return scope
         */
        public Mem0Scope build() {
            return new Mem0Scope(this);
        }
    }
}
