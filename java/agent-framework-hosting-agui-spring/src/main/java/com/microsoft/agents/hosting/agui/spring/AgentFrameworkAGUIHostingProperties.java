// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui.spring;

import com.microsoft.agents.hosting.agui.AGUIHostingRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds opt-in Spring WebFlux AG-UI route and in-memory thread-store settings. */
@ConfigurationProperties(AgentFrameworkAGUIHostingProperties.PREFIX)
public final class AgentFrameworkAGUIHostingProperties {
    /** Configuration prefix. */
    public static final String PREFIX = "agent-framework.hosting.agui";

    private boolean enabled;

    private String basePath = AGUIHostingRegistry.DEFAULT_PATH;

    private int maxThreads = 1_000;

    private Duration threadTimeToLive = Duration.ofMinutes(30);

    private boolean includeRunInput;

    /**
     * Reports whether Spring AG-UI routes are enabled.
     *
     * @return enabled state
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets route enablement.
     *
     * @param value enabled state
     */
    public void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Returns the route predicate base path.
     *
     * @return base path
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * Sets the normalized route predicate base path.
     *
     * @param value base path
     */
    public void setBasePath(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            throw new IllegalArgumentException("basePath must be an absolute path.");
        }
        basePath = value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Returns the maximum in-memory principal-scoped threads.
     *
     * @return capacity
     */
    public int getMaxThreads() {
        return maxThreads;
    }

    /**
     * Sets the positive in-memory thread capacity.
     *
     * @param value capacity
     */
    public void setMaxThreads(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxThreads must be positive.");
        }
        maxThreads = value;
    }

    /**
     * Returns the in-memory thread TTL.
     *
     * @return TTL
     */
    public Duration getThreadTimeToLive() {
        return threadTimeToLive;
    }

    /**
     * Sets the positive in-memory thread TTL.
     *
     * @param value TTL
     */
    public void setThreadTimeToLive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("threadTimeToLive must be positive.");
        }
        threadTimeToLive = value;
    }

    /**
     * Reports whether validated request input is echoed on {@code RUN_STARTED}.
     *
     * @return echo state
     */
    public boolean isIncludeRunInput() {
        return includeRunInput;
    }

    /**
     * Sets validated request-input echoing.
     *
     * @param value echo state
     */
    public void setIncludeRunInput(boolean value) {
        includeRunInput = value;
    }
}
