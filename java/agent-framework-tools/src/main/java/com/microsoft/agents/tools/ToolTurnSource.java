// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
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
     * Atomically drains messages that should be appended before the next provider turn.
     *
     * <p>The loop invokes this hook before every normal turn. When a completed response contains no
     * actionable function calls, it invokes the hook once more with that response before deciding
     * whether the logical run is complete. Returning messages from the latter invocation causes an
     * immediate additional provider turn. Implementations should return an empty list when no
     * messages are pending.
     *
     * @param request current provider-neutral turn request
     * @param previousResponse completed non-actionable response, or {@code null} before a normal turn
     * @return ordered messages removed from the pending source
     */
    default List<Message> drainAdditionalMessages(ToolTurnRequest request, ChatResponse previousResponse) {
        return List.of();
    }

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
