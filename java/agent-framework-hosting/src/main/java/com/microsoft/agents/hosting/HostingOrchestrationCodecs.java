// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.orchestrations.OrchestrationContinuation;
import com.microsoft.agents.orchestrations.OrchestrationResumeInput;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/** Provides standard process-local orchestration hosting codecs. */
public final class HostingOrchestrationCodecs {
    private HostingOrchestrationCodecs() {}

    /**
     * Creates a codec with a caller-provided terminal output encoder and standard resume shapes.
     *
     * @param outputEncoder terminal output encoder
     * @param <O> output type
     * @return orchestration codec
     */
    public static <O> HostingOrchestrationCodec<O> of(Function<? super O, ? extends StateValue> outputEncoder) {
        java.util.Objects.requireNonNull(outputEncoder, "outputEncoder");
        return new HostingOrchestrationCodec<>() {
            @Override
            public StateValue encodeOutput(O output) {
                return java.util.Objects.requireNonNull(outputEncoder.apply(output), "encoded output");
            }

            @Override
            public OrchestrationResumeInput decodeResumeInput(
                    OrchestrationContinuation continuation, HostingResumeRequest request) {
                return standardResume(continuation, request);
            }
        };
    }

    /**
     * Returns an identity codec for JSON-shaped terminal output.
     *
     * @return state-value codec
     */
    public static HostingOrchestrationCodec<StateValue> stateValue() {
        return of(Function.identity());
    }

    /**
     * Returns a string terminal output codec.
     *
     * @return text codec
     */
    public static HostingOrchestrationCodec<String> text() {
        return of(StateValue::string);
    }

    private static OrchestrationResumeInput standardResume(
            OrchestrationContinuation continuation, HostingResumeRequest request) {
        return switch (continuation.kind()) {
            case APPROVAL -> approval(continuation, request);
            case HUMAN_INPUT -> OrchestrationResumeInput.human(inputString(request));
            case PLAN_REVIEW -> planReview(request);
        };
    }

    private static OrchestrationResumeInput approval(
            OrchestrationContinuation continuation, HostingResumeRequest request) {
        if (request.type() != HostingContinuationType.APPROVAL || continuation.agentContinuation() == null) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Approval continuation requires approval decisions.");
        }
        List<ToolApprovalRequest> pending = continuation.agentContinuation().approvalRequests();
        LinkedHashMap<String, HostingApprovalDecision> supplied = new LinkedHashMap<>();
        request.decisions().forEach(decision -> {
            if (supplied.putIfAbsent(decision.approvalId(), decision) != null) {
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE, "Approval decision identifiers must be unique.");
            }
        });
        if (!pending.stream()
                .map(value -> value.approvalId().value())
                .collect(java.util.stream.Collectors.toSet())
                .equals(supplied.keySet())) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Approval decisions must match every pending request.");
        }
        ArrayList<ToolApprovalDecision> decisions = new ArrayList<>(pending.size());
        for (ToolApprovalRequest approval : pending) {
            HostingApprovalDecision decision =
                    supplied.get(approval.approvalId().value());
            decisions.add(
                    decision.approved()
                            ? ToolApprovalDecision.approve(approval)
                            : ToolApprovalDecision.reject(approval, decision.reason()));
        }
        return OrchestrationResumeInput.approval(decisions);
    }

    private static OrchestrationResumeInput planReview(HostingResumeRequest request) {
        if (request.type() != HostingContinuationType.INPUT
                || !(request.input() instanceof StateValue.ObjectValue object)) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Plan review requires an object input.");
        }
        StateValue approved = object.values().get("approved");
        if (!(approved instanceof StateValue.BooleanValue bool)) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Plan review requires Boolean approved.");
        }
        if (bool.value()) {
            return OrchestrationResumeInput.approvePlan();
        }
        StateValue feedback = object.values().get("feedback");
        return OrchestrationResumeInput.rejectPlan(
                feedback instanceof StateValue.StringValue string ? string.value() : null);
    }

    private static String inputString(HostingResumeRequest request) {
        if (request.type() != HostingContinuationType.INPUT) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Human input continuation requires input.");
        }
        if (request.input() instanceof StateValue.StringValue string
                && !string.value().isBlank()) {
            return string.value();
        }
        if (request.input() instanceof StateValue.ObjectValue object) {
            StateValue value = object.values().get("text");
            if (value instanceof StateValue.StringValue string
                    && !string.value().isBlank()) {
                return string.value();
            }
        }
        throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Human input continuation requires non-blank text.");
    }
}
