// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2AContentConverter;
import com.microsoft.agents.protocols.a2a.A2AConversionException;
import com.microsoft.agents.protocols.a2a.A2AJsonCodec;
import com.microsoft.agents.protocols.a2a.A2ALimits;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.DataPart;
import com.microsoft.agents.protocols.a2a.FilePart;
import com.microsoft.agents.protocols.a2a.Part;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TextPart;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowRunOptions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Hosts a typed framework workflow as one A2A task.
 *
 * <p>String, framework Message, StateValue, and byte-array inputs/outputs are mapped explicitly.
 * Checkpoint persistence remains a workflow concern and is not advertised as cross-language A2A
 * state. The current workflow runtime has no external-input suspension event, so this adapter never
 * fabricates an input-required boundary.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public final class A2AWorkflowExecutor<I, O> implements A2AExecutor {
    private final Workflow<I, O> workflow;

    private final List<String> outputModes;

    private final A2AJsonCodec codec;

    /**
     * Creates a workflow executor.
     *
     * @param workflow workflow
     * @param outputModes advertised output modes
     * @param limits conversion limits
     */
    public A2AWorkflowExecutor(Workflow<I, O> workflow, List<String> outputModes, A2ALimits limits) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.outputModes = List.copyOf(outputModes);
        codec = new A2AJsonCodec(Objects.requireNonNull(limits, "limits"));
    }

    @Override
    public CompletionStage<Void> executeAsync(
            A2AExecutionContext context, A2AEventSink sink, RunCancellation cancellation) {
        I input = convertInput(context);
        return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                .thenCompose(ignored -> workflow.runAsync(input, WorkflowRunOptions.defaults(), cancellation))
                .thenCompose(result -> {
                    List<String> negotiatedOutputModes = context.request()
                                    .configuration()
                                    .acceptedOutputModes()
                                    .isEmpty()
                            ? outputModes
                            : context.request().configuration().acceptedOutputModes();
                    List<Part> parts = convertOutput(result.output(), negotiatedOutputModes);
                    CompletionStage<?> artifactStage = parts.isEmpty()
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : sink.addArtifactAsync(
                                    Artifact.builder(sink.current().id() + "-result")
                                            .name("workflow-result")
                                            .parts(parts)
                                            .build(),
                                    false,
                                    true,
                                    Map.of("workflowRunId", StateValue.string(result.runId())));
                    return artifactStage
                            .thenCompose(ignored -> sink.updateStatusAsync(TaskState.TASK_STATE_COMPLETED, null))
                            .thenApply(ignored -> null);
                });
    }

    @SuppressWarnings("unchecked")
    private I convertInput(A2AExecutionContext context) {
        Class<I> inputType = workflow.inputType();
        if (inputType == String.class) {
            if (context.request().message().parts().stream().anyMatch(part -> !(part instanceof TextPart))) {
                throw new A2AConversionException("Workflow String input accepts only text/plain parts.");
            }
            String text = context.request().message().parts().stream()
                    .map(TextPart.class::cast)
                    .map(TextPart::text)
                    .collect(Collectors.joining());
            return (I) text;
        }
        if (inputType == Message.class) {
            return (I) A2AContentConverter.toFrameworkMessage(context.request().message(), List.of("*/*"), codec);
        }
        if (inputType == StateValue.class) {
            if (context.request().message().parts().size() != 1
                    || !(context.request().message().parts().getFirst() instanceof DataPart data)) {
                throw new A2AConversionException(
                        "Workflow StateValue input requires exactly one application/json data part.");
            }
            return (I) data.data();
        }
        if (inputType == byte[].class) {
            if (context.request().message().parts().size() != 1
                    || !(context.request().message().parts().getFirst() instanceof FilePart file)
                    || !file.inline()) {
                throw new A2AConversionException("Workflow byte[] input requires exactly one inline file part.");
            }
            return (I) file.bytes();
        }
        throw new A2AConversionException("Workflow input type " + inputType.getName()
                + " has no lossless A2A mapping; use String, Message, StateValue, or byte[].");
    }

    private List<Part> convertOutput(O output, List<String> negotiatedOutputModes) {
        if (output instanceof String text) {
            requireMode("text/plain", negotiatedOutputModes);
            return List.of(new TextPart(text));
        }
        if (output instanceof StateValue data) {
            requireMode("application/json", negotiatedOutputModes);
            return List.of(new DataPart(data));
        }
        if (output instanceof Message message) {
            return A2AContentConverter.toA2AParts(List.of(message), negotiatedOutputModes, codec);
        }
        if (output instanceof byte[] bytes) {
            requireMode("application/octet-stream", negotiatedOutputModes);
            return List.of(FilePart.bytes(bytes, "workflow-output.bin", "application/octet-stream", Map.of()));
        }
        if (output instanceof DataContent data) {
            requireMode(data.mediaType(), negotiatedOutputModes);
            return List.of(FilePart.bytes(data.data(), "workflow-output.bin", data.mediaType(), data.metadata()));
        }
        throw new A2AConversionException(
                "Workflow output type " + output.getClass().getName()
                        + " has no lossless A2A mapping; use String, Message, StateValue, byte[], or DataContent.");
    }

    private static void requireMode(String mediaType, List<String> modes) {
        boolean supported = modes.stream()
                .anyMatch(mode -> mode.equalsIgnoreCase(mediaType)
                        || "*/*".equals(mode)
                        || (mode.endsWith("/*") && mediaType.regionMatches(true, 0, mode, 0, mode.indexOf('/') + 1)));
        if (!supported) {
            throw new A2AConversionException("Workflow output media type '" + mediaType + "' is not accepted.");
        }
    }
}
