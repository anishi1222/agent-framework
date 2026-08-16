// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.StructuredOutputs;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Uses a separate caller-owned chat client to judge whether a loop should continue.
 *
 * <p>The judge receives the original request and latest response on every evaluation. Configure
 * only a judge endpoint trusted to receive that potentially sensitive and untrusted content.
 */
public final class AIJudgeLoopEvaluator implements LoopEvaluator {
    /** Marker used by judges that cannot honor structured output when work is complete. */
    public static final String DONE_VERDICT_MARKER = "VERDICT: DONE";

    /** Marker used by judges that cannot honor structured output when more work is required. */
    public static final String MORE_VERDICT_MARKER = "VERDICT: MORE";

    /** Placeholder replaced by rendered additional criteria. */
    public static final String CRITERIA_PLACEHOLDER = "{criteria}";

    /** Placeholder replaced by the judge's gap analysis. */
    public static final String GAP_ANALYSIS_PLACEHOLDER = "{gap_analysis}";

    /** Default system instructions sent to the judge. */
    public static final String DEFAULT_INSTRUCTIONS =
            "You are an evaluator. You are given a user's original request and an agent's latest "
                    + "response. Decide whether the agent has fully addressed the original request. "
                    + "Set 'answered' to true if the request has been fully addressed, or false if "
                    + "more work is still required. When 'answered' is false, use 'gapAnalysis' to "
                    + "explain what is still missing or what work remains. If you cannot return "
                    + "structured output, reply with "
                    + DONE_VERDICT_MARKER
                    + " when the request has been fully addressed, or "
                    + MORE_VERDICT_MARKER
                    + " when more work is still required."
                    + CRITERIA_PLACEHOLDER;

    /** Default feedback produced when the judge requests another iteration. */
    public static final String DEFAULT_FEEDBACK_MESSAGE_TEMPLATE =
            "Your previous response did not fully address the original request. The following is "
                    + "still missing or incomplete: "
                    + GAP_ANALYSIS_PLACEHOLDER
                    + " Please continue and fully address the original request.";

    private static final String UNKNOWN_GAP_ANALYSIS = "<unknown>";

    private static final StructuredOutputOptions VERDICT_OUTPUT = StructuredOutputOptions.jsonSchema(
            "harness_judge_verdict",
            Map.of(
                    "type",
                    StateValue.string("object"),
                    "properties",
                    StateValue.object(Map.of(
                            "answered",
                            StateValue.object(Map.of(
                                    "type",
                                    StateValue.string("boolean"),
                                    "description",
                                    StateValue.string("True when the original request is fully addressed."))),
                            "gapAnalysis",
                            StateValue.object(Map.of(
                                    "type",
                                    StateValue.string("string"),
                                    "description",
                                    StateValue.string("What remains when answered is false."))))),
                    "required",
                    StateValue.array(List.of(StateValue.string("answered"))),
                    "additionalProperties",
                    StateValue.bool(false)));

    private final ChatClient judgeClient;

    private final String instructions;

    private final String feedbackMessageTemplate;

    /**
     * Creates an evaluator with default judge options.
     *
     * @param judgeClient caller-owned trusted judge client
     */
    public AIJudgeLoopEvaluator(ChatClient judgeClient) {
        this(judgeClient, AIJudgeLoopEvaluatorOptions.defaults());
    }

    /**
     * Creates a configured evaluator.
     *
     * @param judgeClient caller-owned trusted judge client
     * @param options judge options
     */
    public AIJudgeLoopEvaluator(ChatClient judgeClient, AIJudgeLoopEvaluatorOptions options) {
        this.judgeClient = Objects.requireNonNull(judgeClient, "judgeClient");
        AIJudgeLoopEvaluatorOptions safeOptions = Objects.requireNonNull(options, "options");
        String template = safeOptions.instructions() == null ? DEFAULT_INSTRUCTIONS : safeOptions.instructions();
        instructions = template.replace(CRITERIA_PLACEHOLDER, renderCriteria(safeOptions.criteria()));
        feedbackMessageTemplate = safeOptions.feedbackMessageTemplate() == null
                ? DEFAULT_FEEDBACK_MESSAGE_TEMPLATE
                : safeOptions.feedbackMessageTemplate();
    }

    @Override
    public CompletionStage<LoopEvaluation> evaluateAsync(LoopContext context, RunCancellation cancellation) {
        LoopContext safeContext = Objects.requireNonNull(context, "context");
        RunCancellation safeCancellation = Objects.requireNonNull(cancellation, "cancellation");
        ChatOptions options =
                ChatOptions.builder().structuredOutput(VERDICT_OUTPUT).build();
        ChatClientRequest request = new ChatClientRequest(judgeMessages(safeContext), options);
        return judgeClient.completeAsync(request, safeCancellation).thenApply(this::evaluation);
    }

    private LoopEvaluation evaluation(ChatResponse response) {
        JudgeVerdict verdict = structuredVerdict(response.text()).orElseGet(() -> markerVerdict(response.text()));
        if (verdict.answered()) {
            return LoopEvaluation.stop();
        }
        String gapAnalysis =
                verdict.gapAnalysis() == null || verdict.gapAnalysis().isBlank()
                        ? UNKNOWN_GAP_ANALYSIS
                        : verdict.gapAnalysis();
        return LoopEvaluation.continueWithFeedback(
                feedbackMessageTemplate.replace(GAP_ANALYSIS_PLACEHOLDER, gapAnalysis));
    }

    private List<Message> judgeMessages(LoopContext context) {
        ArrayList<Content> userContents = new ArrayList<>();
        userContents.add(new TextContent("# Has the original request been fully addressed?\n\n## Original request:\n"));
        context.initialMessages().forEach(message -> userContents.addAll(message.contents()));
        userContents.add(new TextContent(
                "\n\n## Agent's latest response:\n" + context.lastResponse().text()));
        return List.of(
                Message.text(Role.SYSTEM, instructions),
                Message.builder(Role.USER).contents(userContents).build());
    }

    private static Optional<JudgeVerdict> structuredVerdict(String text) {
        StateValue parsed;
        try {
            parsed = StructuredOutputs.parseJson(text);
        } catch (SerializationException ignored) {
            return Optional.empty();
        }
        if (!(parsed instanceof StateValue.ObjectValue object)
                || !(object.values().get("answered") instanceof StateValue.BooleanValue answered)) {
            return Optional.empty();
        }
        String gapAnalysis = null;
        if (object.values().get("gapAnalysis") instanceof StateValue.StringValue gap) {
            gapAnalysis = gap.value();
        }
        return Optional.of(new JudgeVerdict(answered.value(), gapAnalysis));
    }

    private static JudgeVerdict markerVerdict(String text) {
        String normalized = text.toUpperCase(Locale.ROOT);
        boolean answered = !normalized.contains(MORE_VERDICT_MARKER) && normalized.contains(DONE_VERDICT_MARKER);
        return new JudgeVerdict(answered, null);
    }

    private static String renderCriteria(List<String> criteria) {
        StringBuilder rendered = new StringBuilder();
        for (String criterion : criteria) {
            if (criterion != null && !criterion.isBlank()) {
                rendered.append("\n- ").append(criterion);
            }
        }
        return rendered.isEmpty() ? "" : "\n\nThe response must satisfy all of the following criteria:" + rendered;
    }

    private record JudgeVerdict(boolean answered, String gapAnalysis) {}
}
