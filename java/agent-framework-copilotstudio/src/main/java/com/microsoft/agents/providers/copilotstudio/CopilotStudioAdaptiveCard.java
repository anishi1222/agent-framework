// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Preserves an Adaptive Card and its application-mediated actions.
 *
 * @param schemaVersion optional Adaptive Card version
 * @param body immutable card body elements
 * @param actions immutable actions
 * @param raw complete strict JSON card content
 */
public record CopilotStudioAdaptiveCard(
        String schemaVersion,
        List<StateValue> body,
        List<CopilotStudioCardAction> actions,
        StateValue.ObjectValue raw) {
    /** Creates and defensively copies card data. */
    public CopilotStudioAdaptiveCard {
        body = List.copyOf(body);
        actions = List.copyOf(actions);
        if (raw == null) {
            throw new NullPointerException("raw");
        }
    }
}
