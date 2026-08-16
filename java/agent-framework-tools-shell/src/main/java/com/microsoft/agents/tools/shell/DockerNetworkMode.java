// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

/** Common Docker network-mode values for shell isolation. */
public final class DockerNetworkMode {
    /** Disables container networking. */
    public static final String NONE = "none";

    /** Uses the default bridge network. */
    public static final String BRIDGE = "bridge";

    /** Shares the host network namespace and weakens isolation. */
    public static final String HOST = "host";

    private DockerNetworkMode() {}
}
