// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.ChatClient;

/** Factory methods for autonomous harness agents. */
public final class HarnessAgents {
    private HarnessAgents() {}

    /**
     * Creates a harness with default options.
     *
     * @param chatClient caller-owned chat client
     * @return harness agent
     */
    public static HarnessAgent create(ChatClient chatClient) {
        return new HarnessAgent(chatClient);
    }

    /**
     * Creates a configured harness.
     *
     * @param chatClient caller-owned chat client
     * @param options harness options
     * @return harness agent
     */
    public static HarnessAgent create(ChatClient chatClient, HarnessAgentOptions options) {
        return new HarnessAgent(chatClient, options);
    }
}
