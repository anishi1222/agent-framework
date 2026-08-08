// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.net.URI;
import java.util.Objects;

/** Defines stored push-notification configuration without performing delivery. */
public final class PushNotificationConfig {
    private final String id;
    private final String taskId;
    private final URI url;
    private final String token;
    private final AuthenticationInfo authentication;
    private final String tenant;

    /**
     * Creates a push-notification configuration.
     *
     * @param id configuration identifier
     * @param taskId optional task identifier for inline send configuration
     * @param url HTTP or HTTPS callback URI
     * @param token optional opaque verification token
     * @param authentication optional authentication information
     * @param tenant optional tenant routing value
     */
    public PushNotificationConfig(
            String id, String taskId, URI url, String token, AuthenticationInfo authentication, String tenant) {
        this.id = A2AValidation.nonBlank(id, "id");
        this.taskId = A2AValidation.optionalNonBlank(taskId, "taskId");
        this.url = A2AValidation.absoluteUri(url, "url");
        if (!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme())) {
            throw new com.microsoft.agents.core.ValidationException("Push notification URL must use HTTP or HTTPS.");
        }
        this.token = A2AValidation.optionalNonBlank(token, "token");
        this.authentication = authentication;
        this.tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
    }

    /** Returns the configuration identifier. */
    public String id() {
        return id;
    }

    /** Returns the associated task identifier, or {@code null}. */
    public String taskId() {
        return taskId;
    }

    /** Returns the callback URI. */
    public URI url() {
        return url;
    }

    /** Returns the verification token, or {@code null}. */
    public String token() {
        return token;
    }

    /** Returns authentication information, or {@code null}. */
    public AuthenticationInfo authentication() {
        return authentication;
    }

    /** Returns the optional tenant. */
    public String tenant() {
        return tenant;
    }

    /**
     * Returns a copy associated with the supplied task.
     *
     * @param value task identifier
     * @return associated configuration
     */
    public PushNotificationConfig forTask(String value) {
        return new PushNotificationConfig(id, value, url, token, authentication, tenant);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof PushNotificationConfig config
                        && id.equals(config.id)
                        && Objects.equals(taskId, config.taskId)
                        && url.equals(config.url)
                        && Objects.equals(token, config.token)
                        && Objects.equals(authentication, config.authentication)
                        && Objects.equals(tenant, config.tenant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, taskId, url, token, authentication, tenant);
    }

    @Override
    public String toString() {
        return "PushNotificationConfig[id=" + id + ", taskId=" + taskId + ", url=" + url
                + ", token=<redacted>, authentication=<redacted>, tenant=" + tenant + "]";
    }
}
