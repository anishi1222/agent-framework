// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import java.util.List;

/** Configures instructions, criteria, and feedback for an AI judge loop evaluator. */
public final class AIJudgeLoopEvaluatorOptions {
    private final String instructions;

    private final List<String> criteria;

    private final String feedbackMessageTemplate;

    private AIJudgeLoopEvaluatorOptions(Builder builder) {
        instructions = optionalNonBlank(builder.instructions, "instructions");
        criteria = List.copyOf(builder.criteria);
        feedbackMessageTemplate = optionalNonBlank(builder.feedbackMessageTemplate, "feedbackMessageTemplate");
    }

    /** Returns default judge options. */
    public static AIJudgeLoopEvaluatorOptions defaults() {
        return builder().build();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns custom judge instructions, or {@code null} for the default prompt. */
    public String instructions() {
        return instructions;
    }

    /** Returns additional response criteria in authored order. */
    public List<String> criteria() {
        return criteria;
    }

    /** Returns a custom feedback template, or {@code null} for the default template. */
    public String feedbackMessageTemplate() {
        return feedbackMessageTemplate;
    }

    /** Builds immutable AI judge options. */
    public static final class Builder {
        private String instructions;

        private List<String> criteria = List.of();

        private String feedbackMessageTemplate;

        private Builder() {}

        /** Sets judge instructions. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /** Sets additional response criteria. */
        public Builder criteria(List<String> criteria) {
            this.criteria = List.copyOf(criteria);
            return this;
        }

        /** Sets the continuation-feedback template. */
        public Builder feedbackMessageTemplate(String feedbackMessageTemplate) {
            this.feedbackMessageTemplate = feedbackMessageTemplate;
            return this;
        }

        /** Creates immutable options. */
        public AIJudgeLoopEvaluatorOptions build() {
            return new AIJudgeLoopEvaluatorOptions(this);
        }
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }
}
