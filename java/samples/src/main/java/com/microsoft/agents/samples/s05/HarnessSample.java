// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.s05;

import com.microsoft.agents.harness.HarnessAgent;
import com.microsoft.agents.harness.HarnessAgentOptions;
import com.microsoft.agents.harness.files.InMemoryAgentFileStore;
import com.microsoft.agents.samples.common.PrefixChatClient;

/** Runs the autonomous Harness facade with deterministic in-memory providers. */
public final class HarnessSample {
    private HarnessSample() {}

    /**
     * Runs the sample.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        HarnessAgentOptions options = HarnessAgentOptions.builder()
                .fileMemoryStore(new InMemoryAgentFileStore())
                .build();
        try (HarnessAgent agent = new HarnessAgent(new PrefixChatClient("harness:"), options)) {
            String output = agent.run("inspect").text();
            require("harness:inspect".equals(output), "Unexpected Harness output: " + output);
            System.out.println(output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
