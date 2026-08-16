// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.List;

/** Provides explicit framework-owned workflow codecs for common input and output shapes. */
public final class HostingWorkflowCodecs {
    private HostingWorkflowCodecs() {}

    /**
     * Returns an identity codec for JSON-shaped workflow values.
     *
     * @return state-value codec
     */
    public static HostingWorkflowCodec<StateValue, StateValue> stateValue() {
        return new HostingWorkflowCodec<>() {
            @Override
            public StateValue decodeInput(HostingRunRequest request) {
                if (request.input() == null) {
                    throw new HostingException(
                            HostingErrorCode.UNPROCESSABLE, "Workflow route requires the input member.");
                }
                return request.input();
            }

            @Override
            public StateValue encodeOutput(StateValue output) {
                return HostingRedactor.redact(java.util.Objects.requireNonNull(output, "output"));
            }
        };
    }

    /**
     * Returns a codec for string input and output.
     *
     * <p>The input may be a JSON string or exactly one message whose text is used.
     *
     * @return string codec
     */
    public static HostingWorkflowCodec<String, String> text() {
        return new HostingWorkflowCodec<>() {
            @Override
            public String decodeInput(HostingRunRequest request) {
                if (request.input() instanceof StateValue.StringValue string) {
                    return string.value();
                }
                List<Message> messages = request.messages();
                if (messages.size() == 1 && !messages.getFirst().text().isEmpty()) {
                    return messages.getFirst().text();
                }
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE,
                        "Text workflow route requires a string input or one text message.");
            }

            @Override
            public StateValue encodeOutput(String output) {
                return StateValue.string(java.util.Objects.requireNonNull(output, "output"));
            }
        };
    }
}
