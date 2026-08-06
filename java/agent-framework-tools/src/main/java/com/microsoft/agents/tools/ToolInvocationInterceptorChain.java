// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/** Continues one function invocation through the remaining provider-neutral interceptors. */
@FunctionalInterface
public interface ToolInvocationInterceptorChain {
    /**
     * Continues with an immutable context.
     *
     * <p>Each chain instance may be invoked at most once. A repeated invocation returns a failed stage.
     *
     * @param context invocation context
     * @return stage producing the intercepted result
     */
    CompletionStage<StateValue> proceedAsync(ToolInvocationInterceptContext context);
}
