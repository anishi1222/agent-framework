// Copyright (c) Microsoft. All rights reserved.

/**
 * Provides the provider-neutral chat-client, agent, session, provider, and middleware runtime.
 *
 * <p>{@link com.microsoft.agents.agents.Agent}, {@link com.microsoft.agents.agents.BaseAgent}, and
 * {@link com.microsoft.agents.agents.ChatAgent} share explicit run identity, cancellation, executor
 * lifecycle, bounded streaming, optimistic session persistence, ordered context/history providers,
 * one-shot approval continuation, and agent/chat/function middleware behavior.
 */
package com.microsoft.agents.agents;
