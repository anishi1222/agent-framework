// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Defines one fan-in edge group.
 *
 * @param sources distinct required source node identifiers
 * @param target fan-in target node identifier
 */
public record FanInEdgeDefinition(List<String> sources, String target) implements DeclarativeEdgeDefinition {
    /** Creates a validated immutable fan-in definition. */
    public FanInEdgeDefinition {
        sources = copyDistinct(sources);
        if (sources.size() < 2) {
            throw new DeclarativeWorkflowValidationException("Fan-in edge requires at least two sources.");
        }
        target = WorkflowDefinitionValidation.requireNonBlank(target, "edge.target");
    }

    @Override
    public DeclarativeEdgeKind kind() {
        return DeclarativeEdgeKind.FAN_IN;
    }

    private static List<String> copyDistinct(List<String> values) {
        if (values == null) {
            throw new NullPointerException("sources");
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String source = WorkflowDefinitionValidation.requireNonBlank(value, "edge.sources element");
            if (!seen.add(source)) {
                throw new DeclarativeWorkflowValidationException(
                        "Fan-in edge contains duplicate source '" + source + "'.");
            }
            copy.add(source);
        }
        return List.copyOf(copy);
    }
}
