// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Represents one strongly typed routing decision after a handoff participant turn. */
public sealed interface HandoffDirective permits HandoffCompletion, HandoffInputRequest, HandoffRequest {}
