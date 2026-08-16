// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.shell.ShellPolicy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActOptionsTest {
    @TempDir
    Path workspace;

    @Test
    void build_shouldRequireExplicitApprovalAndPolicy() {
        // Arrange and act and assert
        assertThatThrownBy(() -> CodeActOptions.builder(workspace).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("shellPolicy");

        assertThatThrownBy(() -> CodeActOptions.builder(workspace)
                        .shellPolicy(new ShellPolicy())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvalHandler");
    }

    @Test
    void build_shouldCreateImmutableCanonicalOptions() throws Exception {
        // Arrange
        CodeActEventListener listener = event -> {};

        // Act
        CodeActOptions options = CodeActOptions.builder(workspace)
                .shellPolicy(new ShellPolicy())
                .approvalHandler((request, cancellation) ->
                        CompletableFuture.completedFuture(ToolApprovalDecision.approve(request)))
                .eventListener(listener)
                .build();

        // Assert
        assertThat(options.workspaceRoot()).isEqualTo(workspace.toRealPath());
        assertThat(options.eventListeners()).containsExactly(listener);
        assertThatThrownBy(() -> options.eventListeners().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void program_shouldDefensivelyCopyAndRejectDuplicateStepIdentifiers() {
        // Arrange
        CodeActStep first = new CodeActStep("same", "echo one");
        CodeActStep second = new CodeActStep("same", "echo two");

        // Act and assert
        assertThatThrownBy(() -> new CodeActProgram(java.util.List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        CodeActProgram program = CodeActProgram.ofCommands("echo one", "echo two");
        assertThat(program.steps()).extracting(CodeActStep::id).containsExactly("step-1", "step-2");
    }
}
