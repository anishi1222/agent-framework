// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.s04;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.orchestrations.OrchestrationParticipant;
import com.microsoft.agents.orchestrations.SequentialOrchestration;
import com.microsoft.agents.samples.common.PrefixChatClient;
import java.util.List;

/** Runs two agents through a sequential orchestration. */
public final class OrchestrationSample {
    private OrchestrationSample() {}

    /**
     * Runs the sample.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        ChatAgent researcher = new ChatAgent(new PrefixChatClient("research:"));
        ChatAgent writer = new ChatAgent(new PrefixChatClient("write:"));
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(researcher), OrchestrationParticipant.of(writer)))
                .build();
        try {
            String output = orchestration.run("Java agents").output().text();
            require("write:research:Java agents".equals(output), "Unexpected orchestration output: " + output);
            System.out.println(output);
        } finally {
            orchestration.close();
            researcher.close();
            writer.close();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
