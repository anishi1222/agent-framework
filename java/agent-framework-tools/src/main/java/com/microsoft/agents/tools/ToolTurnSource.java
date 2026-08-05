// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Supplies provider turns to the tool loop without introducing a {@code ChatClient} dependency.
 *
 * <p>Provider and future agent modules adapt their native clients to this interface. Test fakes belong
 * in test source sets.
 */
public interface ToolTurnSource {
    /**
     * Completes one finite provider turn.
     *
     * @param request provider-neutral turn request
     * @param cancellation run cancellation signal
     * @return stage producing the complete response
     */
    CompletionStage<ChatResponse> completeAsync(ToolTurnRequest request, RunCancellation cancellation);

    /**
     * Streams one provider turn.
     *
     * @param request provider-neutral turn request
     * @param cancellation run cancellation signal
     * @return publisher producing response updates
     */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(ToolTurnRequest request, RunCancellation cancellation);
}
