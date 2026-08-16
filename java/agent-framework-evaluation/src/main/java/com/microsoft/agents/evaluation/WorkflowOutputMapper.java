// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Converts a completed workflow result to provider-neutral response messages.
 *
 * @param <O> workflow output type
 */
@FunctionalInterface
public interface WorkflowOutputMapper<O> {
    /**
     * Converts a completed workflow result to ordered response messages.
     *
     * @param result completed workflow result
     * @return ordered response messages
     */
    List<Message> map(WorkflowRunResult<O> result);

    /**
     * Creates a mapper for workflow outputs converted to assistant text.
     *
     * @param outputText function converting the workflow output to text
     * @param <O> workflow output type
     * @return workflow output mapper
     */
    static <O> WorkflowOutputMapper<O> text(Function<? super O, String> outputText) {
        Function<? super O, String> checkedFunction = Objects.requireNonNull(outputText, "outputText");
        return result -> List.of(Message.text(
                Role.ASSISTANT,
                Objects.requireNonNull(
                        checkedFunction.apply(
                                Objects.requireNonNull(result, "result").output()),
                        "mapped output text")));
    }

    /**
     * Creates a mapper for workflow outputs converted directly to messages.
     *
     * @param outputMessages function converting the workflow output to messages
     * @param <O> workflow output type
     * @return workflow output mapper
     */
    static <O> WorkflowOutputMapper<O> messages(Function<? super O, ? extends List<Message>> outputMessages) {
        Function<? super O, ? extends List<Message>> checkedFunction =
                Objects.requireNonNull(outputMessages, "outputMessages");
        return result -> EvaluationValidation.copyList(
                checkedFunction.apply(Objects.requireNonNull(result, "result").output()), "mapped output messages");
    }

    /**
     * Creates a mapper for workflow outputs converted to agent responses.
     *
     * @param outputResponse function converting the workflow output to an agent response
     * @param <O> workflow output type
     * @return workflow output mapper
     */
    static <O> WorkflowOutputMapper<O> agentResponse(Function<? super O, ? extends AgentResponse<?>> outputResponse) {
        Function<? super O, ? extends AgentResponse<?>> checkedFunction =
                Objects.requireNonNull(outputResponse, "outputResponse");
        return result -> Objects.requireNonNull(
                        checkedFunction.apply(
                                Objects.requireNonNull(result, "result").output()),
                        "mapped agent response")
                .messages();
    }
}
