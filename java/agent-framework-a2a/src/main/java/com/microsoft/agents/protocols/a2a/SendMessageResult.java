// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Represents the message-or-task result of a finite send operation. */
public sealed interface SendMessageResult permits Message, Task {}
