// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Defines immutable GitHub Copilot SDK lifecycle, security, and resource policy.
 */
public final class GitHubCopilotClientOptions {
    private final Path cliExecutable;

    private final GitHubCopilotCliLaunchMode cliLaunchMode;

    private final GitHubCopilotClientMode clientMode;

    private final Set<Path> allowedCliRoots;

    private final Path workingDirectory;

    private final Set<Path> workingDirectoryRoots;

    private final Path copilotHome;

    private final Map<String, String> environment;

    private final Set<String> allowedEnvironmentVariables;

    private final GitHubCopilotCredential credential;

    private final boolean useLoggedInUser;

    private final GitHubCopilotExternalServer externalServer;

    private final GitHubCopilotTelemetryConfig telemetry;

    private final Duration startupTimeout;

    private final Duration requestTimeout;

    private final Duration idleTimeout;

    private final Duration closeTimeout;

    private final GitHubCopilotLimits limits;

    private final Executor executor;

    private GitHubCopilotClientOptions(Builder builder) {
        externalServer = builder.externalServer;
        cliLaunchMode = Objects.requireNonNull(builder.cliLaunchMode, "cliLaunchMode");
        clientMode = Objects.requireNonNull(builder.clientMode, "clientMode");
        cliExecutable = builder.cliExecutable == null ? null : executable(builder.cliExecutable);
        allowedCliRoots = canonicalRoots(builder.allowedCliRoots, "allowedCliRoots");
        workingDirectory = directory(builder.workingDirectory, "workingDirectory");
        workingDirectoryRoots = canonicalRoots(builder.workingDirectoryRoots, "workingDirectoryRoots");
        copilotHome = builder.copilotHome == null ? null : directory(builder.copilotHome, "copilotHome");
        environment = Map.copyOf(builder.environment);
        allowedEnvironmentVariables = Set.copyOf(builder.allowedEnvironmentVariables);
        credential = builder.credential;
        useLoggedInUser = builder.useLoggedInUser;
        telemetry = builder.telemetry;
        startupTimeout = positive(builder.startupTimeout, "startupTimeout");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        idleTimeout = positiveWholeSeconds(builder.idleTimeout, "idleTimeout");
        closeTimeout = positive(builder.closeTimeout, "closeTimeout");
        limits = Objects.requireNonNull(builder.limits, "limits");
        executor = builder.executor;
        validatePaths();
        validateEnvironment();
        validateAuthentication();
    }

    /**
     * Creates an options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the optional canonical CLI executable.
     *
     * <p>When absent in SDK-managed mode, the official SDK resolves {@code copilot} from
     * {@code PATH}.
     *
     * @return canonical executable or {@code null}
     */
    public Path cliExecutable() {
        return cliExecutable;
    }

    /**
     * Returns the selected CLI launch mode.
     *
     * @return launch mode
     */
    public GitHubCopilotCliLaunchMode cliLaunchMode() {
        return cliLaunchMode;
    }

    /**
     * Returns the selected official SDK client mode.
     *
     * @return client mode
     */
    public GitHubCopilotClientMode clientMode() {
        return clientMode;
    }

    /**
     * Returns canonical executable roots.
     *
     * @return immutable root set
     */
    public Set<Path> allowedCliRoots() {
        return allowedCliRoots;
    }

    /**
     * Returns the canonical process working directory.
     *
     * @return working directory
     */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * Returns canonical working-directory roots.
     *
     * @return immutable root set
     */
    public Set<Path> workingDirectoryRoots() {
        return workingDirectoryRoots;
    }

    /**
     * Returns the optional canonical Copilot home.
     *
     * @return Copilot home or {@code null}
     */
    public Path copilotHome() {
        return copilotHome;
    }

    /**
     * Returns the sanitized child environment.
     *
     * @return immutable environment
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns explicitly allowed child-environment names.
     *
     * @return immutable allowlist
     */
    public Set<String> allowedEnvironmentVariables() {
        return allowedEnvironmentVariables;
    }

    /**
     * Returns whether stored CLI user credentials may be used.
     *
     * @return explicit opt-in value
     */
    public boolean useLoggedInUser() {
        return useLoggedInUser;
    }

    /**
     * Returns an optional loopback external-server configuration.
     *
     * @return external server or {@code null}
     */
    public GitHubCopilotExternalServer externalServer() {
        return externalServer;
    }

    /**
     * Returns optional official SDK telemetry configuration.
     *
     * @return telemetry configuration or {@code null}
     */
    public GitHubCopilotTelemetryConfig telemetry() {
        return telemetry;
    }

    /**
     * Returns the startup timeout.
     *
     * @return startup timeout
     */
    public Duration startupTimeout() {
        return startupTimeout;
    }

    /**
     * Returns the finite request timeout.
     *
     * @return request timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the session idle timeout.
     *
     * @return idle timeout
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /**
     * Returns the close timeout.
     *
     * @return close timeout
     */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /**
     * Returns facade mapping and optional-launcher process limits.
     *
     * @return configured limits
     */
    public GitHubCopilotLimits limits() {
        return limits;
    }

    Executor executor() {
        return executor;
    }

    GitHubCopilotCredential credential() {
        return credential;
    }

    @Override
    public String toString() {
        return "GitHubCopilotClientOptions{cliExecutable="
                + cliExecutable
                + ", cliLaunchMode="
                + cliLaunchMode
                + ", clientMode="
                + clientMode
                + ", allowedCliRoots="
                + allowedCliRoots
                + ", workingDirectory="
                + workingDirectory
                + ", workingDirectoryRoots="
                + workingDirectoryRoots
                + ", copilotHome="
                + copilotHome
                + ", environmentNames="
                + environment.keySet()
                + ", credential="
                + (credential == null ? "<absent>" : "[REDACTED]")
                + ", useLoggedInUser="
                + useLoggedInUser
                + ", externalServer="
                + externalServer
                + ", telemetry="
                + telemetry
                + ", startupTimeout="
                + startupTimeout
                + ", requestTimeout="
                + requestTimeout
                + ", idleTimeout="
                + idleTimeout
                + ", closeTimeout="
                + closeTimeout
                + ", limits="
                + limits
                + '}';
    }

    private void validatePaths() {
        if (cliExecutable != null
                && !allowedCliRoots.isEmpty()
                && allowedCliRoots.stream().noneMatch(cliExecutable::startsWith)) {
            throw new IllegalArgumentException("cliExecutable must be within allowedCliRoots.");
        }
        if (cliExecutable == null && !allowedCliRoots.isEmpty()) {
            throw new IllegalArgumentException("allowedCliRoots requires an explicit cliExecutable.");
        }
        if (workingDirectoryRoots.stream().noneMatch(workingDirectory::startsWith)) {
            throw new IllegalArgumentException("workingDirectory must be within workingDirectoryRoots.");
        }
    }

    private void validateEnvironment() {
        Set<String> denied = new LinkedHashSet<>(environment.keySet());
        denied.removeAll(allowedEnvironmentVariables);
        if (!denied.isEmpty()) {
            throw new IllegalArgumentException("environment contains names outside the allowlist: " + denied);
        }
        environment.forEach((name, value) -> {
            if (name.isBlank() || name.indexOf('=') >= 0 || value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("environment contains an invalid name or value.");
            }
        });
    }

    private void validateAuthentication() {
        if (credential != null && useLoggedInUser) {
            throw new IllegalArgumentException("credential and useLoggedInUser(true) are mutually exclusive.");
        }
        if (externalServer != null && credential != null) {
            throw new IllegalArgumentException(
                    "credential belongs on the external server and cannot be sent by this client.");
        }
        if (externalServer != null && useLoggedInUser) {
            throw new IllegalArgumentException(
                    "useLoggedInUser belongs on the external server and cannot be configured by this client.");
        }
        if (externalServer != null && cliExecutable != null) {
            throw new IllegalArgumentException("externalServer and cliExecutable are mutually exclusive.");
        }
        if (externalServer != null && cliLaunchMode == GitHubCopilotCliLaunchMode.HARDENED_EXTERNAL) {
            throw new IllegalArgumentException(
                    "externalServer is caller-owned and cannot use the hardened external launcher.");
        }
        if (externalServer != null && telemetry != null) {
            throw new IllegalArgumentException(
                    "Telemetry for a caller-owned external server must be configured on that server.");
        }
        if (cliLaunchMode == GitHubCopilotCliLaunchMode.HARDENED_EXTERNAL && cliExecutable == null) {
            throw new IllegalArgumentException("HARDENED_EXTERNAL requires an explicit cliExecutable.");
        }
        if (clientMode == GitHubCopilotClientMode.EMPTY && copilotHome == null && externalServer == null) {
            throw new IllegalArgumentException("EMPTY mode requires copilotHome or externalServer.");
        }
    }

    private static Path executable(Path value) {
        Path path = canonical(Objects.requireNonNull(value, "cliExecutable"), "cliExecutable");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException("cliExecutable must be a canonical executable regular file.");
        }
        return path;
    }

    private static Path directory(Path value, String name) {
        Path path = canonical(Objects.requireNonNull(value, name), name);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(name + " must be a canonical directory.");
        }
        return path;
    }

    private static Set<Path> canonicalRoots(Set<Path> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path value : values) {
            roots.add(directory(value, name + " element"));
        }
        return Set.copyOf(roots);
    }

    private static Path canonical(Path value, String name) {
        try {
            return value.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(name + " cannot be resolved.", exception);
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static Duration positiveWholeSeconds(Duration value, String name) {
        Duration result = positive(value, name);
        if (result.toSeconds() < 1 || result.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " must be between one second and Integer.MAX_VALUE seconds.");
        }
        return result;
    }

    /** Builds immutable {@link GitHubCopilotClientOptions} instances. */
    public static final class Builder {
        private Path cliExecutable;

        private GitHubCopilotCliLaunchMode cliLaunchMode = GitHubCopilotCliLaunchMode.SDK_MANAGED;

        private GitHubCopilotClientMode clientMode = GitHubCopilotClientMode.COPILOT_CLI;

        private Set<Path> allowedCliRoots = Set.of();

        private Path workingDirectory = Path.of(".");

        private Set<Path> workingDirectoryRoots = Set.of(Path.of("."));

        private Path copilotHome;

        private final Map<String, String> environment = new LinkedHashMap<>(Map.of("LANG", "C.UTF-8"));

        private Set<String> allowedEnvironmentVariables = Set.of("LANG");

        private GitHubCopilotCredential credential;

        private boolean useLoggedInUser;

        private GitHubCopilotExternalServer externalServer;

        private GitHubCopilotTelemetryConfig telemetry;

        private Duration startupTimeout = Duration.ofSeconds(30);

        private Duration requestTimeout = Duration.ofSeconds(60);

        private Duration idleTimeout = Duration.ofMinutes(10);

        private Duration closeTimeout = Duration.ofSeconds(15);

        private GitHubCopilotLimits limits = GitHubCopilotLimits.defaults();

        private Executor executor;

        private Builder() {}

        /**
         * Sets the exact CLI executable.
         *
         * @param cliExecutable executable path
         * @return this builder
         */
        public Builder cliExecutable(Path cliExecutable) {
            this.cliExecutable = Objects.requireNonNull(cliExecutable, "cliExecutable");
            return this;
        }

        /**
         * Selects SDK-managed or explicitly hardened external CLI launching.
         *
         * @param cliLaunchMode launch mode
         * @return this builder
         */
        public Builder cliLaunchMode(GitHubCopilotCliLaunchMode cliLaunchMode) {
            this.cliLaunchMode = Objects.requireNonNull(cliLaunchMode, "cliLaunchMode");
            return this;
        }

        /**
         * Selects the stable official SDK client mode.
         *
         * @param clientMode client mode
         * @return this builder
         */
        public Builder clientMode(GitHubCopilotClientMode clientMode) {
            this.clientMode = Objects.requireNonNull(clientMode, "clientMode");
            return this;
        }

        /**
         * Replaces the canonical executable-root allowlist.
         *
         * @param allowedCliRoots allowed roots
         * @return this builder
         */
        public Builder allowedCliRoots(Set<Path> allowedCliRoots) {
            this.allowedCliRoots = Set.copyOf(Objects.requireNonNull(allowedCliRoots, "allowedCliRoots"));
            return this;
        }

        /**
         * Sets the child process working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        /**
         * Replaces the working-directory-root allowlist.
         *
         * @param roots allowed roots
         * @return this builder
         */
        public Builder workingDirectoryRoots(Set<Path> roots) {
            this.workingDirectoryRoots = Set.copyOf(Objects.requireNonNull(roots, "roots"));
            return this;
        }

        /**
         * Sets an explicit per-session Copilot home.
         *
         * @param copilotHome existing directory
         * @return this builder
         */
        public Builder copilotHome(Path copilotHome) {
            this.copilotHome = Objects.requireNonNull(copilotHome, "copilotHome");
            return this;
        }

        /**
         * Replaces the cleared child environment.
         *
         * @param environment child environment
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) {
            this.environment.clear();
            this.environment.putAll(Objects.requireNonNull(environment, "environment"));
            return this;
        }

        /**
         * Replaces the allowed child-environment names.
         *
         * @param names allowed names
         * @return this builder
         */
        public Builder allowedEnvironmentVariables(Set<String> names) {
            this.allowedEnvironmentVariables = Set.copyOf(Objects.requireNonNull(names, "names"));
            return this;
        }

        /**
         * Sets an explicit supported user token.
         *
         * @param credential credential
         * @return this builder
         */
        public Builder credential(GitHubCopilotCredential credential) {
            this.credential = Objects.requireNonNull(credential, "credential");
            return this;
        }

        /**
         * Explicitly permits or denies use of the locally signed-in CLI user.
         *
         * @param useLoggedInUser opt-in value
         * @return this builder
         */
        public Builder useLoggedInUser(boolean useLoggedInUser) {
            this.useLoggedInUser = useLoggedInUser;
            return this;
        }

        /**
         * Uses an already-running loopback server instead of spawning a process.
         *
         * @param externalServer external server
         * @return this builder
         */
        public Builder externalServer(GitHubCopilotExternalServer externalServer) {
            this.externalServer = Objects.requireNonNull(externalServer, "externalServer");
            return this;
        }

        /**
         * Configures stable official SDK telemetry for an owned CLI process.
         *
         * @param telemetry telemetry configuration
         * @return this builder
         */
        public Builder telemetry(GitHubCopilotTelemetryConfig telemetry) {
            this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
            return this;
        }

        /** Sets the startup timeout. */
        public Builder startupTimeout(Duration startupTimeout) {
            this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
            return this;
        }

        /** Sets the finite request timeout. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /** Sets the session idle timeout. */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
            return this;
        }

        /** Sets the close timeout. */
        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
            return this;
        }

        /** Sets facade mapping and optional-launcher process limits. */
        public Builder limits(GitHubCopilotLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets a caller-owned executor.
         *
         * @param executor caller-owned executor
         * @return this builder
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return client options
         */
        public GitHubCopilotClientOptions build() {
            return new GitHubCopilotClientOptions(this);
        }
    }
}
