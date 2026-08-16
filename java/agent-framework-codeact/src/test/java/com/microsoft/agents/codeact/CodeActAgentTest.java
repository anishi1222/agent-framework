// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.shell.ShellPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActAgentTest {
    @TempDir
    Path workspace;

    @Test
    void run_shouldExecuteStructuredPlannerValueThroughBoundedExecutor() {
        // Arrange
        CodeActProgram program = CodeActProgram.ofCommands("echo planned");
        Agent<CodeActProgram> planner = new FixedPlanner(program);
        CodeActOptions options = CodeActOptions.builder(workspace)
                .shellPolicy(new ShellPolicy(List.of(), List.of("^echo\\b"), null))
                .approvalHandler((request, cancellation) ->
                        CompletableFuture.completedFuture(ToolApprovalDecision.approve(request)))
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActAgent agent = new CodeActAgent(planner, executor);
            AgentResponse<CodeActResult> response =
                    agent.run(List.of(Message.text(com.microsoft.agents.core.Role.USER, "plan")));

            // Assert
            assertThat(response.value().status()).isEqualTo(CodeActStatus.COMPLETED);
            assertThat(response.text()).isEqualTo(response.value().transcript());
            assertThat(response.agentId()).isEqualTo("planner:codeact");
        }
    }

    private static final class FixedPlanner implements Agent<CodeActProgram> {
        private final CodeActProgram program;

        private FixedPlanner(CodeActProgram program) {
            this.program = program;
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("planner", "Planner", "Returns a fixed CodeAct program.");
        }

        @Override
        public RunHandle<AgentResponse<CodeActProgram>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<CodeActProgram>> source = new RunHandleSource<>(cancellation);
            source.tryComplete(
                    AgentResponse.<CodeActProgram>builder().value(program).build());
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException("Not used by this test.");
        }
    }
}
