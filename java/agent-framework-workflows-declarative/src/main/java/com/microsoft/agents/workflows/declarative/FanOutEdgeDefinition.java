// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Defines one fan-out edge group.
 *
 * @param source source node identifier
 * @param targets distinct target node identifiers
 */
public record FanOutEdgeDefinition(String source, List<String> targets) implements DeclarativeEdgeDefinition {
    /** Creates a validated immutable fan-out definition. */
    public FanOutEdgeDefinition {
        source = WorkflowDefinitionValidation.requireNonBlank(source, "edge.source");
        targets = copyDistinct(targets);
        if (targets.isEmpty()) {
            throw new DeclarativeWorkflowValidationException("Fan-out edge requires at least one target.");
        }
    }

    @Override
    public DeclarativeEdgeKind kind() {
        return DeclarativeEdgeKind.FAN_OUT;
    }

    private static List<String> copyDistinct(List<String> values) {
        if (values == null) {
            throw new NullPointerException("targets");
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String target = WorkflowDefinitionValidation.requireNonBlank(value, "edge.targets element");
            if (!seen.add(target)) {
                throw new DeclarativeWorkflowValidationException(
                        "Fan-out edge contains duplicate target '" + target + "'.");
            }
            copy.add(target);
        }
        return List.copyOf(copy);
    }
}
