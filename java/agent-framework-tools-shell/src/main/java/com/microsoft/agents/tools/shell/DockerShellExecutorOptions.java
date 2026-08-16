// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Configures Docker-compatible container shell execution. */
public final class DockerShellExecutorOptions {
    private final String image;
    private final String containerName;
    private final ShellMode mode;
    private final String hostWorkingDirectory;
    private final String containerWorkingDirectory;
    private final boolean mountReadOnly;
    private final String network;
    private final long memoryBytes;
    private final int pidsLimit;
    private final ContainerUser user;
    private final boolean readOnlyRoot;
    private final List<String> extraRunArguments;
    private final Map<String, String> environment;
    private final ShellPolicy policy;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final String dockerBinary;

    private DockerShellExecutorOptions(Builder builder) {
        image = requireNonBlank(builder.image, "image");
        containerName = optionalNonBlank(builder.containerName, "containerName");
        if (containerName != null && !containerName.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("containerName contains unsupported characters.");
        }
        mode = Objects.requireNonNull(builder.mode, "mode");
        hostWorkingDirectory = optionalNonBlank(builder.hostWorkingDirectory, "hostWorkingDirectory");
        containerWorkingDirectory = requireNonBlank(builder.containerWorkingDirectory, "containerWorkingDirectory");
        mountReadOnly = builder.mountReadOnly;
        network = requireNonBlank(builder.network, "network");
        memoryBytes = builder.memoryBytes;
        if (memoryBytes <= 0) {
            throw new IllegalArgumentException("memoryBytes must be positive.");
        }
        pidsLimit = builder.pidsLimit;
        if (pidsLimit <= 0) {
            throw new IllegalArgumentException("pidsLimit must be positive.");
        }
        user = Objects.requireNonNull(builder.user, "user");
        readOnlyRoot = builder.readOnlyRoot;
        extraRunArguments = List.copyOf(builder.extraRunArguments);
        if (extraRunArguments.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("extraRunArguments must contain non-blank values.");
        }
        environment = Map.copyOf(builder.environment);
        policy = Objects.requireNonNull(builder.policy, "policy");
        timeout = builder.timeout;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive when present.");
        }
        maxOutputBytes = builder.maxOutputBytes;
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive.");
        }
        dockerBinary = requireNonBlank(builder.dockerBinary, "dockerBinary");
    }

    /**
     * Returns a new options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns default restrictive options.
     *
     * @return Docker shell defaults
     */
    public static DockerShellExecutorOptions defaults() {
        return builder().build();
    }

    /** Returns the OCI image. */
    public String image() {
        return image;
    }

    /** Returns an explicit container name, or {@code null}. */
    public String containerName() {
        return containerName;
    }

    /** Returns the execution mode. */
    public ShellMode mode() {
        return mode;
    }

    /** Returns the optional host workspace mount. */
    public String hostWorkingDirectory() {
        return hostWorkingDirectory;
    }

    /** Returns the container working directory. */
    public String containerWorkingDirectory() {
        return containerWorkingDirectory;
    }

    /** Reports whether the host workspace mount is read-only. */
    public boolean mountReadOnly() {
        return mountReadOnly;
    }

    /** Returns the container network mode. */
    public String network() {
        return network;
    }

    /** Returns the memory limit in bytes. */
    public long memoryBytes() {
        return memoryBytes;
    }

    /** Returns the process-count limit. */
    public int pidsLimit() {
        return pidsLimit;
    }

    /** Returns the container identity. */
    public ContainerUser user() {
        return user;
    }

    /** Reports whether the container root filesystem is read-only. */
    public boolean readOnlyRoot() {
        return readOnlyRoot;
    }

    /** Returns caller-supplied runtime arguments. */
    public List<String> extraRunArguments() {
        return extraRunArguments;
    }

    /** Returns container environment variables. */
    public Map<String, String> environment() {
        return environment;
    }

    /** Returns the command policy. */
    public ShellPolicy policy() {
        return policy;
    }

    /** Returns the default command timeout, or {@code null}. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the per-stream output bound. */
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /** Returns the Docker-compatible runtime binary. */
    public String dockerBinary() {
        return dockerBinary;
    }

    /** Builds immutable Docker shell options. */
    public static final class Builder {
        private String image = DockerShellExecutor.DEFAULT_IMAGE;
        private String containerName;
        private ShellMode mode = ShellMode.PERSISTENT;
        private String hostWorkingDirectory;
        private String containerWorkingDirectory = DockerShellExecutor.DEFAULT_CONTAINER_WORKING_DIRECTORY;
        private boolean mountReadOnly = true;
        private String network = DockerNetworkMode.NONE;
        private long memoryBytes = DockerShellExecutor.DEFAULT_MEMORY_BYTES;
        private int pidsLimit = DockerShellExecutor.DEFAULT_PIDS_LIMIT;
        private ContainerUser user = ContainerUser.defaultUser();
        private boolean readOnlyRoot = true;
        private List<String> extraRunArguments = List.of();
        private final Map<String, String> environment = new LinkedHashMap<>();
        private ShellPolicy policy = new ShellPolicy();
        private Duration timeout = DockerShellExecutor.DEFAULT_TIMEOUT;
        private int maxOutputBytes = 64 * 1024;
        private String dockerBinary = "docker";

        private Builder() {}

        /** Sets the OCI image. */
        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /** Sets the container name. */
        public Builder containerName(String containerName) {
            this.containerName = containerName;
            return this;
        }

        /** Sets the execution mode. */
        public Builder mode(ShellMode mode) {
            this.mode = mode;
            return this;
        }

        /** Sets a host workspace to mount. */
        public Builder hostWorkingDirectory(String hostWorkingDirectory) {
            this.hostWorkingDirectory = hostWorkingDirectory;
            return this;
        }

        /** Sets the container working directory. */
        public Builder containerWorkingDirectory(String containerWorkingDirectory) {
            this.containerWorkingDirectory = containerWorkingDirectory;
            return this;
        }

        /** Controls whether the host workspace mount is read-only. */
        public Builder mountReadOnly(boolean mountReadOnly) {
            this.mountReadOnly = mountReadOnly;
            return this;
        }

        /** Sets the container network mode. */
        public Builder network(String network) {
            this.network = network;
            return this;
        }

        /** Sets the memory limit in bytes. */
        public Builder memoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
            return this;
        }

        /** Sets the process-count limit. */
        public Builder pidsLimit(int pidsLimit) {
            this.pidsLimit = pidsLimit;
            return this;
        }

        /** Sets the container identity. */
        public Builder user(ContainerUser user) {
            this.user = user;
            return this;
        }

        /** Controls whether the root filesystem is read-only. */
        public Builder readOnlyRoot(boolean readOnlyRoot) {
            this.readOnlyRoot = readOnlyRoot;
            return this;
        }

        /** Sets additional runtime arguments that may weaken isolation. */
        public Builder extraRunArguments(List<String> extraRunArguments) {
            this.extraRunArguments = Objects.requireNonNull(extraRunArguments, "extraRunArguments");
            return this;
        }

        /** Sets environment variables passed into the container. */
        public Builder environment(Map<String, String> environment) {
            this.environment.clear();
            Objects.requireNonNull(environment, "environment")
                    .forEach((key, value) -> this.environment.put(
                            requireNonBlank(key, "environment key"),
                            Objects.requireNonNull(value, "environment value")));
            return this;
        }

        /** Sets the command policy. */
        public Builder policy(ShellPolicy policy) {
            this.policy = policy;
            return this;
        }

        /** Sets the default command timeout, or {@code null} to disable it. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Sets the per-stream output bound. */
        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        /** Sets the Docker-compatible runtime binary. */
        public Builder dockerBinary(String dockerBinary) {
            this.dockerBinary = dockerBinary;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return Docker shell options
         */
        public DockerShellExecutorOptions build() {
            return new DockerShellExecutorOptions(this);
        }
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
