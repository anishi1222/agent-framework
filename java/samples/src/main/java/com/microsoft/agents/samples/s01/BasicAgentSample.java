// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.s01;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.samples.common.PrefixChatClient;

/** Runs a minimal provider-neutral chat agent. */
public final class BasicAgentSample {
    private BasicAgentSample() {}

    /**
     * Runs the sample.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        try (ChatAgent agent = new ChatAgent(new PrefixChatClient("echo:"))) {
            String text = agent.run("hello").text();
            require("echo:hello".equals(text), "Unexpected response: " + text);
            System.out.println(text);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
