// Copyright (c) Microsoft. All rights reserved.

/**
 * Provides provider-neutral sequential, concurrent, handoff, group-chat, and Magentic
 * orchestrations.
 *
 * <p>All patterns expose finite {@code CompletionStage}, bounded {@code Flow.Publisher}, synchronous,
 * and explicitly cancellable {@code RunHandle} views from one execution core. Suspended phases expose
 * one-time process-local finite, streaming, synchronous, and cancellable resume views. Public
 * signatures use only framework-owned and JDK types.
 */
package com.microsoft.agents.orchestrations;
