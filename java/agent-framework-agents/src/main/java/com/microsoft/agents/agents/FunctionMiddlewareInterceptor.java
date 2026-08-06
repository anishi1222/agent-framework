// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolInvocationInterceptContext;
import com.microsoft.agents.tools.ToolInvocationInterceptor;
import com.microsoft.agents.tools.ToolInvocationInterceptorChain;
import java.util.Collection;
import java.util.concurrent.CompletionStage;

final class FunctionMiddlewareInterceptor implements ToolInvocationInterceptor {
    private final AgentSession session;

    private final FunctionMiddlewarePipeline pipeline;

    FunctionMiddlewareInterceptor(AgentSession session, Collection<? extends FunctionMiddleware> middleware) {
        this.session = session;
        this.pipeline = new FunctionMiddlewarePipeline(middleware);
    }

    @Override
    public CompletionStage<StateValue> interceptAsync(
            ToolInvocationInterceptContext context, ToolInvocationInterceptorChain chain) {
        FunctionMiddlewareContext middlewareContext = new FunctionMiddlewareContext(
                session, context, new MiddlewareMetadata(context.invocation().metadata()));
        return pipeline.executeAsync(middlewareContext, next -> chain.proceedAsync(next.invocation()));
    }
}
