// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.s03;

import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.util.Locale;

/** Builds and runs a strongly typed two-step workflow. */
public final class WorkflowSample {
    private WorkflowSample() {}

    /**
     * Runs the sample.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("sample-workflow", String.class, String.class);
        WorkflowNode<String, String> normalize = builder.addNode(
                "normalize", FunctionExecutor.sync(String.class, String.class, (value, context) -> value.strip()));
        WorkflowNode<String, String> publish = builder.addNode(
                "publish",
                FunctionExecutor.sync(
                        String.class, String.class, (value, context) -> value.toUpperCase(Locale.ROOT) + "!"));

        try (Workflow<String, String> workflow = builder.entry(normalize)
                .output(publish)
                .connect(normalize, publish)
                .build()) {
            String output = workflow.run("  ready  ").output();
            require("READY!".equals(output), "Unexpected workflow output: " + output);
            System.out.println(output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
