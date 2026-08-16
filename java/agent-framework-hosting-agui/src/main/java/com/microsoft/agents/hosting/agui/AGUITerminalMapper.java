// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface AGUITerminalMapper {
    CompletionStage<List<AGUIEvent>> map(HostingOutcome outcome);
}
